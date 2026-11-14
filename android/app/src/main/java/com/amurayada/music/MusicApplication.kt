package com.amurayada.music

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import kotlinx.coroutines.launch

class MusicApplication : Application(), ImageLoaderFactory {
    
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
            }
            .crossfade(true)
            .respectCacheHeaders(false)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .build()
    }

    fun clearImageCache() {
        android.util.Log.d("MusicApplication", "Clearing image cache...")
        val loader = coil.Coil.imageLoader(this)
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
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
        
        // 2. Network Warm-up & Elite Engine Initialization (youtubedl-android: yt-dlp + FFmpeg + Aria2c)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                com.yausername.youtubedl_android.YoutubeDL.getInstance().init(this@MusicApplication)
                com.yausername.ffmpeg.FFmpeg.getInstance().init(this@MusicApplication)
                com.yausername.aria2c.Aria2c.getInstance().init(this@MusicApplication)
                android.util.Log.d("MusicApp", "youtubedl-android (yt-dlp + FFmpeg + Aria2c) initialized")

                // Solo actualizar binarios automáticamente si nunca se ha actualizado o han pasado más de 7 días
                updateYoutubeDLIfNeeded(force = false)
            } catch (e: Exception) {
                android.util.Log.e("MusicApp", "Failed to initialize youtubedl-android", e)
            }

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
        }
    }

    /**
     * Actualiza el motor yt-dlp de forma manual (desde Ajustes con force = true)
     * o de forma diferida únicamente cuando han transcurrido más de 7 días desde la última actualización.
     */
    suspend fun updateYoutubeDLIfNeeded(
        force: Boolean = false
    ): com.yausername.youtubedl_android.YoutubeDL.UpdateStatus? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val prefs = getSharedPreferences(PREFS_YTDLP, MODE_PRIVATE)
            val lastUpdate = prefs.getLong(KEY_LAST_UPDATE_MS, 0L)
            val now = System.currentTimeMillis()
            if (!force && lastUpdate > 0L && (now - lastUpdate) < SEVEN_DAYS_MS) {
                android.util.Log.d("MusicApp", "yt-dlp update skipped (< 7 days since last check)")
                return@withContext null
            }

            val status = com.yausername.youtubedl_android.YoutubeDL.getInstance().updateYoutubeDL(
                this@MusicApplication,
                com.yausername.youtubedl_android.YoutubeDL.UpdateChannel.STABLE
            )
            prefs.edit().putLong(KEY_LAST_UPDATE_MS, now).apply()
            android.util.Log.i("MusicApp", "yt-dlp update status: $status")
            status
        } catch (e: Exception) {
            android.util.Log.w("MusicApp", "yt-dlp update failed: ${e.message}")
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
