package com.omnivault.media.utils

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File

object StorageHelper {
    private const val FOLDER_NAME = "OmniVault"
    private const val PREFS_NAME = "omnivault_prefs"
    private const val KEY_CUSTOM_STORAGE_PATH = "custom_storage_path"

    fun getOutputDirectory(isAudio: Boolean, context: Context? = null): File {
        if (context != null) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val custom = prefs.getString(KEY_CUSTOM_STORAGE_PATH, null)
            if (!custom.isNullOrBlank()) {
                val folder = File(custom, if (isAudio) "Music" else "Movies")
                if (folder.exists() || folder.mkdirs()) {
                    return folder
                }
            }
        }

        // 1. Intentar carpeta estándar Music o Movies en almacenamiento público
        try {
            val baseDir = if (isAudio) {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            } else {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            }
            val targetFolder = File(baseDir, FOLDER_NAME)
            if (targetFolder.exists() || targetFolder.mkdirs()) {
                return targetFolder
            }
        } catch (_: Exception) {}

        // 2. Fallback: Downloads/OmniVault (escribible sin bloqueos de Scoped Storage)
        try {
            val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dlFolder = File(dlDir, FOLDER_NAME)
            if (dlFolder.exists() || dlFolder.mkdirs()) {
                return dlFolder
            }
        } catch (_: Exception) {}

        // 3. Fallback: Directorio externo propio de la app (100% garantizado en Android 10-14+)
        if (context != null) {
            try {
                val appDir = context.getExternalFilesDir(if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES)
                if (appDir != null && (appDir.exists() || appDir.mkdirs())) {
                    return appDir
                }
            } catch (_: Exception) {}
        }

        // 4. Último recurso seguro
        return context?.filesDir ?: File("/sdcard/Download")
    }

    fun setCustomStoragePath(context: Context, path: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CUSTOM_STORAGE_PATH, path).apply()
    }

    fun getCustomStoragePath(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CUSTOM_STORAGE_PATH, null)
    }

    fun getStorageSummary(context: Context): String {
        val custom = getCustomStoragePath(context)
        return if (!custom.isNullOrBlank()) {
            "Personalizado / MicroSD: $custom"
        } else {
            "Almacenamiento Interno (Música/OmniVault y Películas/OmniVault)"
        }
    }

    fun sanitizeFilename(name: String): String {
        val cleaned = FilenameStandardizer.standardizeTitle(name)
        return cleaned.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    }

    fun scanMediaFile(context: Context, file: File, mimeType: String) {
        if (!file.exists()) return
        try {
            val appContext = context.applicationContext

            // 1. Notificación principal mediante MediaScannerConnection
            MediaScannerConnection.scanFile(
                appContext,
                arrayOf(file.absolutePath),
                arrayOf(mimeType)
            ) { path, uri ->
                android.util.Log.i("StorageHelper", "Media indexed in MediaStore: $path -> $uri")
            }

            // 2. Broadcast de difusión para compatibilidad con reproductores de música de terceros
            try {
                val mediaScanIntent = android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                mediaScanIntent.data = android.net.Uri.fromFile(file)
                appContext.sendBroadcast(mediaScanIntent)
            } catch (_: Exception) {}

        } catch (e: Exception) {
            android.util.Log.w("StorageHelper", "Error scanning media file: ${e.message}")
        }
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
}
