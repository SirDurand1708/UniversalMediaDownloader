package com.omnivault.media.downloader

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import androidx.documentfile.provider.DocumentFile
import com.amurayada.music.data.auth.YouTubeAuthManager
import com.amurayada.music.data.database.DownloadDatabase
import com.amurayada.music.data.database.DownloadEntity
import com.omnivault.media.model.DownloadStatus
import com.omnivault.media.model.MediaFormat
import com.omnivault.media.utils.CoverArtHelper
import com.omnivault.media.utils.FilenameStandardizer
import com.omnivault.media.utils.LyricsHelper
import com.omnivault.media.utils.StorageHelper
import com.omnivault.media.utils.UrlUtils
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import com.amurayada.music.data.database.DownloadQueueEntity

data class PlaylistEntryPreview(
    val title: String,
    val url: String,
    val durationText: String = ""
)

data class UniversalMediaMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val thumbnailUrl: String,
    val sourceUrl: String
)

data class UniversalDownloadState(
    val processId: String,
    val url: String,
    val format: MediaFormat,
    val title: String = "Analizando enlace…",
    val artist: String = "",
    val thumbnailUrl: String = "",
    val progress: Float = 0f,
    val etaInSeconds: Long = -1L,
    val speedText: String = "Conectando…",
    val status: DownloadStatus = DownloadStatus.FETCHING_METADATA,
    val localFilePath: String? = null,
    val fileSizeBytes: Long = 0L,
    val errorReason: String? = null
)

data class UniversalDownloadResult(
    val processId: String,
    val metadata: UniversalMediaMetadata,
    val format: MediaFormat,
    val savedPath: String,
    val physicalFile: File?,
    val fileSizeBytes: Long,
    val localThumbnailPath: String?
)

/**
 * Motor Universal de Descargas basado en `youtubedl-android` (`yt-dlp` + `FFmpeg` + `Aria2c`).
 * Soporta cualquier URL (YouTube, YouTube Music, TikTok, Instagram Reels, X/Twitter, Facebook, etc.)
 * y exporta de forma segura a `Music/OmniVault` o `Movies/OmniVault` vía `MediaStore` / `DocumentFile`
 * registrando la descarga en `DownloadDatabase` (Room).
 */
class UniversalDownloadEngine private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val db = DownloadDatabase.getDatabase(appContext)
    private val downloadDao = db.downloadDao()
    private val downloadQueueDao = db.downloadQueueDao()
    private val downloadSemaphore = Semaphore(2)

    private val _downloadsState = MutableStateFlow<Map<String, UniversalDownloadState>>(emptyMap())
    val downloadsState: StateFlow<Map<String, UniversalDownloadState>> = _downloadsState.asStateFlow()
    private val activeProcesses = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val metadataCache = LruCache<String, UniversalMediaMetadata>(40)

    fun computeStableSongId(url: String, isVideo: Boolean): Long {
        val norm = UrlUtils.normalizeMediaUrl(url)
        val key = "$norm|${if (isVideo) "video" else "audio"}"
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        val rawLong = ByteBuffer.wrap(digest, 0, 8).long
        return if (rawLong == Long.MIN_VALUE) 1L else kotlin.math.abs(rawLong)
    }

    suspend fun isAlreadyDownloaded(url: String, isVideo: Boolean): Boolean {
        val norm = UrlUtils.normalizeMediaUrl(url)
        val stableId = computeStableSongId(url, isVideo)
        if (downloadDao.isDownloaded(stableId)) return true
        return downloadDao.getDownloadsByType(isVideo).any {
            UrlUtils.normalizeMediaUrl(it.youtubeUrl) == norm
        }
    }

    suspend fun persistQueuedItem(processId: String, url: String, format: MediaFormat, title: String = "En cola…") {
        downloadQueueDao.upsert(
            DownloadQueueEntity(
                processId = processId,
                url = url,
                normalizedUrl = UrlUtils.normalizeMediaUrl(url),
                formatName = format.name,
                title = title,
                status = "QUEUED"
            )
        )
    }

    suspend fun restorePersistedQueueOnStartup(): List<DownloadQueueEntity> {
        val pending = downloadQueueDao.getAll()
        if (pending.isEmpty()) return emptyList()
        val settingsPrefs = appContext.getSharedPreferences("music_settings", Context.MODE_PRIVATE)
        val autoResume = settingsPrefs.getBoolean("auto_resume_downloads", false)
        val updatedStates = mutableMapOf<String, UniversalDownloadState>()
        for (entity in pending) {
            val fmt = MediaFormat.entries.firstOrNull { it.name == entity.formatName } ?: MediaFormat.MP3_320
            val targetStatus = if (autoResume) DownloadStatus.QUEUED else DownloadStatus.PAUSED
            if (!autoResume && entity.status != "ERROR") {
                downloadQueueDao.updateStatus(entity.processId, "PAUSED", entity.errorReason)
            }
            updatedStates[entity.processId] = UniversalDownloadState(
                processId = entity.processId,
                url = entity.url,
                title = entity.title,
                format = fmt,
                status = if (entity.status == "ERROR") DownloadStatus.ERROR else targetStatus,
                speedText = if (entity.status == "ERROR") "Error" else if (autoResume) "Reanudando…" else "En pausa",
                errorReason = entity.errorReason
            )
        }
        _downloadsState.update { it + updatedStates }
        return if (autoResume) pending.filter { it.status != "ERROR" } else emptyList()
    }

    suspend fun markActiveDownloadsPaused(reason: String = "Pausado por límite de sistema") {
        val current = _downloadsState.value.values.filter {
            it.status == DownloadStatus.QUEUED ||
                it.status == DownloadStatus.FETCHING_METADATA ||
                it.status == DownloadStatus.DOWNLOADING ||
                it.status == DownloadStatus.PROCESSING
        }
        for (item in current) {
            cancelAnalysisProcess(item.processId)
            downloadQueueDao.updateStatus(item.processId, "PAUSED", reason)
            _downloadsState.update { map ->
                map + (item.processId to item.copy(status = DownloadStatus.PAUSED, speedText = "En pausa", errorReason = reason))
            }
        }
    }

    suspend fun removePersistedQueueItem(processId: String) {
        downloadQueueDao.deleteById(processId)
        _downloadsState.update { it - processId }
    }

    suspend fun getResumableQueueItems(): List<DownloadQueueEntity> {
        return downloadQueueDao.getAll()
    }

    suspend fun extractPlaylistEntries(playlistUrl: String): List<PlaylistEntryPreview> = withContext(Dispatchers.IO) {
        ensureInitialized()
        val cleanUrl = UrlUtils.extractUrlFromText(playlistUrl)?.trim() ?: playlistUrl.trim()
        val processId = "pl_extract_${System.currentTimeMillis()}"
        val request = YoutubeDLRequest(cleanUrl).apply {
            addOption("--flat-playlist")
            addOption("--dump-single-json")
            addOption("--no-warnings")
            addOption("--socket-timeout", 15)
        }
        val tempCookieDir = File(appContext.cacheDir, "ytdl_pl_cookie_$processId").apply { mkdirs() }
        try {
            writeAuthCookiesIfAvailable(tempCookieDir)?.let { cookieFile ->
                request.addOption("--cookies", cookieFile.absolutePath)
            }
            val response = YoutubeDL.getInstance().execute(request, processId, null)
            val jsonText = response.out.trim()
            if (jsonText.isNotBlank()) {
                val root = org.json.JSONObject(jsonText)
                val entries = root.optJSONArray("entries")
                if (entries != null && entries.length() > 0) {
                    val result = mutableListOf<PlaylistEntryPreview>()
                    for (i in 0 until entries.length()) {
                        val obj = entries.optJSONObject(i) ?: continue
                        val id = obj.optString("id", "")
                        val rawEntryUrl = obj.optString("url", "")
                        val resolvedUrl = when {
                            rawEntryUrl.startsWith("http") -> rawEntryUrl
                            id.isNotBlank() -> "https://www.youtube.com/watch?v=$id"
                            else -> ""
                        }
                        if (resolvedUrl.isBlank()) continue
                        val title = obj.optString("title", "Elemento ${i + 1}").ifBlank { "Elemento ${i + 1}" }
                        val durSec = obj.optLong("duration", 0L)
                        val durText = if (durSec > 0) com.amurayada.music.ui.screens.nowplaying.formatDuration(durSec * 1000L) else ""
                        result.add(PlaylistEntryPreview(title = title, url = resolvedUrl, durationText = durText))
                    }
                    if (result.isNotEmpty()) return@withContext result
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("UniversalDownloadEngine", "extractPlaylistEntries yt-dlp fallback: ${e.message}")
        } finally {
            try { tempCookieDir.deleteRecursively() } catch (_: Exception) {}
        }

        // Fallback via MediaExtractor
        val fallbackUrls = MediaExtractor.extractPlaylistVideoUrls(cleanUrl)
        return@withContext fallbackUrls.mapIndexed { idx, u ->
            PlaylistEntryPreview(title = "Pista / Video ${idx + 1}", url = u)
        }
    }

    private fun normalizeCacheKey(url: String): String =
        (UrlUtils.extractUrlFromText(url) ?: url).trim()

    fun cacheMetadata(url: String, metadata: UniversalMediaMetadata) {
        val key = normalizeCacheKey(url)
        if (key.isNotBlank()) {
            synchronized(metadataCache) {
                metadataCache.put(key, metadata)
            }
        }
    }

    fun getCachedMetadata(url: String): UniversalMediaMetadata? {
        val key = normalizeCacheKey(url)
        if (key.isBlank()) return null
        return synchronized(metadataCache) {
            metadataCache.get(key)
        }
    }

    fun cancelAnalysisProcess(processId: String) {
        try {
            YoutubeDL.getInstance().destroyProcessById(processId)
        } catch (_: Exception) {}
    }

    companion object {
        private const val PUBLIC_FOLDER_NAME = "OmniVault"

        @Volatile
        private var INSTANCE: UniversalDownloadEngine? = null

        fun getInstance(context: Context): UniversalDownloadEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UniversalDownloadEngine(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    @Synchronized
    fun ensureInitialized() {
        try {
            YoutubeDL.getInstance().init(appContext)
            FFmpeg.getInstance().init(appContext)
            Aria2c.getInstance().init(appContext)
        } catch (e: Exception) {
            android.util.Log.w("UniversalDownloadEngine", "Init check warning: ${e.message}")
        }
    }

    /**
     * Cancela en caliente el proceso nativo de `yt-dlp` / `FFmpeg` asociado al `processId`.
     */
    fun unmarkDeletedFromPrefs(songId: Long, savedPath: String?, physicalPath: String? = null) {
        try {
            val prefs = appContext.getSharedPreferences("omnivault_prefs", Context.MODE_PRIVATE)
            val hiddenIds = prefs.getStringSet("hidden_song_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
            val deletedPaths = prefs.getStringSet("deleted_song_paths", emptySet())?.toMutableSet() ?: mutableSetOf()
            var changed = hiddenIds.remove(songId.toString())
            if (!savedPath.isNullOrBlank() && deletedPaths.remove(savedPath)) changed = true
            if (!physicalPath.isNullOrBlank() && deletedPaths.remove(physicalPath)) changed = true
            if (changed) {
                prefs.edit()
                    .putStringSet("hidden_song_ids", hiddenIds)
                    .putStringSet("deleted_song_paths", deletedPaths)
                    .apply()
            }
        } catch (_: Exception) {}
    }

    suspend fun markFallbackCompleted(processId: String) {
        try {
            downloadQueueDao.deleteById(processId)
            val current = _downloadsState.value.toMutableMap()
            if (current.remove(processId) != null) {
                _downloadsState.value = current
            }
        } catch (_: Exception) {}
    }

    fun cancelDownload(processId: String) {
        try {
            YoutubeDL.getInstance().destroyProcessById(processId)
        } catch (_: Exception) {}
        _downloadsState.update { current ->
            val existing = current[processId]
            if (existing != null) {
                current + (processId to existing.copy(status = DownloadStatus.CANCELLED, speedText = "Cancelado"))
            } else {
                current
            }
        }
    }

    /**
     * Paso A: Extracción universal de metadatos reales (`title`, `uploader`/`artist`, `duration`, `thumbnail`)
     * mediante `YoutubeDL.getInstance().getInfo(request)` con respaldo automático en `MediaExtractor`.
     */
    suspend fun extractMetadata(
        url: String,
        preferredFormat: MediaFormat = MediaFormat.M4A,
        processId: String = "info_${System.currentTimeMillis()}"
    ): UniversalMediaMetadata = withContext(Dispatchers.IO) {
        val cleanUrl = UrlUtils.extractUrlFromText(url)?.trim() ?: url.trim()
        getCachedMetadata(cleanUrl)?.let { return@withContext it }

        ensureInitialized()
        val tempCookieDir = File(appContext.cacheDir, "ytdl_info_${System.currentTimeMillis()}")

        try {
            val infoRequest = YoutubeDLRequest(cleanUrl).apply {
                addOption("--no-playlist")
                addOption("--flat-playlist")
                addOption("--skip-download")
                addOption("--socket-timeout", 4)
                addOption("--retries", 0)
                addOption("--extractor-args", "youtube:skip=dash,hls")
                writeAuthCookiesIfAvailable(tempCookieDir)?.let { cookieFile ->
                    addOption("--cookies", cookieFile.absolutePath)
                }
            }

            val videoInfo = YoutubeDL.getInstance().getInfo(infoRequest)
            val rawTitle: String = videoInfo.title?.takeIf { it.isNotBlank() } ?: "OmniVault Media"
            val standardizedTitle = FilenameStandardizer.standardizeTitle(rawTitle)
            val uploader: String = videoInfo.uploader?.takeIf { it.isNotBlank() } ?: "OmniVault"
            val durationMs: Long = (videoInfo.duration.toLong().coerceAtLeast(0L)) * 1000L
            val thumbnail: String = videoInfo.thumbnail?.takeIf { it.isNotBlank() } ?: ""

            val result = UniversalMediaMetadata(
                title = standardizedTitle,
                artist = uploader,
                album = uploader,
                durationMs = durationMs,
                thumbnailUrl = thumbnail,
                sourceUrl = cleanUrl
            )
            cacheMetadata(cleanUrl, result)
            return@withContext result
        } catch (ce: CancellationException) {
            cancelAnalysisProcess(processId)
            throw ce
        } catch (e: Exception) {
            if (!coroutineContext.isActive) {
                cancelAnalysisProcess(processId)
                throw CancellationException("Metadata extraction cancelled")
            }
            android.util.Log.w(
                "UniversalDownloadEngine",
                "YoutubeDL.getInfo fallback to MediaExtractor for $cleanUrl: ${e.message}"
            )
            val fallback = MediaExtractor.extract(cleanUrl, preferredFormat)
            val result = UniversalMediaMetadata(
                title = FilenameStandardizer.standardizeTitle(fallback.title),
                artist = fallback.author.ifBlank { "OmniVault" },
                album = fallback.author.ifBlank { "OmniVault" },
                durationMs = fallback.durationSeconds.coerceAtLeast(0L) * 1000L,
                thumbnailUrl = fallback.thumbnailUrl,
                sourceUrl = cleanUrl
            )
            cacheMetadata(cleanUrl, result)
            return@withContext result
        } finally {
            if (!coroutineContext.isActive) {
                cancelAnalysisProcess(processId)
            }
            try {
                if (tempCookieDir.exists()) tempCookieDir.deleteRecursively()
            } catch (_: Exception) {}
        }
    }

    /**
     * Ejecuta el flujo completo de descarga universal (Paso A -> Paso B -> Paso C + Room DB).
     */
    suspend fun executeDownload(
        url: String,
        format: MediaFormat,
        processId: String = "dl_${System.currentTimeMillis()}",
        preloadedSongId: Long? = null,
        onStateChanged: ((UniversalDownloadState) -> Unit)? = null
    ): UniversalDownloadResult = withContext(Dispatchers.IO) {
        ensureInitialized()
        val cleanUrl = UrlUtils.extractUrlFromText(url)?.trim() ?: url.trim()
        val tempCacheDir = File(appContext.cacheDir, "ytdl_cache_$processId")

        var currentState = UniversalDownloadState(
            processId = processId,
            url = cleanUrl,
            format = format,
            status = DownloadStatus.QUEUED,
            speedText = "En cola…"
        )

        fun publishState(state: UniversalDownloadState) {
            _downloadsState.update { it + (processId to state) }
            onStateChanged?.invoke(state)
        }

        publishState(currentState)
        persistQueuedItem(processId, cleanUrl, format, currentState.title)

        downloadSemaphore.withPermit {
        try {
            if (!StorageHelper.hasEnoughFreeSpace(appContext)) {
                throw IllegalStateException("Espacio insuficiente en el dispositivo (mínimo 250 MB libres)")
            }

            activeProcesses.add(processId)
            cleanupOrphanCacheDirs()
            if (tempCacheDir.exists()) tempCacheDir.deleteRecursively()
            tempCacheDir.mkdirs()

            // =========================================================================
            // PASO A: Reutilizar Metadatos Cacheados (Prohibido re-analizar con getInfo)
            // =========================================================================
            val cachedMeta = getCachedMetadata(cleanUrl)
            var metadata = cachedMeta ?: UniversalMediaMetadata(
                title = "OmniVault Media",
                artist = "OmniVault",
                album = "OmniVault",
                durationMs = 0L,
                thumbnailUrl = "",
                sourceUrl = cleanUrl
            )
            currentState = currentState.copy(
                title = metadata.title,
                artist = metadata.artist,
                thumbnailUrl = metadata.thumbnailUrl,
                status = DownloadStatus.DOWNLOADING,
                progress = 0.01f,
                speedText = "Iniciando descarga…"
            )
            publishState(currentState)

            // =========================================================================
            // PASO B: Descarga en Caché con YoutubeDLRequest + FFmpeg
            // =========================================================================
            var safeBaseName = if (cachedMeta != null) {
                StorageHelper.sanitizeFilename(metadata.title).ifBlank { "media_$processId" }
            } else {
                ""
            }
            val outTemplate = if (safeBaseName.isNotBlank()) {
                File(tempCacheDir, "$safeBaseName.%(ext)s").absolutePath
            } else {
                File(tempCacheDir, "%(title).120B.%(ext)s").absolutePath
            }

            val request = YoutubeDLRequest(cleanUrl).apply {
                addOption("--no-playlist")
                addOption("--no-mtime")
                addOption("--concurrent-fragments", 8)
                addOption("--buffer-size", "256K")
                addOption("--extractor-args", "youtube:player_client=android_vr,tv,default")
                addOption("--retries", 3)
                addOption("--fragment-retries", 3)
                addOption("--socket-timeout", 20)
                addOption("-P", tempCacheDir.absolutePath)
                addOption("-o", outTemplate)

                val hasAria2c = File(appContext.applicationInfo.nativeLibraryDir, "libaria2c.so").exists()
                if (hasAria2c) {
                    val connCount = if (activeProcesses.size > 1) 4 else 16
                    addOption("--downloader", "aria2c")
                    addOption("--downloader-args", "aria2c:-x $connCount -s $connCount --max-connection-per-server=$connCount -k 1M --min-split-size=1M --file-allocation=none --summary-interval=0")
                }

                writeAuthCookiesIfAvailable(tempCacheDir)?.let { cookieFile ->
                    addOption("--cookies", cookieFile.absolutePath)
                }

                when (format) {
                    MediaFormat.MP3_320 -> {
                        addOption("-x")
                        addOption("--audio-format", "mp3")
                        addOption("--audio-quality", "0")
                        addOption("--add-metadata")
                    }
                    MediaFormat.M4A -> {
                        addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
                        addOption("-x")
                        addOption("--audio-format", "m4a")
                        addOption("--add-metadata")
                    }
                    MediaFormat.MP4_MAX -> {
                        addOption(
                            "-f",
                            "bestvideo+bestaudio[ext=m4a]/bestvideo+bestaudio/best"
                        )
                        addOption("-S", "res,fps,br")
                        addOption("--merge-output-format", "mp4")
                        addOption("--add-metadata")
                    }
                    MediaFormat.MP4_1080 -> {
                        addOption(
                            "-f",
                            "bestvideo[height<=1080]+bestaudio[ext=m4a]/bestvideo[height<=1080]+bestaudio/best[height<=1080]/best"
                        )
                        addOption("-S", "res,fps,br")
                        addOption("--merge-output-format", "mp4")
                        addOption("--add-metadata")
                    }
                    MediaFormat.MP4_720 -> {
                        addOption(
                            "-f",
                            "bestvideo[height<=720]+bestaudio[ext=m4a]/bestvideo[height<=720]+bestaudio/best[height<=720]/best"
                        )
                        addOption("-S", "res,fps,br")
                        addOption("--merge-output-format", "mp4")
                        addOption("--add-metadata")
                    }
                    MediaFormat.MP4_480 -> {
                        addOption(
                            "-f",
                            "bestvideo[height<=480]+bestaudio[ext=m4a]/bestvideo[height<=480]+bestaudio/best[height<=480]/best"
                        )
                        addOption("-S", "res,fps,br")
                        addOption("--merge-output-format", "mp4")
                        addOption("--add-metadata")
                    }
                }
            }

            var lastCallbackMs = 0L
            YoutubeDL.getInstance().execute(request, processId) { progress, etaInSeconds, line ->
                val now = System.currentTimeMillis()
                if (now - lastCallbackMs >= 200L || progress >= 100f) {
                    lastCallbackMs = now
                    val normalizedProgress = if (progress > 0f) {
                        (progress / 100f).coerceIn(0.01f, 0.95f)
                    } else {
                        currentState.progress
                    }
                    val speedRegex = Regex("""(\d+(?:\.\d+)?\s*[KMGT]?i?B/s)""")
                    val speedParsed = speedRegex.find(line)?.groupValues?.get(1)
                    val speedLabel = when {
                        speedParsed != null -> speedParsed
                        etaInSeconds > 0 -> "ETA ${etaInSeconds}s"
                        line.contains("[Merger]", ignoreCase = true) || line.contains("[ExtractAudio]", ignoreCase = true) ->
                            "Procesando con FFmpeg…"
                        else -> "Descargando…"
                    }

                    currentState = currentState.copy(
                        progress = normalizedProgress,
                        etaInSeconds = etaInSeconds,
                        speedText = speedLabel,
                        status = DownloadStatus.DOWNLOADING
                    )
                    publishState(currentState)
                }
            }

            val downloadedCacheFile = findDownloadedFile(tempCacheDir)
                ?: throw IllegalStateException("yt-dlp no generó un archivo de salida válido en caché")

            if (cachedMeta == null) {
                val resolvedTitle = FilenameStandardizer.standardizeTitle(
                    downloadedCacheFile.nameWithoutExtension.ifBlank { "OmniVault Media" }
                )
                metadata = metadata.copy(title = resolvedTitle)
                safeBaseName = StorageHelper.sanitizeFilename(resolvedTitle).ifBlank { "media_$processId" }
            }

            // =========================================================================
            // PASO C: Exportación Segura a Almacenamiento (MediaStore / DocumentFile) + Room DB
            // =========================================================================
            currentState = currentState.copy(
                progress = 0.97f,
                status = DownloadStatus.PROCESSING,
                speedText = "Guardando en dispositivo…"
            )
            publishState(currentState)

            val finalExt = when (format) {
                MediaFormat.MP3_320 -> "mp3"
                MediaFormat.M4A -> if (downloadedCacheFile.extension.equals("mp3", ignoreCase = true)) "mp3" else "m4a"
                MediaFormat.MP4_MAX, MediaFormat.MP4_1080, MediaFormat.MP4_720, MediaFormat.MP4_480 -> "mp4"
            }
            val finalMimeType = when (finalExt) {
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                else -> "video/mp4"
            }
            val displayName = "$safeBaseName.$finalExt"

            // Si es audio, asegurar carátula local y archivo de letras sincronizadas (.lrc)
            var localCoverPath: String? = null
            var effectiveThumbUrl = metadata.thumbnailUrl
            if (effectiveThumbUrl.isNullOrBlank()) {
                val ytId = Regex("(?:v=|youtu\\.be/|shorts/|embed/|live/)([A-Za-z0-9_-]{11})")
                    .find(cleanUrl)?.groupValues?.getOrNull(1)
                if (!ytId.isNullOrBlank()) {
                    effectiveThumbUrl = "https://i.ytimg.com/vi/$ytId/hqdefault.jpg"
                }
            }
            val coverBytes = CoverArtHelper.downloadCoverBytes(effectiveThumbUrl)
            if (coverBytes != null && coverBytes.isNotEmpty()) {
                val cachedCover = CoverArtHelper.cacheCoverLocally(appContext, processId, coverBytes)
                if (cachedCover != null) {
                    localCoverPath = cachedCover.absolutePath
                }
            }

            if (format.isAudio) {
                val lyricsContent = LyricsHelper.fetchLyrics(metadata.title, metadata.artist)
                if (coverBytes != null && coverBytes.isNotEmpty()) {
                    if (finalExt == "mp3") {
                        CoverArtHelper.embedCoverInMp3(downloadedCacheFile, coverBytes, metadata.title, metadata.artist, lyricsContent)
                    } else {
                        CoverArtHelper.embedCoverInM4a(downloadedCacheFile, coverBytes, metadata.title, metadata.artist, lyricsContent)
                    }
                }
                if (!lyricsContent.isNullOrBlank()) {
                    val outDir = StorageHelper.getOutputDirectory(true, appContext)
                    val lrcFile = LyricsHelper.saveLrcFile(outDir, safeBaseName, lyricsContent)
                    if (lrcFile != null) {
                        StorageHelper.scanMediaFile(appContext, lrcFile, "text/plain")
                    }
                }
            }

            val exported = exportToStorage(
                sourceFile = downloadedCacheFile,
                displayName = displayName,
                mimeType = finalMimeType,
                isAudio = format.isAudio
            )

            // Registrar en DownloadDatabase (Room SQLite)
            val entitySongId = preloadedSongId ?: computeStableSongId(cleanUrl, !format.isAudio)
            val entity = DownloadEntity(
                songId = entitySongId,
                title = metadata.title,
                artist = metadata.artist,
                album = if (format.isAudio) metadata.album else "Video",
                duration = metadata.durationMs,
                youtubeUrl = cleanUrl,
                localPath = exported.first,
                thumbnailUrl = localCoverPath ?: metadata.thumbnailUrl,
                fileSize = exported.third,
                isVideo = !format.isAudio,
                mimeType = finalMimeType
            )
            downloadDao.insertDownload(entity)
            downloadQueueDao.deleteById(processId)
            if (format.isAudio) {
                unmarkDeletedFromPrefs(entitySongId, exported.first, exported.second?.absolutePath)
            }

            currentState = currentState.copy(
                progress = 1f,
                etaInSeconds = 0L,
                speedText = "Completado",
                status = DownloadStatus.COMPLETED,
                localFilePath = exported.first,
                fileSizeBytes = exported.third,
                thumbnailUrl = localCoverPath ?: metadata.thumbnailUrl
            )
            publishState(currentState)

            return@withContext UniversalDownloadResult(
                processId = processId,
                metadata = metadata,
                format = format,
                savedPath = exported.first,
                physicalFile = exported.second,
                fileSizeBytes = exported.third,
                localThumbnailPath = localCoverPath
            )
        } catch (e: CancellationException) {
            try { downloadQueueDao.deleteById(processId) } catch (_: Exception) {}
            cancelDownload(processId)
            throw e
        } catch (e: Exception) {
            val errMsg = e.localizedMessage ?: "Error en descarga con yt-dlp"
            downloadQueueDao.updateStatus(processId, "ERROR", errMsg)
            val failedState = currentState.copy(
                status = DownloadStatus.ERROR,
                speedText = "Error",
                errorReason = errMsg
            )
            publishState(failedState)
            throw e
        } finally {
            activeProcesses.remove(processId)
            try {
                if (tempCacheDir.exists()) tempCacheDir.deleteRecursively()
            } catch (_: Exception) {}
        }
        }
    }

    private fun cleanupOrphanCacheDirs() {
        try {
            appContext.cacheDir.listFiles()?.forEach { file ->
                if (file.isDirectory && file.name.startsWith("ytdl_cache_")) {
                    val id = file.name.removePrefix("ytdl_cache_")
                    if (id !in activeProcesses) {
                        file.deleteRecursively()
                    }
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Exporta el archivo desde `tempCacheDir` hacia:
     * 1) `DocumentFile` si el usuario eligió una carpeta personalizada SAF, o
     * 2) `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` (`Music/OmniVault`) /
     *    `MediaStore.Video.Media.EXTERNAL_CONTENT_URI` (`Movies/OmniVault`) en Android 10+.
     */
    private fun exportToStorage(
        sourceFile: File,
        displayName: String,
        mimeType: String,
        isAudio: Boolean
    ): Triple<String, File?, Long> {
        val fileLength = sourceFile.length()

        // 1. Verificar si el usuario seleccionó carpeta SAF personalizada en Ajustes
        val settingsPrefs = appContext.getSharedPreferences("music_settings", Context.MODE_PRIVATE)
        val downloadMode = settingsPrefs.getString("download_path_mode", "default")
        val customUriString = settingsPrefs.getString("download_path_uri", null)

        if (downloadMode == "custom" && !customUriString.isNullOrBlank()) {
            try {
                val treeUri = Uri.parse(customUriString)
                val treeDoc = DocumentFile.fromTreeUri(appContext, treeUri)
                if (treeDoc != null && treeDoc.canWrite()) {
                    treeDoc.findFile(displayName)?.delete()
                    val targetDoc = treeDoc.createFile(mimeType, displayName)
                    if (targetDoc != null) {
                        val out = appContext.contentResolver.openOutputStream(targetDoc.uri)
                            ?: throw java.io.IOException("No se pudo abrir flujo SAF")
                        out.use { stream ->
                            sourceFile.inputStream().use { input ->
                                input.copyTo(stream, bufferSize = 262_144)
                            }
                        }
                        return Triple(targetDoc.uri.toString(), null, fileLength)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("UniversalDownloadEngine", "Custom DocumentFile export failed, using MediaStore: ${e.message}")
            }
        }

        // 2. Android 10+ (API 29+): MediaStore.Audio / MediaStore.Video en Music/OmniVault o Movies/OmniVault
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val collectionUri = if (isAudio) {
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }
                val relativePath = if (isAudio) {
                    "${Environment.DIRECTORY_MUSIC}/$PUBLIC_FOLDER_NAME"
                } else {
                    "${Environment.DIRECTORY_MOVIES}/$PUBLIC_FOLDER_NAME"
                }

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val insertedUri = appContext.contentResolver.insert(collectionUri, values)
                if (insertedUri != null) {
                    try {
                        val out = appContext.contentResolver.openOutputStream(insertedUri)
                            ?: throw java.io.IOException("No se pudo abrir flujo MediaStore")
                        out.use { stream ->
                            sourceFile.inputStream().use { input ->
                                input.copyTo(stream, bufferSize = 262_144)
                            }
                        }
                        val completeValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                        }
                        appContext.contentResolver.update(insertedUri, completeValues, null, null)
                        return Triple(insertedUri.toString(), null, fileLength)
                    } catch (e: Exception) {
                        try { appContext.contentResolver.delete(insertedUri, null, null) } catch (_: Exception) {}
                        throw e
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("UniversalDownloadEngine", "MediaStore export failed, falling back to StorageHelper: ${e.message}")
            }
        }

        // 3. Respaldo directo en sistema de archivos (Android 7–9 o rutas personalizadas en StorageHelper)
        val savedFile = StorageHelper.saveMediaFile(
            context = appContext,
            sourceFile = sourceFile,
            displayName = displayName,
            isAudio = isAudio,
            mimeType = mimeType
        )
        return Triple(savedFile.absolutePath, savedFile, savedFile.length())
    }

    private fun findDownloadedFile(dir: File): File? {
        return dir.listFiles()
            ?.filter { file ->
                file.isFile &&
                    file.name != "cookies.txt" &&
                    !file.name.endsWith(".part", ignoreCase = true) &&
                    !file.name.endsWith(".ytdl", ignoreCase = true) &&
                    !file.name.endsWith(".webp", ignoreCase = true) &&
                    !file.name.endsWith(".jpg", ignoreCase = true) &&
                    !file.name.endsWith(".png", ignoreCase = true) &&
                    file.length() > 0L
            }
            ?.maxByOrNull { it.length() }
    }

    private fun writeAuthCookiesIfAvailable(targetDir: File): File? {
        return try {
            val cookieStr = YouTubeAuthManager.getInstance(appContext).getCookies()
                ?.takeIf { it.isNotBlank() }
                ?: return null
            if (!targetDir.exists()) targetDir.mkdirs()
            val cookieFile = File(targetDir, "cookies.txt")
            val sb = StringBuilder("# Netscape HTTP Cookie File\n")
            val exp = (System.currentTimeMillis() / 1000L) + 31536000L
            for (pair in cookieStr.split(";")) {
                val trimmed = pair.trim()
                val eq = trimmed.indexOf('=')
                if (eq <= 0) continue
                val name = trimmed.substring(0, eq).trim()
                val value = trimmed.substring(eq + 1).trim()
                if (name.isNotEmpty()) {
                    sb.append(".youtube.com\tTRUE\t/\tTRUE\t$exp\t$name\t$value\n")
                    sb.append(".google.com\tTRUE\t/\tTRUE\t$exp\t$name\t$value\n")
                }
            }
            cookieFile.writeText(sb.toString())
            cookieFile
        } catch (_: Exception) {
            null
        }
    }
}
