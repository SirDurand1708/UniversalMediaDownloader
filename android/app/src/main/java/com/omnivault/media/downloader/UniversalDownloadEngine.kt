package com.omnivault.media.downloader

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import kotlinx.coroutines.withContext
import java.io.File

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
    private val downloadDao = DownloadDatabase.getDatabase(appContext).downloadDao()

    private val _downloadsState = MutableStateFlow<Map<String, UniversalDownloadState>>(emptyMap())
    val downloadsState: StateFlow<Map<String, UniversalDownloadState>> = _downloadsState.asStateFlow()

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
        preferredFormat: MediaFormat = MediaFormat.M4A
    ): UniversalMediaMetadata = withContext(Dispatchers.IO) {
        ensureInitialized()
        val cleanUrl = UrlUtils.extractUrlFromText(url)?.trim() ?: url.trim()
        val tempCookieDir = File(appContext.cacheDir, "ytdl_info_${System.currentTimeMillis()}")

        try {
            val infoRequest = YoutubeDLRequest(cleanUrl).apply {
                addOption("--no-playlist")
                addOption("--socket-timeout", 15)
                writeAuthCookiesIfAvailable(tempCookieDir)?.let { cookieFile ->
                    addOption("--cookies", cookieFile.absolutePath)
                }
            }

            val videoInfo = YoutubeDL.getInstance().getInfo(infoRequest)
            val rawTitle = videoInfo.title?.takeIf { it.isNotBlank() } ?: "OmniVault Media"
            val standardizedTitle = FilenameStandardizer.standardizeTitle(rawTitle)
            val uploader = videoInfo.uploader?.takeIf { it.isNotBlank() }
                ?: videoInfo.artist?.takeIf { it.isNotBlank() }
                ?: "OmniVault"
            val album = videoInfo.album?.takeIf { it.isNotBlank() } ?: uploader
            val durationMs = (videoInfo.duration.toLong().coerceAtLeast(0L)) * 1000L
            val thumbnail = videoInfo.thumbnail?.takeIf { it.isNotBlank() } ?: ""

            return@withContext UniversalMediaMetadata(
                title = standardizedTitle,
                artist = uploader,
                album = album,
                durationMs = durationMs,
                thumbnailUrl = thumbnail,
                sourceUrl = cleanUrl
            )
        } catch (e: Exception) {
            android.util.Log.w(
                "UniversalDownloadEngine",
                "YoutubeDL.getInfo fallback to MediaExtractor for $cleanUrl: ${e.message}"
            )
            val fallback = MediaExtractor.extract(cleanUrl, preferredFormat)
            return@withContext UniversalMediaMetadata(
                title = FilenameStandardizer.standardizeTitle(fallback.title),
                artist = fallback.author.ifBlank { "OmniVault" },
                album = fallback.author.ifBlank { "OmniVault" },
                durationMs = fallback.durationSeconds.coerceAtLeast(0L) * 1000L,
                thumbnailUrl = fallback.thumbnailUrl,
                sourceUrl = cleanUrl
            )
        } finally {
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
            status = DownloadStatus.FETCHING_METADATA,
            speedText = "Obteniendo metadatos…"
        )

        fun publishState(state: UniversalDownloadState) {
            _downloadsState.update { it + (processId to state) }
            onStateChanged?.invoke(state)
        }

        try {
            if (tempCacheDir.exists()) tempCacheDir.deleteRecursively()
            tempCacheDir.mkdirs()

            // =========================================================================
            // PASO A: Extracción de Info con YoutubeDL.getInstance().getInfo(url)
            // =========================================================================
            publishState(currentState)

            val metadata = extractMetadata(cleanUrl, format)
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
            val safeBaseName = StorageHelper.sanitizeFilename(metadata.title).ifBlank { "media_$processId" }
            val outTemplate = File(tempCacheDir, "$safeBaseName.%(ext)s").absolutePath

            val request = YoutubeDLRequest(cleanUrl).apply {
                addOption("--no-playlist")
                addOption("--no-mtime")
                addOption("--concurrent-fragments", 4)
                addOption("--retries", 3)
                addOption("--fragment-retries", 3)
                addOption("--socket-timeout", 20)
                addOption("-P", tempCacheDir.absolutePath)
                addOption("-o", outTemplate)

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
                    MediaFormat.MP4_1080 -> {
                        addOption(
                            "-f",
                            "bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/best[height<=1080][ext=mp4]/best[ext=mp4]/best"
                        )
                        addOption("--merge-output-format", "mp4")
                        addOption("--add-metadata")
                    }
                    MediaFormat.MP4_720 -> {
                        addOption(
                            "-f",
                            "bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/best[height<=720][ext=mp4]/best[ext=mp4]/best"
                        )
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
                MediaFormat.MP4_1080, MediaFormat.MP4_720 -> "mp4"
            }
            val finalMimeType = when (finalExt) {
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                else -> "video/mp4"
            }
            val displayName = "$safeBaseName.$finalExt"

            // Si es audio, asegurar carátula local y archivo de letras sincronizadas (.lrc)
            var localCoverPath: String? = null
            val coverBytes = CoverArtHelper.downloadCoverBytes(metadata.thumbnailUrl)
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
            val entitySongId = preloadedSongId ?: Math.abs(cleanUrl.hashCode().toLong())
            val entity = DownloadEntity(
                songId = entitySongId,
                title = metadata.title,
                artist = metadata.artist,
                album = metadata.album,
                duration = metadata.durationMs,
                youtubeUrl = cleanUrl,
                localPath = exported.first,
                thumbnailUrl = localCoverPath ?: metadata.thumbnailUrl,
                fileSize = exported.third
            )
            downloadDao.insertDownload(entity)

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
            cancelDownload(processId)
            throw e
        } catch (e: Exception) {
            val failedState = currentState.copy(
                status = DownloadStatus.ERROR,
                speedText = "Error",
                errorReason = e.localizedMessage ?: "Error en descarga con yt-dlp"
            )
            publishState(failedState)
            throw e
        } finally {
            try {
                if (tempCacheDir.exists()) tempCacheDir.deleteRecursively()
            } catch (_: Exception) {}
        }
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
                        appContext.contentResolver.openOutputStream(targetDoc.uri)?.use { out ->
                            sourceFile.inputStream().use { input ->
                                input.copyTo(out)
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
                    appContext.contentResolver.openOutputStream(insertedUri)?.use { out ->
                        sourceFile.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    }
                    val completeValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    appContext.contentResolver.update(insertedUri, completeValues, null, null)

                    return Triple(insertedUri.toString(), null, fileLength)
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
