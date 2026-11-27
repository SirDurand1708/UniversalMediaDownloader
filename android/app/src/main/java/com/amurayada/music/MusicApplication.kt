package com.amurayada.music

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import kotlinx.coroutines.launch

class MusicApplication : Application(), ImageLoaderFactory {

    val appContainer: com.amurayada.music.di.AppContainer by lazy {
        com.amurayada.music.di.AppContainer(this)
    }
    
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            // Use optimized OkHttp (HTTP/2) for images too - Coil 2.x API
            .okHttpClient {
                com.amurayada.music.data.newpipe.NewPipeDownloader.getClient()
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(512L * 1024 * 1024) // 512 MB Aggressive Cache
                    .build()
            }
            .components {
                add(coil.decode.VideoFrameDecoder.Factory())
                add(com.amurayada.music.ui.utils.SongArtKeyer())
                add(com.amurayada.music.ui.utils.SongArtFetcher.Factory(this@MusicApplication))
            }
            .crossfade(true)
            .respectCacheHeaders(false)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .build()
    }

    val applicationScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() +
            kotlinx.coroutines.Dispatchers.IO +
            kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
                com.omnivault.media.utils.LocalCrashLogger.logError(
                    "MusicApplication",
                    "Error no controlado en applicationScope",
                    throwable
                )
            }
    )

    fun clearImageCache() {
        android.util.Log.d("MusicApplication", "Clearing image cache...")
        val loader = coil.Coil.imageLoader(this)
        loader.memoryCache?.clear()
        applicationScope.launch {
            try {
                loader.diskCache?.clear()
            } catch (_: Exception) {}
        }
        android.util.Log.d("MusicApplication", "Image cache cleared!")
    }
    
    companion object {
        private const val PREFS_YTDLP = "ytdlp_engine_prefs"
        private const val KEY_LAST_UPDATE_MS = "last_ytdlp_update_ms"
        private const val SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000L

        private var instance: MusicApplication? = null
        fun getInstance(): MusicApplication? = instance
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        com.omnivault.media.utils.LocalCrashLogger.init(this)
        
        // 0. Initialize notification channels for background downloads and playback
        try {
            com.omnivault.media.utils.NotificationHelper.createNotificationChannels(this)
        } catch (e: Exception) {
            android.util.Log.e("MusicApp", "Failed to create notification channels", e)
        }

        // 0.1 Initialize YouTube Music Client (SharedPreferences for visitorData persistence)
        com.amurayada.music.data.youtube.YouTubeMusicClient.init(this)
        
        // 0.5 Initialize Auth Manager for authenticated API requests
        val authManager = com.amurayada.music.data.auth.YouTubeAuthManager.getInstance(this)
        com.amurayada.music.data.youtube.YouTubeMusicClient.setAuthManager(authManager)
        
        // 1. Initialize NewPipe with Localization
        initNewPipe()
        
        // 2. Network Warm-up ligero + limpieza única de almacenamiento legado sin lectores
        applicationScope.launch {
            try {
                deleteDatabase("omnivault_media_db")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    deleteSharedPreferences("omnivault_storage")
                }
            } catch (_: Exception) {}
            try {
                // Dummy request to open TCP/TLS connection
                val client = com.amurayada.music.data.newpipe.NewPipeDownloader.getClient()
                val request = okhttp3.Request.Builder()
                    .url("https://music.youtube.com")
                    .head() // Lightweight HEAD request
                    .build()
                client.newCall(request).execute().close()
                android.util.Log.d("MusicApp", "Network Warmed Up!")
            } catch (e: Exception) {
                // Ignore
            }
            // Auto-actualizar yt-dlp en segundo plano si está desactualizado o > 7 días
            updateYoutubeDLIfNeeded(force = false)
        }
    }

    /**
     * Actualiza el motor yt-dlp de forma automática o manual (desde Ajustes con force = true)
     * cuando la versión instalada es antigua (2025.x) o han transcurrido más de 7 días.
     */
    suspend fun updateYoutubeDLIfNeeded(
        force: Boolean = false
    ): com.yausername.youtubedl_android.YoutubeDL.UpdateStatus? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val ytdl = com.yausername.youtubedl_android.YoutubeDL.getInstance()
            try {
                ytdl.init(this@MusicApplication)
            } catch (_: Exception) {}

            val installedVer = try { ytdl.version(this@MusicApplication) } catch (_: Exception) { null }
            val isLegacyVersion = installedVer.isNullOrBlank() || installedVer.startsWith("2025.")
            val prefs = getSharedPreferences(PREFS_YTDLP, MODE_PRIVATE)
            val lastUpdate = prefs.getLong(KEY_LAST_UPDATE_MS, 0L)
            val now = System.currentTimeMillis()
            if (!force && !isLegacyVersion && lastUpdate > 0L && (now - lastUpdate) < SEVEN_DAYS_MS) {
                android.util.Log.d("MusicApp", "yt-dlp update skipped (v=$installedVer, < 7 days)")
                return@withContext null
            }

            val status = try {
                ytdl.updateYoutubeDL(
                    this@MusicApplication,
                    com.yausername.youtubedl_android.YoutubeDL.UpdateChannel.NIGHTLY
                )
            } catch (nightlyErr: Exception) {
                android.util.Log.w("MusicApp", "yt-dlp NIGHTLY update failed, trying STABLE: ${nightlyErr.message}")
                ytdl.updateYoutubeDL(
                    this@MusicApplication,
                    com.yausername.youtubedl_android.YoutubeDL.UpdateChannel.STABLE
                )
            }
            prefs.edit().putLong(KEY_LAST_UPDATE_MS, now).apply()
            android.util.Log.i("MusicApp", "yt-dlp update status: $status (prev=$installedVer)")
            status
        } catch (e: Exception) {
            android.util.Log.w("MusicApp", "yt-dlp update failed: ${e.message}")
            com.omnivault.media.utils.LocalCrashLogger.logError("MusicApp", "Error actualizando motor yt-dlp", e)
            null
        }
    }
    
    private fun initNewPipe() {
        if (org.schabi.newpipe.extractor.NewPipe.getDownloader() == null) {
            org.schabi.newpipe.extractor.NewPipe.init(com.amurayada.music.data.newpipe.NewPipeDownloader())
        }
        
        try {
            // Force Localization to Colombia/Local to fix search relevancy (e.g. Morat)
            // Force Localization to Colombia/Local to fix search relevancy (e.g. Morat)
            // Note: Direct InnerTube implementation in SearchRepository handles "gl":"CO" directly.
            // NewPipe settings are read-only in this version or interface.
            /*
            val service = org.schabi.newpipe.extractor.NewPipe.getService(0)
            service.contentCountry = myCountry
            service.localization = myLanguage
            */
            android.util.Log.d("MusicApp", "NewPipe Init Complete (Localization handled in Direct API)")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
