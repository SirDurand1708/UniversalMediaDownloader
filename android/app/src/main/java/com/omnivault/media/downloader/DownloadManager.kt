package com.omnivault.media.downloader

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.omnivault.media.model.DownloadItem
import com.omnivault.media.model.DownloadStatus
import com.omnivault.media.model.MediaFormat
import com.omnivault.media.utils.AudioExtractor
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

    var onDownloadUpdatedListener: ((DownloadItem) -> Unit)? = null
    var onHistoryUpdatedListener: ((List<DownloadItem>) -> Unit)? = null

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
            item.status = DownloadStatus.DOWNLOADING
            notifyUpdate(item)

            // 2. Preparar archivo temporal y destino final
            val targetDir = StorageHelper.getOutputDirectory(item.format.isAudio)
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

            // 4. Procesamiento final (Extracción de audio sin pérdida si aplica)
            var finalOutputFile: File
            var finalMimeType: String

            if (item.format.isAudio) {
                if (info.directStreamUrl.contains(".mp3") || tempDownloadFile.name.endsWith(".mp3")) {
                    finalOutputFile = File(targetDir, "${cleanTitle}.mp3")
                    tempDownloadFile.copyTo(finalOutputFile, overwrite = true)
                    tempDownloadFile.delete()
                    finalMimeType = "audio/mpeg"
                } else {
                    // Flujo nativo AAC en contenedor MP4: extraer pista de audio pura en .m4a
                    item.speedText = "Extrayendo audio AAC..."
                    notifyUpdate(item)
                    val m4aFile = File(targetDir, "${cleanTitle}.m4a")
                    val extracted = AudioExtractor.extractAudio(tempDownloadFile, m4aFile)
                    tempDownloadFile.delete()

                    if (extracted && m4aFile.exists() && m4aFile.length() > 0) {
                        finalOutputFile = m4aFile
                        finalMimeType = "audio/mp4"
                    } else {
                        // Fallback
                        finalOutputFile = File(targetDir, "${cleanTitle}.${item.format.extension}")
                        tempDownloadFile.copyTo(finalOutputFile, overwrite = true)
                        finalMimeType = item.format.mimeType
                    }
                }
            } else {
                finalOutputFile = File(targetDir, "${cleanTitle}.${item.format.extension}")
                tempDownloadFile.copyTo(finalOutputFile, overwrite = true)
                tempDownloadFile.delete()
                finalMimeType = item.format.mimeType
            }

            // 5. Registro en el escáner del sistema Android (MediaStore)
            StorageHelper.scanMediaFile(context, finalOutputFile, finalMimeType)

            // 6. Finalización con éxito
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
        onDownloadUpdatedListener?.invoke(item)
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
