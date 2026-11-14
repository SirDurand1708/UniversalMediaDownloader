package com.omnivault.media.downloader

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.omnivault.media.model.DownloadItem
import com.omnivault.media.model.DownloadStatus
import com.omnivault.media.model.MediaFormat
import com.omnivault.media.utils.AudioExtractor
import com.omnivault.media.utils.CoverArtHelper
import com.omnivault.media.utils.LyricsHelper
import com.omnivault.media.utils.MediaMuxerHelper
import com.omnivault.media.utils.NotificationHelper
import com.omnivault.media.utils.StorageHelper
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DownloadManager private constructor(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("omnivault_storage", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val activeTasks = ConcurrentHashMap<String, DownloadItem>()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val historyList = mutableListOf<DownloadItem>()

    private val downloadListeners = java.util.concurrent.CopyOnWriteArraySet<(DownloadItem) -> Unit>()
    var onDownloadUpdatedListener: ((DownloadItem) -> Unit)? = null
    var onHistoryUpdatedListener: ((List<DownloadItem>) -> Unit)? = null

    fun addDownloadListener(listener: (DownloadItem) -> Unit) {
        downloadListeners.add(listener)
    }

    fun removeDownloadListener(listener: (DownloadItem) -> Unit) {
        downloadListeners.remove(listener)
    }

    init {
        loadHistory()
    }

    companion object {
        @Volatile
        private var INSTANCE: DownloadManager? = null

        fun getInstance(context: Context): DownloadManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DownloadManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    fun getActiveDownloads(): List<DownloadItem> = activeTasks.values.toList()
    fun getHistory(): List<DownloadItem> = historyList.toList()

    fun enqueueDownload(url: String, format: MediaFormat) {
        val cleanUrl = url.trim()
        val alreadyActive = activeTasks.values.any { 
            it.url == cleanUrl && it.format == format && it.status != DownloadStatus.ERROR && it.status != DownloadStatus.CANCELLED 
        }
        if (alreadyActive) {
            return
        }

        val item = DownloadItem(
            url = cleanUrl,
            format = format,
            status = DownloadStatus.FETCHING_METADATA
        )
        activeTasks[item.id] = item
        notifyUpdate(item)

        val job = scope.launch {
            runDownloadPipeline(item)
        }
        activeJobs[item.id] = job
    }

    fun cancelDownload(taskId: String) {
        try {
            UniversalDownloadEngine.getInstance(context).cancelDownload(taskId)
        } catch (_: Exception) {}
        try {
            com.yausername.youtubedl_android.YoutubeDL.getInstance().destroyProcessById(taskId)
        } catch (_: Exception) {}
        activeJobs[taskId]?.cancel()
        activeTasks.remove(taskId)?.let { item ->
            item.status = DownloadStatus.CANCELLED
            notifyUpdate(item)
        }
    }

    @Synchronized
    private fun ensureYoutubeDLReady() {
        try {
            com.yausername.youtubedl_android.YoutubeDL.getInstance().init(context)
            com.yausername.ffmpeg.FFmpeg.getInstance().init(context)
            com.yausername.aria2c.Aria2c.getInstance().init(context)
        } catch (_: Exception) {}
    }

    private fun writeTempCookiesFile(workDir: File): File? {
        return try {
            val auth = com.amurayada.music.data.auth.YouTubeAuthManager.getInstance(context)
            val cookieStr = auth.getCookies()?.takeIf { it.isNotBlank() } ?: return null
            val cookieFile = File(workDir, "cookies.txt")
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

    private suspend fun downloadWithYoutubeDL(item: DownloadItem, stagedFile: File): Boolean = withContext(Dispatchers.IO) {
        val workDir = File(context.cacheDir, "ytdl_${item.id}")
        try {
            ensureYoutubeDLReady()
            if (workDir.exists()) workDir.deleteRecursively()
            workDir.mkdirs()

            val request = com.yausername.youtubedl_android.YoutubeDLRequest(item.url)
            request.addOption("--no-playlist")
            request.addOption("--no-mtime")
            request.addOption("--concurrent-fragments", 4)
            request.addOption("--retries", 3)
            request.addOption("--fragment-retries", 3)
            request.addOption("--socket-timeout", 15)

            writeTempCookiesFile(workDir)?.let { cookieFile ->
                request.addOption("--cookies", cookieFile.absolutePath)
            }

            if (item.format.isAudio) {
                if (item.format == MediaFormat.MP3_320) {
                    request.addOption("-f", "bestaudio/best/18")
                    request.addOption("-x")
                    request.addOption("--audio-format", "mp3")
                    request.addOption("--audio-quality", "0")
                } else {
                    request.addOption("-f", "bestaudio[ext=m4a]/bestaudio/best/18")
                    request.addOption("-x")
                    request.addOption("--audio-format", "m4a")
                }
            } else {
                val maxH = if (item.format == MediaFormat.MP4_1080) 1080 else 720
                request.addOption(
                    "-f",
                    "bestvideo[height<=$maxH][ext=mp4]+bestaudio[ext=m4a]/best[height<=$maxH][ext=mp4]/18/best"
                )
                request.addOption("--merge-output-format", "mp4")
            }

            val outTemplate = File(workDir, "output.%(ext)s").absolutePath
            request.addOption("-o", outTemplate)

            var lastUpdate = 0L
            com.yausername.youtubedl_android.YoutubeDL.getInstance().execute(
                request,
                item.id
            ) { progress, etaInSeconds, line ->
                val now = System.currentTimeMillis()
                if (now - lastUpdate >= 250L) {
                    lastUpdate = now
                    if (progress > 0f && progress <= 100f) {
                        item.progress = (progress * 0.94f).toInt().coerceIn(1, 95)
                    }
                    val speedMatch = Regex("""(\d+(?:\.\d+)?\s*[KMGT]?i?B/s)""").find(line)
                    item.speedText = when {
                        speedMatch != null -> speedMatch.groupValues[1]
                        etaInSeconds > 0 -> "ETA ${etaInSeconds}s"
                        else -> "Descargando (yt-dlp + FFmpeg)…"
                    }
                    notifyUpdate(item)
                }
            }

            val producedFile = workDir.listFiles()
                ?.filter {
                    it.isFile &&
                        it.name != "cookies.txt" &&
                        !it.name.endsWith(".part") &&
                        !it.name.endsWith(".ytdl") &&
                        it.length() > 0L
                }
                ?.maxByOrNull { it.length() }

            if (producedFile != null && producedFile.exists() && producedFile.length() > 0L) {
                producedFile.copyTo(stagedFile, overwrite = true)
                return@withContext true
            }
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("DownloadManager", "youtubedl-android execution failed: ${e.message}")
            false
        } finally {
            try {
                if (workDir.exists()) workDir.deleteRecursively()
            } catch (_: Exception) {}
        }
    }

    private suspend fun runDownloadPipeline(item: DownloadItem) {
        try {
            // 0. Motor Principal Universal: UniversalDownloadEngine (youtubedl-android: yt-dlp + FFmpeg + Aria2c)
            try {
                val engine = UniversalDownloadEngine.getInstance(context)
                val result = engine.executeDownload(
                    url = item.url,
                    format = item.format,
                    processId = item.id
                ) { state ->
                    item.title = state.title
                    item.artist = state.artist
                    if (state.thumbnailUrl.isNotBlank()) {
                        item.thumbnailUrl = state.thumbnailUrl
                    }
                    item.status = state.status
                    item.progress = (state.progress * 100f).toInt().coerceIn(0, 100)
                    item.speedText = state.speedText
                    notifyUpdate(item)
                }

                item.title = result.metadata.title
                item.artist = result.metadata.artist
                item.thumbnailUrl = result.localThumbnailPath ?: result.metadata.thumbnailUrl
                item.progress = 100
                item.status = DownloadStatus.COMPLETED
                item.localFilePath = result.savedPath
                item.fileSizeBytes = result.fileSizeBytes
                item.fileSizeFormatted = StorageHelper.formatFileSize(result.fileSizeBytes)

                activeTasks.remove(item.id)
                addToHistory(item)
                notifyUpdate(item)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(
                    "DownloadManager",
                    "UniversalDownloadEngine primary execution failed (${e.message}), activating VISIONOS/InnerTube fallback..."
                )
            }

            // 1. Extraer Metadatos (Respaldo nativo VISIONOS 1.02 + visitorData)
            item.status = DownloadStatus.FETCHING_METADATA
            notifyUpdate(item)

            val info = try {
                MediaExtractor.extract(item.url, item.format)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("DownloadManager", "MediaExtractor failed (${e.message}), querying yt-dlp metadata...")
                ensureYoutubeDLReady()
                val ytdlInfo = try {
                    com.yausername.youtubedl_android.YoutubeDL.getInstance().getInfo(item.url)
                } catch (_: Exception) {
                    null
                }
                ExtractedMediaInfo(
                    title = ytdlInfo?.title?.takeIf { it.isNotBlank() } ?: "OmniVault Media",
                    directStreamUrl = "",
                    format = item.format,
                    durationSeconds = (ytdlInfo?.duration ?: 0).toLong(),
                    author = ytdlInfo?.uploader?.takeIf { it.isNotBlank() } ?: "YouTube",
                    thumbnailUrl = ytdlInfo?.thumbnail ?: ""
                )
            }
            item.title = com.omnivault.media.utils.FilenameStandardizer.standardizeTitle(info.title)
            item.thumbnailUrl = info.thumbnailUrl
            item.artist = info.author
            item.status = DownloadStatus.DOWNLOADING
            notifyUpdate(item)

            // 2. Preparar archivo temporal y destino final (honra carpeta personalizada o MicroSD)
            val targetDir = StorageHelper.getOutputDirectory(item.format.isAudio, context)
            val cleanTitle = StorageHelper.sanitizeFilename(info.title)

            val tempDownloadFile = File(context.cacheDir, "temp_stream_${item.id}.tmp")
            val tempAudioFile = File(context.cacheDir, "temp_audio_${item.id}.tmp")
            if (tempDownloadFile.exists()) tempDownloadFile.delete()
            if (tempAudioFile.exists()) tempAudioFile.delete()

            var hasSeparateAudio = !item.format.isAudio && !info.audioStreamUrl.isNullOrBlank()
            val mainStreamMaxProgress = if (hasSeparateAudio) 70 else 95

            val preStagedExt = if (item.format.isAudio) {
                if (item.format == MediaFormat.MP3_320) "mp3" else "m4a"
            } else {
                item.format.extension
            }
            val preStagedFile = File(context.cacheDir, "staged_${item.id}.${preStagedExt}")
            if (preStagedFile.exists()) preStagedFile.delete()
            var completedViaYoutubeDL = false

            // Descarga de flujos principales con soporte chunked + failover automático
            var primaryDownloadSuccess = false
            if (info.directStreamUrl.isNotBlank()) {
                try {
                    downloadStreamInternal(
                        item = item,
                        primaryUrl = info.directStreamUrl,
                        backupUrls = info.backupStreamUrls,
                        requestHeaders = info.requestHeaders,
                        targetFile = tempDownloadFile,
                        startPct = 1,
                        endPct = mainStreamMaxProgress,
                        isAudioStream = item.format.isAudio
                    )
                    if (hasSeparateAudio) {
                        item.speedText = "Descargando audio HQ…"
                        notifyUpdate(item)
                        downloadStreamInternal(
                            item = item,
                            primaryUrl = info.audioStreamUrl!!,
                            backupUrls = info.backupAudioUrls,
                            requestHeaders = info.requestHeaders,
                            targetFile = tempAudioFile,
                            startPct = 70,
                            endPct = 90,
                            isAudioStream = true
                        )
                    }
                    primaryDownloadSuccess = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("DownloadManager", "Primary download failed: ${e.message}. Launching elite fallback...")
                }
            }

            // Respaldo de Élite multi-etapa:
            // 1) Motor nativo youtubedl-android (yt-dlp + FFmpeg + QuickJS)
            // 2) VISIONOS 1.02 / Progresivo ANDROID (itag 18) / Proxy Piped filtrado
            // 3) Flujo MP4 progresivo directo (si el muxing DASH falló en alguna pista)
            if (!primaryDownloadSuccess) {
                item.speedText = "Descargando con motor yt-dlp + FFmpeg…"
                notifyUpdate(item)

                var fallbackSuccess = false
                if (downloadWithYoutubeDL(item, preStagedFile)) {
                    completedViaYoutubeDL = true
                    fallbackSuccess = true
                }

                if (!fallbackSuccess) {
                    item.speedText = "Conectando servidor de respaldo…"
                    notifyUpdate(item)
                    val fallbackInfo = MediaExtractor.extractEmergencyFallback(item.url, item.format)
                    if (fallbackInfo != null) {
                        try {
                            if (tempDownloadFile.exists()) tempDownloadFile.delete()
                            if (tempAudioFile.exists()) tempAudioFile.delete()

                            hasSeparateAudio = !item.format.isAudio && !fallbackInfo.audioStreamUrl.isNullOrBlank()
                            val fallbackMainMaxProgress = if (hasSeparateAudio) 70 else 95

                            downloadStreamInternal(
                                item = item,
                                primaryUrl = fallbackInfo.directStreamUrl,
                                backupUrls = fallbackInfo.backupStreamUrls,
                                requestHeaders = fallbackInfo.requestHeaders,
                                targetFile = tempDownloadFile,
                                startPct = 1,
                                endPct = fallbackMainMaxProgress,
                                isAudioStream = item.format.isAudio
                            )
                            if (hasSeparateAudio) {
                                item.speedText = "Descargando audio de respaldo…"
                                notifyUpdate(item)
                                downloadStreamInternal(
                                    item = item,
                                    primaryUrl = fallbackInfo.audioStreamUrl!!,
                                    backupUrls = fallbackInfo.backupAudioUrls,
                                    requestHeaders = fallbackInfo.requestHeaders,
                                    targetFile = tempAudioFile,
                                    startPct = 70,
                                    endPct = 90,
                                    isAudioStream = true
                                )
                            }
                            fallbackSuccess = true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            android.util.Log.w("DownloadManager", "Emergency fallback failed: ${e.message}")
                        }
                    }
                }

                if (!fallbackSuccess && info.progressiveFallbackUrls.isNotEmpty()) {
                    try {
                        if (tempDownloadFile.exists()) tempDownloadFile.delete()
                        if (tempAudioFile.exists()) tempAudioFile.delete()
                        hasSeparateAudio = false
                        item.speedText = "Descargando versión compatible MP4…"
                        notifyUpdate(item)
                        downloadStreamInternal(
                            item = item,
                            primaryUrl = info.progressiveFallbackUrls.first(),
                            backupUrls = info.progressiveFallbackUrls.drop(1),
                            requestHeaders = info.requestHeaders,
                            targetFile = tempDownloadFile,
                            startPct = 1,
                            endPct = 95,
                            isAudioStream = false
                        )
                        fallbackSuccess = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("DownloadManager", "Progressive fallback failed: ${e.message}")
                    }
                }

                if (!fallbackSuccess) {
                    throw Exception("No se pudo descargar el video debido a restricciones del servidor (HTTP 403). Por favor intenta de nuevo en unos momentos.")
                }
            }

            // 4. Procesamiento y empaquetado en staging
            val isMp3Stream = (completedViaYoutubeDL && item.format == MediaFormat.MP3_320) ||
                info.directStreamUrl.contains(".mp3", ignoreCase = true) ||
                tempDownloadFile.name.endsWith(".mp3", ignoreCase = true)
            val extension = if (item.format.isAudio) {
                if (isMp3Stream) "mp3" else "m4a"
            } else {
                item.format.extension
            }

            val stagedFile = File(context.cacheDir, "staged_${item.id}.${extension}")
            if (!completedViaYoutubeDL && stagedFile.exists()) stagedFile.delete()

            val finalMimeType: String
            if (completedViaYoutubeDL && preStagedFile.exists() && preStagedFile.length() > 0L) {
                if (preStagedFile.absolutePath != stagedFile.absolutePath) {
                    preStagedFile.copyTo(stagedFile, overwrite = true)
                    preStagedFile.delete()
                }
                finalMimeType = if (item.format.isAudio) {
                    if (extension == "mp3") "audio/mpeg" else "audio/mp4"
                } else {
                    "video/mp4"
                }
            } else if (hasSeparateAudio) {
                // Remuxing nativo DASH en Android para video 1080p Full HD
                item.status = DownloadStatus.PROCESSING
                item.speedText = "Remuxing 1080p FHD…"
                notifyUpdate(item)
                val muxed = MediaMuxerHelper.muxVideoAndAudio(tempDownloadFile, tempAudioFile, stagedFile)
                if (muxed && stagedFile.exists() && stagedFile.length() > 0L) {
                    tempDownloadFile.delete()
                    tempAudioFile.delete()
                } else {
                    tempDownloadFile.copyTo(stagedFile, overwrite = true)
                    tempDownloadFile.delete()
                    tempAudioFile.delete()
                }
                finalMimeType = "video/mp4"
            } else if (item.format.isAudio) {
                if (isMp3Stream) {
                    tempDownloadFile.copyTo(stagedFile, overwrite = true)
                    tempDownloadFile.delete()
                    finalMimeType = "audio/mpeg"
                } else {
                    item.status = DownloadStatus.PROCESSING
                    item.speedText = "Extrayendo audio…"
                    notifyUpdate(item)
                    val extracted = AudioExtractor.extractAudio(tempDownloadFile, stagedFile)
                    if (!extracted || !stagedFile.exists() || stagedFile.length() == 0L) {
                        tempDownloadFile.copyTo(stagedFile, overwrite = true)
                    }
                    tempDownloadFile.delete()
                    finalMimeType = "audio/mp4"
                }
            } else {
                tempDownloadFile.copyTo(stagedFile, overwrite = true)
                tempDownloadFile.delete()
                finalMimeType = item.format.mimeType
            }

            // 5. Post-procesamiento diferenciado: Audio vs Video
            item.status = DownloadStatus.PROCESSING
            item.speedText = "Procesando…"
            notifyUpdate(item)

            var coverBytes: ByteArray? = null

            if (item.format.isAudio) {
                // =========================================================================
                // SOLO PARA AUDIOS (Canciones MP3 / M4A):
                // Inyectar carátula, metadatos de artista/álbum y letras sincronizadas (.lrc)
                // =========================================================================
                val lyricsContent = LyricsHelper.fetchLyrics(info.title, info.author)
                coverBytes = CoverArtHelper.downloadCoverBytes(info.thumbnailUrl)

                if (coverBytes != null && coverBytes.isNotEmpty()) {
                    item.speedText = "Incrustando carátula y metadatos..."
                    notifyUpdate(item)

                    if (stagedFile.name.endsWith(".mp3", ignoreCase = true)) {
                        CoverArtHelper.embedCoverInMp3(stagedFile, coverBytes, info.title, info.author, lyricsContent)
                    } else {
                        CoverArtHelper.embedCoverInM4a(stagedFile, coverBytes, info.title, info.author, lyricsContent)
                    }

                    val cachedCover = CoverArtHelper.cacheCoverLocally(context, item.id, coverBytes)
                    if (cachedCover != null) {
                        item.thumbnailUrl = cachedCover.absolutePath
                    }
                }
            } else {
                // =========================================================================
                // PARA VIDEOS (MP4):
                // Conservar el archivo de video puro sin etiquetas de álbum de música
                // (evita que Lark Player u otros reproductores lo clasifiquen como canción/MV).
                // Solo guardamos la miniatura en caché interna para la interfaz de OmniVault.
                // =========================================================================
                coverBytes = CoverArtHelper.downloadCoverBytes(info.thumbnailUrl)
                if (coverBytes != null && coverBytes.isNotEmpty()) {
                    val cachedCover = CoverArtHelper.cacheCoverLocally(context, item.id, coverBytes)
                    if (cachedCover != null) {
                        item.thumbnailUrl = cachedCover.absolutePath
                    }
                }
            }

            // 6. Mover archivo a la carpeta pública correspondiente (Music o Movies) garantizando visibilidad en MediaStore
            val finalOutputFile = StorageHelper.saveMediaFile(
                context = context,
                sourceFile = stagedFile,
                displayName = "${cleanTitle}.${extension}",
                isAudio = item.format.isAudio,
                mimeType = finalMimeType
            )
            stagedFile.delete()

            // Guardar carátula complementaria .jpg y archivo de letras .lrc ÚNICAMENTE para audios.
            // Para videos NUNCA se guardan .jpg ni .lrc en la carpeta de películas,
            // ya que Lark Player los detecta como álbum de música.
            val effectiveTargetDir = finalOutputFile.parentFile ?: targetDir
            if (item.format.isAudio) {
                if (coverBytes != null && coverBytes.isNotEmpty()) {
                    try {
                        val companionJpg = File(effectiveTargetDir, "${cleanTitle}.jpg")
                        companionJpg.writeBytes(coverBytes)
                        StorageHelper.scanMediaFile(context, companionJpg, "image/jpeg")
                    } catch (_: Exception) {}
                }

                val lyricsContent = LyricsHelper.fetchLyrics(info.title, info.author)
                if (!lyricsContent.isNullOrBlank()) {
                    val lrcFile = LyricsHelper.saveLrcFile(effectiveTargetDir, cleanTitle, lyricsContent)
                    if (lrcFile != null) {
                        StorageHelper.scanMediaFile(context, lrcFile, "text/plain")
                    }
                }
            }

            // 7. Indexación inmediata en el escáner del sistema Android (MediaStore) y registro en Room (DownloadDatabase)
            StorageHelper.scanMediaFile(context, finalOutputFile, finalMimeType)
            try {
                val downloadDao = com.amurayada.music.data.database.DownloadDatabase.getDatabase(context).downloadDao()
                downloadDao.insertDownload(
                    com.amurayada.music.data.database.DownloadEntity(
                        songId = Math.abs(item.url.hashCode().toLong()),
                        title = item.title,
                        artist = item.artist?.takeIf { it.isNotBlank() } ?: "OmniVault",
                        album = item.artist?.takeIf { it.isNotBlank() } ?: "OmniVault",
                        duration = info.durationSeconds * 1000L,
                        youtubeUrl = item.url,
                        localPath = finalOutputFile.absolutePath,
                        thumbnailUrl = item.thumbnailUrl,
                        fileSize = finalOutputFile.length()
                    )
                )
            } catch (dbErr: Exception) {
                android.util.Log.w("DownloadManager", "Room insert warning: ${dbErr.message}")
            }

            // 8. Finalización con éxito
            item.progress = 100
            item.status = DownloadStatus.COMPLETED
            item.localFilePath = finalOutputFile.absolutePath
            item.fileSizeBytes = finalOutputFile.length()
            item.fileSizeFormatted = StorageHelper.formatFileSize(finalOutputFile.length())

            activeTasks.remove(item.id)
            addToHistory(item)
            notifyUpdate(item)

        } catch (e: CancellationException) {
            item.status = DownloadStatus.CANCELLED
            activeTasks.remove(item.id)
            notifyUpdate(item)
        } catch (e: Exception) {
            item.status = DownloadStatus.ERROR
            item.errorReason = e.localizedMessage ?: "Error de descarga"
            activeTasks.remove(item.id)
            notifyUpdate(item)
        }
    }

    private fun extractClenFromUrl(streamUrl: String): Long? {
        return Regex("""[?&]clen=(\d+)""").find(streamUrl)?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun extractItagFromUrl(streamUrl: String): Int? {
        return Regex("""[?&]itag=(\d+)""").find(streamUrl)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun buildUrlWithRange(rawUrl: String, start: Long, end: Long): String {
        val cleaned = rawUrl.replace(Regex("""([?&])range=[^&]*""")) { m ->
            if (m.groupValues[1] == "?") "?" else ""
        }.replace("?&", "?").trimEnd('&', '?')
        val sep = if (cleaned.contains("?")) "&" else "?"
        return "${cleaned}${sep}range=${start}-${end}"
    }

    private fun resolveStreamHeaders(
        streamUrl: String,
        sourceHeaders: Map<String, String>,
        isAudioStream: Boolean
    ): Map<String, String> {
        val lowerUrl = streamUrl.lowercase()
        val isYouTube = lowerUrl.contains("googlevideo.com") || lowerUrl.contains("youtube.com")
        val isTikTok = lowerUrl.contains("tiktok") || lowerUrl.contains("tikwm")
        val isVisionOs = isYouTube && lowerUrl.contains("c=visionos")
        val isIos = isYouTube && lowerUrl.contains("c=ios")
        val isAndroid = isYouTube && (lowerUrl.contains("c=android") || (!isVisionOs && !isIos && sourceHeaders["User-Agent"]?.contains("android", ignoreCase = true) == true))
        val isNativeMobileClient = isAndroid || isVisionOs || isIos

        val ua = when {
            isAndroid -> "com.google.android.youtube/20.10.38 (Linux; U; Android 14)"
            isVisionOs -> "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"
            isIos -> "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)"
            isYouTube -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
            isTikTok -> "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
            else -> sourceHeaders["User-Agent"] ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        }

        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = ua
        headers["Accept"] = if (isAudioStream) {
            "audio/webm,audio/ogg,audio/wav,audio/*;q=0.9,application/ogg;q=0.7,video/*;q=0.6,*/*;q=0.5"
        } else {
            "*/*"
        }
        headers["Accept-Language"] = "en-US,en;q=0.9,es;q=0.8"
        headers["Accept-Encoding"] = "identity"
        headers["Connection"] = "keep-alive"

        if (isYouTube && !isNativeMobileClient) {
            headers["Referer"] = "https://www.youtube.com/"
            headers["Origin"] = "https://www.youtube.com"
            headers["Sec-Fetch-Dest"] = if (isAudioStream) "audio" else "video"
            headers["Sec-Fetch-Mode"] = "no-cors"
            headers["Sec-Fetch-Site"] = "cross-site"
        } else if (isTikTok) {
            headers["Referer"] = "https://www.tikwm.com/"
        }

        for ((k, v) in sourceHeaders) {
            if (k.equals("Range", ignoreCase = true) || k.equals("Accept-Encoding", ignoreCase = true) || k.equals("User-Agent", ignoreCase = true)) {
                continue
            }
            if (isNativeMobileClient && (k.equals("Referer", ignoreCase = true) || k.equals("Origin", ignoreCase = true))) {
                continue
            }
            headers[k] = v
        }
        return headers
    }

    private suspend fun downloadYouTubeChunkedStream(
        item: DownloadItem,
        initialUrl: String,
        initialHeaders: Map<String, String>,
        targetFile: File,
        startPct: Int,
        endPct: Int,
        isAudioStream: Boolean,
        totalBytes: Long
    ) {
        val targetItag = extractItagFromUrl(initialUrl)
        val chunkSize = 8 * 1024 * 1024L
        var activeUrl = initialUrl
        var activeHeaders = initialHeaders
        var downloadedBytes = 0L
        var chunksSinceRefresh = 0
        var lastNotifyTime = System.currentTimeMillis()
        var lastNotifyBytes = 0L

        if (targetFile.exists()) targetFile.delete()

        BufferedOutputStream(FileOutputStream(targetFile, false), 65536).use { outStream ->
            while (downloadedBytes < totalBytes) {
                currentCoroutineContext().ensureActive()
                val start = downloadedBytes
                val end = minOf(start + chunkSize - 1, totalBytes - 1)
                val expectedChunkBytes = end - start + 1

                var chunkCompleted = false
                var lastChunkError: Exception? = null

                for (attempt in 0 until 3) {
                    currentCoroutineContext().ensureActive()
                    if (attempt > 0) {
                        delay(250L * attempt)
                        MediaExtractor.refreshYouTubeStreamUrl(
                            url = item.url,
                            requestedFormat = item.format,
                            isAudioStream = isAudioStream,
                            preferVisionOs = true,
                            targetItag = targetItag
                        )?.let { (refreshedUrl, refreshedHeaders) ->
                            val refreshedClen = extractClenFromUrl(refreshedUrl)
                            if (refreshedClen == null || refreshedClen == totalBytes) {
                                activeUrl = refreshedUrl
                                activeHeaders = refreshedHeaders
                                chunksSinceRefresh = 0
                            }
                        }
                    }

                    try {
                        val lowerActive = activeUrl.lowercase()
                        val useRangeParam = lowerActive.contains("c=android") ||
                            lowerActive.contains("c=ios") ||
                            lowerActive.contains("c=visionos")
                        val reqUrl = if (useRangeParam) buildUrlWithRange(activeUrl, start, end) else activeUrl
                        val headers = resolveStreamHeaders(activeUrl, activeHeaders, isAudioStream)

                        val reqBuilder = Request.Builder().url(reqUrl)
                        for ((k, v) in headers) {
                            reqBuilder.header(k, v)
                        }
                        if (!useRangeParam) {
                            reqBuilder.header("Range", "bytes=$start-$end")
                        }

                        client.newCall(reqBuilder.build()).execute().use { response ->
                            if (response.code == 403) {
                                throw Exception("Servidor respondió con código HTTP: 403")
                            }
                            if (!response.isSuccessful && response.code != 206) {
                                throw Exception("Servidor respondió con código HTTP: ${response.code}")
                            }
                            val contentType = response.header("Content-Type") ?: ""
                            if (contentType.contains("text/html", ignoreCase = true)) {
                                throw Exception("El enlace proporcionó una página web en lugar de un flujo multimedia.")
                            }

                            val body = response.body ?: throw Exception("Cuerpo de respuesta vacío")
                            val chunkBuffer = ByteArrayOutputStream(expectedChunkBytes.toInt())
                            BufferedInputStream(body.byteStream(), 65536).use { inStream ->
                                val buf = ByteArray(65536)
                                var bytesRead: Int
                                while (inStream.read(buf).also { bytesRead = it } != -1) {
                                    currentCoroutineContext().ensureActive()
                                    chunkBuffer.write(buf, 0, bytesRead)
                                    if (chunkBuffer.size().toLong() > expectedChunkBytes + 4096L) {
                                        throw Exception("El servidor ignoró el rango solicitado")
                                    }

                                    val now = System.currentTimeMillis()
                                    val timeDiff = now - lastNotifyTime
                                    if (timeDiff >= 300) {
                                        val currentTotal = downloadedBytes + chunkBuffer.size()
                                        val speedBytesPerSec = ((currentTotal - lastNotifyBytes).coerceAtLeast(0L) * 1000.0) / timeDiff
                                        item.speedText = String.format("%.2f MB/s", speedBytesPerSec / (1024 * 1024))
                                        val ratio = (currentTotal.toDouble() / totalBytes).coerceIn(0.0, 1.0)
                                        item.progress = (startPct + ratio * (endPct - startPct)).toInt().coerceIn(1, 99)
                                        lastNotifyTime = now
                                        lastNotifyBytes = currentTotal
                                        notifyUpdate(item)
                                    }
                                }
                            }

                            val chunkBytes = chunkBuffer.toByteArray()
                            if (chunkBytes.size.toLong() != expectedChunkBytes) {
                                throw Exception("Fragmento incompleto (${chunkBytes.size}/$expectedChunkBytes bytes)")
                            }
                            outStream.write(chunkBytes)
                            downloadedBytes += chunkBytes.size
                            chunksSinceRefresh++
                            chunkCompleted = true
                        }
                        if (chunkCompleted) break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("DownloadManager", "Chunk $start-$end attempt $attempt failed: ${e.message}")
                        lastChunkError = e
                    }
                }

                if (!chunkCompleted) {
                    throw lastChunkError ?: Exception("Error descargando fragmento de YouTube")
                }
            }
            outStream.flush()
        }

        item.progress = endPct.coerceIn(1, 99)
        notifyUpdate(item)
    }

    private suspend fun downloadStreamInternal(
        item: DownloadItem,
        primaryUrl: String,
        backupUrls: List<String>,
        requestHeaders: Map<String, String>,
        targetFile: File,
        startPct: Int,
        endPct: Int,
        isAudioStream: Boolean
    ) {
        val candidateUrls = (listOf(primaryUrl) + backupUrls).filter { it.isNotBlank() }.distinct().take(3)
        var lastError: Exception? = null

        for (streamUrl in candidateUrls) {
            currentCoroutineContext().ensureActive()
            if (targetFile.exists()) targetFile.delete()

            val lowerUrl = streamUrl.lowercase()
            val clen = if (lowerUrl.contains("googlevideo.com")) extractClenFromUrl(streamUrl) else null
            if (clen != null && clen > 0L) {
                try {
                    downloadYouTubeChunkedStream(
                        item = item,
                        initialUrl = streamUrl,
                        initialHeaders = requestHeaders,
                        targetFile = targetFile,
                        startPct = startPct,
                        endPct = endPct,
                        isAudioStream = isAudioStream,
                        totalBytes = clen
                    )
                    if (targetFile.exists() && targetFile.length() >= clen) {
                        return
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("DownloadManager", "Chunked download failed: ${e.message}")
                    lastError = e
                    if (targetFile.exists()) targetFile.delete()
                }
            }

            val headers = resolveStreamHeaders(streamUrl, requestHeaders, isAudioStream)
            val hasRangeInUrl = lowerUrl.contains("range=")

            // Método 1: OkHttp con encabezados precisos adaptados al tipo de flujo
            try {
                val reqBuilder = Request.Builder().url(streamUrl)
                for ((k, v) in headers) {
                    reqBuilder.header(k, v)
                }
                if (!hasRangeInUrl) {
                    reqBuilder.header("Range", "bytes=0-")
                }

                client.newCall(reqBuilder.build()).execute().use { response ->
                    if (response.code == 403) {
                        throw Exception("Servidor respondió con código HTTP: 403")
                    }
                    if (!response.isSuccessful && response.code != 206) {
                        throw Exception("Servidor respondió con código HTTP: ${response.code}")
                    }

                    val contentType = response.header("Content-Type") ?: ""
                    if (contentType.contains("text/html", ignoreCase = true)) {
                        throw Exception("El enlace proporcionó una página web en lugar de un flujo multimedia.")
                    }

                    val body = response.body ?: throw Exception("Cuerpo de respuesta vacío")
                    val totalBytes = body.contentLength()
                    BufferedInputStream(body.byteStream(), 65536).use { inputStream ->
                        BufferedOutputStream(FileOutputStream(targetFile), 65536).use { outputStream ->
                            val buffer = ByteArray(65536)
                            var bytesRead: Int
                            var totalRead = 0L
                            var lastTime = System.currentTimeMillis()
                            var lastBytes = 0L

                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                currentCoroutineContext().ensureActive()
                                outputStream.write(buffer, 0, bytesRead)
                                totalRead += bytesRead

                                val now = System.currentTimeMillis()
                                val timeDiff = now - lastTime
                                if (timeDiff >= 400) {
                                    val speedBytesPerSec = ((totalRead - lastBytes) * 1000.0) / timeDiff
                                    item.speedText = String.format("%.2f MB/s", speedBytesPerSec / (1024 * 1024))
                                    if (totalBytes > 0) {
                                        val ratio = (totalRead.toDouble() / totalBytes).coerceIn(0.0, 1.0)
                                        item.progress = (startPct + ratio * (endPct - startPct)).toInt().coerceIn(1, 99)
                                    }
                                    lastTime = now
                                    lastBytes = totalRead
                                    notifyUpdate(item)
                                }
                            }
                            outputStream.flush()
                        }
                    }
                }

                if (targetFile.exists() && targetFile.length() > 0L) {
                    return
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("DownloadManager", "OkHttp download attempt failed: ${e.message}")
                lastError = e
            }

            // Método 2 (Fallback): HttpURLConnection nativo con headers adaptados
            var connection: java.net.HttpURLConnection? = null
            try {
                if (targetFile.exists()) targetFile.delete()
                val url = java.net.URL(streamUrl)
                connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = true
                connection.useCaches = false
                connection.connectTimeout = 30000
                connection.readTimeout = 120000

                for ((k, v) in headers) {
                    connection.setRequestProperty(k, v)
                }
                if (!hasRangeInUrl) {
                    connection.setRequestProperty("Range", "bytes=0-")
                }

                val responseCode = connection.responseCode
                if (responseCode != java.net.HttpURLConnection.HTTP_OK && responseCode != 206) {
                    throw Exception("HttpURLConnection respondió con código HTTP: $responseCode")
                }

                val totalBytes = connection.contentLength.toLong()
                BufferedInputStream(connection.inputStream, 65536).use { inputStream ->
                    BufferedOutputStream(FileOutputStream(targetFile), 65536).use { outputStream ->
                        val buffer = ByteArray(65536)
                        var bytesRead: Int
                        var totalRead = 0L
                        var lastTime = System.currentTimeMillis()
                        var lastBytes = 0L

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            currentCoroutineContext().ensureActive()
                            outputStream.write(buffer, 0, bytesRead)
                            totalRead += bytesRead

                            val now = System.currentTimeMillis()
                            val timeDiff = now - lastTime
                            if (timeDiff >= 400) {
                                val speedBytesPerSec = ((totalRead - lastBytes) * 1000.0) / timeDiff
                                item.speedText = String.format("%.2f MB/s", speedBytesPerSec / (1024 * 1024))
                                if (totalBytes > 0) {
                                    val ratio = (totalRead.toDouble() / totalBytes).coerceIn(0.0, 1.0)
                                    item.progress = (startPct + ratio * (endPct - startPct)).toInt().coerceIn(1, 99)
                                }
                                lastTime = now
                                lastBytes = totalRead
                                notifyUpdate(item)
                            }
                        }
                        outputStream.flush()
                    }
                }

                if (targetFile.exists() && targetFile.length() > 0L) {
                    return
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("DownloadManager", "HttpURLConnection fallback failed: ${e.message}")
                lastError = e
            } finally {
                try { connection?.disconnect() } catch (_: Exception) {}
            }

            // Método 3: Conexión con encabezados limpios mínimos
            try {
                if (targetFile.exists()) targetFile.delete()
                val minimalReq = Request.Builder()
                    .url(streamUrl)
                    .header("User-Agent", headers["User-Agent"] ?: "Mozilla/5.0")
                    .apply {
                        if (!hasRangeInUrl) header("Range", "bytes=0-")
                    }
                    .build()
                client.newCall(minimalReq).execute().use { response ->
                    if (response.isSuccessful || response.code == 206) {
                        val body = response.body
                        if (body != null) {
                            BufferedInputStream(body.byteStream(), 65536).use { inStream ->
                                BufferedOutputStream(FileOutputStream(targetFile), 65536).use { outStream ->
                                    val buf = ByteArray(65536)
                                    var readBytes: Int
                                    while (inStream.read(buf).also { readBytes = it } != -1) {
                                        currentCoroutineContext().ensureActive()
                                        outStream.write(buf, 0, readBytes)
                                    }
                                    outStream.flush()
                                }
                            }
                            if (targetFile.exists() && targetFile.length() > 0L) {
                                return
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }

        throw lastError ?: Exception("No se pudo descargar el flujo multimedia después de probar todos los servidores de respaldo.")
    }

    private fun notifyUpdate(item: DownloadItem) {
        // Notificar a oyente principal
        onDownloadUpdatedListener?.invoke(item)

        // Notificar a todos los observadores registrados (MediaDownloadService, etc.)
        for (listener in downloadListeners) {
            try {
                listener.invoke(item)
            } catch (_: Exception) {}
        }
    }

    private fun addToHistory(item: DownloadItem) {
        synchronized(historyList) {
            historyList.add(0, item)
        }
        saveHistory()
        onHistoryUpdatedListener?.invoke(historyList.toList())
    }

    fun removeHistoryItem(item: DownloadItem) {
        synchronized(historyList) {
            historyList.removeAll { it.id == item.id }
        }
        // Eliminar archivo si existe
        item.localFilePath?.let { File(it).delete() }
        saveHistory()
        onHistoryUpdatedListener?.invoke(historyList.toList())
    }

    fun removeHistoryByPath(path: String) {
        if (path.isBlank()) return
        val targetName = File(path).name
        var removed = false
        synchronized(historyList) {
            removed = historyList.removeAll { item ->
                val itemPath = item.localFilePath ?: return@removeAll false
                itemPath.equals(path, ignoreCase = true) ||
                    (targetName.isNotBlank() && File(itemPath).name.equals(targetName, ignoreCase = true))
            }
        }
        try {
            val file = File(path)
            if (file.exists()) file.delete()
        } catch (_: Exception) {}
        if (removed) {
            saveHistory()
            onHistoryUpdatedListener?.invoke(historyList.toList())
        }
    }

    fun clearAllHistory() {
        synchronized(historyList) {
            historyList.clear()
        }
        saveHistory()
        onHistoryUpdatedListener?.invoke(historyList.toList())
    }

    suspend fun syncWithDeviceMedia(context: Context): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val audios = com.omnivault.media.data.LocalMediaScanner.scanDeviceAudio(context)
        val videos = com.omnivault.media.data.LocalMediaScanner.scanDeviceVideo(context)

        val existingPaths = synchronized(historyList) {
            historyList.mapNotNull { it.localFilePath }.toSet()
        }
        val newAudios = audios.filter { it.localFilePath !in existingPaths }
        val newVideos = videos.filter { it.localFilePath !in existingPaths }

        if (newAudios.isNotEmpty() || newVideos.isNotEmpty()) {
            synchronized(historyList) {
                historyList.addAll(newAudios)
                historyList.addAll(newVideos)
            }
            withContext(Dispatchers.Main) {
                onHistoryUpdatedListener?.invoke(historyList.toList())
            }
        }
        Pair(audios.size, videos.size)
    }

    fun recordPlay(item: DownloadItem) {
        val target = historyList.firstOrNull { it.id == item.id || it.localFilePath == item.localFilePath }
        if (target != null) {
            target.playCount++
            saveHistory()
        }
    }

    fun standardizeAllLibrary(): Int {
        var count = 0
        for (item in historyList) {
            val renamed = com.omnivault.media.utils.FilenameStandardizer.standardizeItemFile(item)
            val cleanTitle = com.omnivault.media.utils.FilenameStandardizer.standardizeTitle(item.title)
            if (renamed || cleanTitle != item.title) {
                item.title = cleanTitle
                count++
            }
        }
        if (count > 0) {
            saveHistory()
            onHistoryUpdatedListener?.invoke(historyList)
        }
        return count
    }

    private fun saveHistory() {
        // Guardar solo las descargas realizadas desde la app para mantener SharedPreferences ultraligero (< 50 KB)
        val downloadsOnly = historyList.filter { !it.id.startsWith("local_") }
        val json = gson.toJson(downloadsOnly)
        prefs.edit().putString("download_history", json).apply()
    }

    private fun loadHistory() {
        val json = prefs.getString("download_history", null) ?: return
        try {
            val type = object : TypeToken<List<DownloadItem>>() {}.type
            val saved: List<DownloadItem> = gson.fromJson(json, type)
            historyList.clear()
            historyList.addAll(saved)
        } catch (_: Exception) {}
    }
}
