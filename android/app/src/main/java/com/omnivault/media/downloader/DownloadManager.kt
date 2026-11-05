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
import com.omnivault.media.utils.NotificationHelper
import com.omnivault.media.utils.StorageHelper
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
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
        val item = DownloadItem(
            url = url,
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
            item.title = info.title
            item.thumbnailUrl = info.thumbnailUrl
            item.artist = info.author
            item.status = DownloadStatus.DOWNLOADING
            notifyUpdate(item)

            // 2. Preparar archivo temporal y destino final (honra carpeta personalizada o MicroSD)
            val targetDir = StorageHelper.getOutputDirectory(item.format.isAudio, context)
            val cleanTitle = StorageHelper.sanitizeFilename(info.title)

            val tempDownloadFile = File(context.cacheDir, "temp_stream_${item.id}.tmp")
            if (tempDownloadFile.exists()) tempDownloadFile.delete()

            // 3. Descargar flujo mediante OkHttp con seguimiento de progreso
            val reqBuilder = Request.Builder()
                .url(info.directStreamUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")

            for ((k, v) in info.requestHeaders) {
                reqBuilder.header(k, v)
            }
            val request = reqBuilder.build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Servidor respondió con código HTTP: ${response.code}")
                }

                val contentType = response.header("Content-Type") ?: ""
                if (contentType.contains("text/html", ignoreCase = true)) {
                    throw Exception("El enlace proporcionó una página web en lugar de un flujo multimedia.")
                }

                val body = response.body ?: throw Exception("Cuerpo de respuesta vacío")
                val totalBytes = body.contentLength()
                val inputStream = body.byteStream()
                val outputStream = FileOutputStream(tempDownloadFile)

                val buffer = ByteArray(16384)
                var bytesRead: Int
                var totalRead = 0L
                var lastTime = System.currentTimeMillis()
                var lastBytes = 0L

                // Validar el primer bloque
                val initialRead = inputStream.read(buffer)
                if (initialRead <= 0) {
                    throw Exception("El flujo descargado está vacío.")
                }
                val previewStr = String(buffer, 0, minOf(initialRead, 80), Charsets.UTF_8).lowercase()
                if (previewStr.contains("<!doctype") || previewStr.contains("<html")) {
                    throw Exception("El enlace retornó HTML en lugar de un archivo multimedia válido.")
                }
                outputStream.write(buffer, 0, initialRead)
                totalRead += initialRead

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalRead += bytesRead

                    val now = System.currentTimeMillis()
                    val timeDiff = now - lastTime
                    if (timeDiff >= 400) {
                        val speedBytesPerSec = ((totalRead - lastBytes) * 1000.0) / timeDiff
                        item.speedText = String.format("%.2f MB/s", speedBytesPerSec / (1024 * 1024))
                        if (totalBytes > 0) {
                            item.progress = ((totalRead * 100) / totalBytes).toInt().coerceIn(1, 99)
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

            // 4. Procesamiento y etiquetado en staging (caché interna antes de publicar)
            val isMp3Stream = info.directStreamUrl.contains(".mp3", ignoreCase = true) || tempDownloadFile.name.endsWith(".mp3", ignoreCase = true)
            val extension = if (item.format.isAudio) {
                if (isMp3Stream) "mp3" else "m4a"
            } else {
                item.format.extension
            }

            val stagedFile = File(context.cacheDir, "staged_${item.id}.${extension}")
            if (stagedFile.exists()) stagedFile.delete()

            val finalMimeType: String
            if (item.format.isAudio) {
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

    private fun saveHistory() {
        val json = gson.toJson(historyList)
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
