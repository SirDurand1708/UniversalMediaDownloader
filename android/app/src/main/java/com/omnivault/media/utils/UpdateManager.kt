package com.omnivault.media.utils

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val version: String,
    val versionCode: Int,
    val releaseDate: String,
    val title: String,
    val changelog: String,
    val apkUrl: String
)

object UpdateManager {
    private const val TAG = "UpdateManager"
    const val CURRENT_VERSION_NAME = "2.5.9"
    const val CURRENT_VERSION_CODE = 259
    private const val UPDATE_URL = "https://raw.githubusercontent.com/SirDurand1708/UniversalMediaDownloader/main/version.json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Consulta en segundo plano la existencia de una nueva versión oficial de OmniVault.
     */
    fun checkForUpdates(
        context: Context,
        silent: Boolean = true,
        onUpdateFound: ((UpdateInfo) -> Unit)? = null,
        onNoUpdate: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder()
                    .url(UPDATE_URL)
                    .header("User-Agent", "OmniVault-Android-Updater")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        if (response.code == 404) {
                            throw Exception("El repositorio está configurado como Privado 🔒 en GitHub. Para activar las actualizaciones y descargas públicas, cámbialo a Público en Settings de tu repositorio.")
                        }
                        throw Exception("Servidor respondió con código HTTP ${response.code}")
                    }

                    val body = response.body?.string() ?: throw Exception("Respuesta vacía")
                    val root = JsonParser.parseString(body).asJsonObject
                    val androidObj = root.getAsJsonObject("android") ?: throw Exception("Formato JSON no contiene nodo android")

                    val remoteVersion = androidObj.get("version")?.asString ?: "2.0.0"
                    val remoteVersionCode = androidObj.get("version_code")?.asInt ?: 200
                    val apkUrl = androidObj.get("apk_url")?.asString ?: ""
                    val title = if (androidObj.has("title")) androidObj.get("title").asString else (root.get("title")?.asString ?: "OmniVault Update")
                    val releaseDate = if (androidObj.has("release_date")) androidObj.get("release_date").asString else (root.get("release_date")?.asString ?: "")
                    val changelog = if (androidObj.has("changelog")) androidObj.get("changelog").asString else (root.get("changelog")?.asString ?: "Mejoras de rendimiento y corrección de errores.")

                    val updateInfo = UpdateInfo(
                        version = remoteVersion,
                        versionCode = remoteVersionCode,
                        releaseDate = releaseDate,
                        title = title,
                        changelog = changelog,
                        apkUrl = apkUrl
                    )

                    // Verificación estricta: sólo considerar actualización si es superior Y existe URL válida de APK
                    val hasNewerVersion = remoteVersionCode > CURRENT_VERSION_CODE || isSemverNewer(remoteVersion, CURRENT_VERSION_NAME)
                    val hasValidApkUrl = apkUrl.isNotBlank() && apkUrl.endsWith(".apk", ignoreCase = true)
                    val isNewer = hasNewerVersion && hasValidApkUrl

                    withContext(Dispatchers.Main) {
                        if (isNewer) {
                            Log.i(TAG, "Nueva versión oficial detectada para Android: $remoteVersion (Código: $remoteVersionCode)")
                            NotificationHelper.showUpdateNotification(context, remoteVersion, apkUrl, changelog)
                            onUpdateFound?.invoke(updateInfo)
                        } else {
                            if (hasNewerVersion && !hasValidApkUrl) {
                                Log.i(TAG, "La versión remota ($remoteVersion) no cuenta con paquete APK para Android. Se omite notificación.")
                            } else {
                                Log.i(TAG, "La aplicación Android está actualizada ($CURRENT_VERSION_NAME)")
                            }
                            onNoUpdate?.invoke()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error al comprobar actualizaciones: ${e.message}")
                withContext(Dispatchers.Main) {
                    if (!silent) {
                        onError?.invoke(e.message ?: "Error desconocido")
                    }
                }
            }
        }
    }

    /**
     * Descarga el APK oficial en segundo plano e invoca el instalador nativo del sistema operativo.
     */
    fun downloadAndInstallUpdate(
        context: Context,
        apkUrl: String,
        version: String,
        onProgress: ((Int, String) -> Unit)? = null,
        onComplete: ((File) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val updateDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates")
                if (!updateDir.exists()) updateDir.mkdirs()

                val apkFile = File(updateDir, "OmniVault-v$version.apk")
                if (apkFile.exists()) apkFile.delete()

                val request = Request.Builder()
                    .url(apkUrl)
                    .header("User-Agent", "OmniVault-Android-Downloader")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("Fallo en descarga: HTTP ${response.code}")
                    }

                    val body = response.body ?: throw Exception("Cuerpo de respuesta vacío")
                    val totalBytes = body.contentLength()
                    val inputStream = body.byteStream()
                    val outputStream = FileOutputStream(apkFile)

                    val buffer = ByteArray(16384)
                    var bytesRead: Int
                    var totalRead = 0L
                    var lastTime = System.currentTimeMillis()
                    var lastBytes = 0L

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        val now = System.currentTimeMillis()
                        val diff = now - lastTime
                        if (diff >= 500) {
                            val speed = ((totalRead - lastBytes) * 1000.0) / diff
                            val speedText = String.format("%.1f MB/s", speed / (1024 * 1024))
                            val progress = if (totalBytes > 0) ((totalRead * 100) / totalBytes).toInt() else 0

                            withContext(Dispatchers.Main) {
                                onProgress?.invoke(progress, speedText)
                                NotificationHelper.showUpdateProgressNotification(context, progress, speedText)
                            }
                            lastTime = now
                            lastBytes = totalRead
                        }
                    }

                    outputStream.flush()
                    outputStream.close()
                    inputStream.close()
                }

                withContext(Dispatchers.Main) {
                    onComplete?.invoke(apkFile)
                    installApk(context, apkFile)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error al descargar actualización", e)
                withContext(Dispatchers.Main) {
                    onError?.invoke(e.message ?: "Error al descargar")
                }
            }
        }
    }

    /**
     * Lanza el Intent para instalar el paquete APK descargado usando FileProvider.
     */
    fun installApk(context: Context, apkFile: File) {
        try {
            val contentUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo lanzar el instalador de APK", e)
        }
    }

    private fun isSemverNewer(remote: String, local: String): Boolean {
        return try {
            val r = remote.replace(Regex("[^0-9.]"), "").split(".").map { it.toIntOrNull() ?: 0 }
            val l = local.replace(Regex("[^0-9.]"), "").split(".").map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(r.size, l.size)) {
                val rPart = r.getOrElse(i) { 0 }
                val lPart = l.getOrElse(i) { 0 }
                if (rPart > lPart) return true
                if (rPart < lPart) return false
            }
            false
        } catch (_: Exception) {
            false
        }
    }
}
