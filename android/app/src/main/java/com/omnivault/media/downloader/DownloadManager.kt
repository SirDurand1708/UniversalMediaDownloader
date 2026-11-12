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
        activeJobs[taskId]?.cancel()
        activeTasks.remove(taskId)?.let { item ->
            item.status = DownloadStatus.CANCELLED
            notifyUpdate(item)
        }
    }

    private suspend fun runDownloadPipeline(item: DownloadItem) {
        try {
            // 1. Extraer Metadatos
            item.status = DownloadStatus.FETCHING_METADATA
            notifyUpdate(item)

            val info = MediaExtractor.extract(item.url, item.format)
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

            val hasSeparateAudio = !info.audioStreamUrl.isNullOrBlank()
            val mainStreamMaxProgress = if (hasSeparateAudio) 70 else 100

            // Helper modular con múltiples capas de respaldo (OkHttp + HttpURLConnection + streams alternativos)
            fun downloadStreamInternal(
                primaryUrl: String,
                backupUrls: List<String>,
                targetFile: File,
                startPct: Int,
                endPct: Int,
                isAudioStream: Boolean
            ) {
                val candidateUrls = (listOf(primaryUrl) + backupUrls).filter { it.isNotBlank() }.distinct()
                var lastError: Exception? = null

                for (streamUrl in candidateUrls) {
                    if (targetFile.exists()) targetFile.delete()

                    // Método 1: OkHttp optimizado con Range: bytes=0- y headers de streaming
                    try {
                        val reqBuilder = Request.Builder()
                            .url(streamUrl)

                        val lowerUrl = streamUrl.lowercase()
                        val isYouTube = lowerUrl.contains("googlevideo.com") || lowerUrl.contains("youtube.com")
                        val isTikTok = lowerUrl.contains("tiktok") || lowerUrl.contains("tikwm")

                        // User-Agent y encabezados de streaming
                        val ua = if (isYouTube) {
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                        } else {
                            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                        }
                        reqBuilder.header("User-Agent", ua)
                        reqBuilder.header("Accept", if (isAudioStream) "audio/webm,audio/ogg,audio/wav,audio/*;q=0.9,application/ogg;q=0.7,video/*;q=0.6,*/*;q=0.5" else "*/*")
                        reqBuilder.header("Accept-Language", "en-US,en;q=0.9,es;q=0.8")
                        reqBuilder.header("Accept-Encoding", "identity")
                        reqBuilder.header("Connection", "keep-alive")
                        reqBuilder.header("Range", "bytes=0-") // CRÍTICO: Evita error HTTP 403 en Google Video CDN

                        if (isYouTube) {
                            reqBuilder.header("Referer", "https://www.youtube.com/")
                            reqBuilder.header("Origin", "https://www.youtube.com")
                            reqBuilder.header("Sec-Fetch-Dest", if (isAudioStream) "audio" else "video")
                            reqBuilder.header("Sec-Fetch-Mode", "no-cors")
                            reqBuilder.header("Sec-Fetch-Site", "cross-site")
                        } else if (isTikTok) {
                            reqBuilder.header("Referer", "https://www.tikwm.com/")
                        }

                        for ((k, v) in info.requestHeaders) {
                            if (!k.equals("Range", ignoreCase = true) && !k.equals("Accept-Encoding", ignoreCase = true)) {
                                reqBuilder.header(k, v)
                            }
                        }

                        val request = reqBuilder.build()
                        client.newCall(request).execute().use { response ->
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
                            val inputStream = BufferedInputStream(body.byteStream(), 65536)
                            val outputStream = BufferedOutputStream(FileOutputStream(targetFile), 65536)

                            val buffer = ByteArray(65536)
                            var bytesRead: Int
                            var totalRead = 0L
                            var lastTime = System.currentTimeMillis()
                            var lastBytes = 0L

                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
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
                            outputStream.close()
                            inputStream.close()
                        }

                        if (targetFile.exists() && targetFile.length() > 0L) {
                            return // Descarga con OkHttp exitosa
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("DownloadManager", "OkHttp download attempt failed: ${e.message}")
                        lastError = e
                    }

                    // Método 2 (Fallback): HttpURLConnection con soporte de Range y headers de navegador nativo
                    try {
                        if (targetFile.exists()) targetFile.delete()
                        val url = java.net.URL(streamUrl)
                        val connection = url.openConnection() as java.net.HttpURLConnection
                        connection.requestMethod = "GET"
                        connection.instanceFollowRedirects = true
                        connection.useCaches = false
                        connection.connectTimeout = 30000
                        connection.readTimeout = 120000

                        val lowerUrl = streamUrl.lowercase()
                        val isYouTube = lowerUrl.contains("googlevideo.com") || lowerUrl.contains("youtube.com")
                        val isTikTok = lowerUrl.contains("tiktok") || lowerUrl.contains("tikwm")

                        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        connection.setRequestProperty("Accept", if (isAudioStream) "audio/webm,audio/ogg,audio/wav,audio/*;q=0.9,application/ogg;q=0.7,video/*;q=0.6,*/*;q=0.5" else "*/*")
                        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9,es;q=0.8")
                        connection.setRequestProperty("Accept-Encoding", "identity")
                        connection.setRequestProperty("Connection", "keep-alive")
                        connection.setRequestProperty("Range", "bytes=0-")

                        if (isYouTube) {
                            connection.setRequestProperty("Referer", "https://www.youtube.com/")
                            connection.setRequestProperty("Origin", "https://www.youtube.com")
                            connection.setRequestProperty("Sec-Fetch-Dest", if (isAudioStream) "audio" else "video")
                            connection.setRequestProperty("Sec-Fetch-Mode", "no-cors")
                            connection.setRequestProperty("Sec-Fetch-Site", "cross-site")
                        } else if (isTikTok) {
                            connection.setRequestProperty("Referer", "https://www.tikwm.com/")
                        }

                        for ((k, v) in info.requestHeaders) {
                            if (!k.equals("Range", ignoreCase = true) && !k.equals("Accept-Encoding", ignoreCase = true)) {
                                connection.setRequestProperty(k, v)
                            }
                        }

                        val responseCode = connection.responseCode
                        if (responseCode != java.net.HttpURLConnection.HTTP_OK && responseCode != 206) {
                            throw Exception("HttpURLConnection respondió con código HTTP: $responseCode")
                        }

                        val totalBytes = connection.contentLength.toLong()
                        val inputStream = BufferedInputStream(connection.inputStream, 65536)
                        val outputStream = BufferedOutputStream(FileOutputStream(targetFile), 65536)

                        val buffer = ByteArray(65536)
                        var bytesRead: Int
                        var totalRead = 0L
                        var lastTime = System.currentTimeMillis()
                        var lastBytes = 0L

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
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
                        outputStream.close()
                        inputStream.close()
                        connection.disconnect()

                        if (targetFile.exists() && targetFile.length() > 0L) {
                            return // Descarga con HttpURLConnection exitosa
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("DownloadManager", "HttpURLConnection fallback failed: ${e.message}")
                        lastError = e
                    }
                }

                throw lastError ?: Exception("No se pudo descargar el flujo multimedia después de probar todos los servidores de respaldo.")
            }

            // Descarga de flujo principal (video o audio)
            downloadStreamInternal(info.directStreamUrl, info.backupStreamUrls, tempDownloadFile, 1, mainStreamMaxProgress, item.format.isAudio)

            // Descarga de pista complementaria de audio DASH si es video FHD 1080p
            if (hasSeparateAudio) {
                item.speedText = "Descargando audio HQ…"
                notifyUpdate(item)
                downloadStreamInternal(info.audioStreamUrl!!, info.backupAudioUrls, tempAudioFile, 70, 90, true)
            }

            // 4. Procesamiento y empaquetado en staging
            val isMp3Stream = info.directStreamUrl.contains(".mp3", ignoreCase = true) || tempDownloadFile.name.endsWith(".mp3", ignoreCase = true)
            val extension = if (item.format.isAudio) {
                if (isMp3Stream) "mp3" else "m4a"
            } else {
                item.format.extension
            }

            val stagedFile = File(context.cacheDir, "staged_${item.id}.${extension}")
            if (stagedFile.exists()) stagedFile.delete()

            val finalMimeType: String
            if (hasSeparateAudio) {
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
                    tempDownloadFile.delete()

                    if (!extracted || !stagedFile.exists() || stagedFile.length() == 0L) {
                        tempDownloadFile.copyTo(stagedFile, overwrite = true)
                    }
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

            // 6. Mover archivo a la carpeta pública correspondiente (Music o Movies)
            val finalOutputFile = File(targetDir, "${cleanTitle}.${extension}")
            stagedFile.copyTo(finalOutputFile, overwrite = true)
            stagedFile.delete()

            // Guardar carátula complementaria .jpg y archivo de letras .lrc ÚNICAMENTE para audios.
            // Para videos NUNCA se guardan .jpg ni .lrc en la carpeta de películas,
            // ya que Lark Player los detecta como álbum de música.
            if (item.format.isAudio) {
                if (coverBytes != null && coverBytes.isNotEmpty()) {
                    try {
                        val companionJpg = File(targetDir, "${cleanTitle}.jpg")
                        companionJpg.writeBytes(coverBytes)
                        StorageHelper.scanMediaFile(context, companionJpg, "image/jpeg")
                    } catch (_: Exception) {}
                }

                val lyricsContent = LyricsHelper.fetchLyrics(info.title, info.author)
                if (!lyricsContent.isNullOrBlank()) {
                    val lrcFile = LyricsHelper.saveLrcFile(targetDir, cleanTitle, lyricsContent)
                    if (lrcFile != null) {
                        StorageHelper.scanMediaFile(context, lrcFile, "text/plain")
                    }
                }
            }

            // 7. Indexación inmediata en el escáner del sistema Android (MediaStore)
            StorageHelper.scanMediaFile(context, finalOutputFile, finalMimeType)

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
        historyList.add(0, item)
        saveHistory()
        onHistoryUpdatedListener?.invoke(historyList)
    }

    fun removeHistoryItem(item: DownloadItem) {
        historyList.removeAll { it.id == item.id }
        // Eliminar archivo si existe
        item.localFilePath?.let { File(it).delete() }
        saveHistory()
        onHistoryUpdatedListener?.invoke(historyList)
    }

    fun clearAllHistory() {
        historyList.clear()
        saveHistory()
        onHistoryUpdatedListener?.invoke(historyList)
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
