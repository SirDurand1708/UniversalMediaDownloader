package com.omnivault.media.utils

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File

object StorageHelper {
    private const val FOLDER_NAME = "OmniVault"

    fun getOutputDirectory(isAudio: Boolean): File {
        val baseDir = if (isAudio) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        } else {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        }
        val targetFolder = File(baseDir, FOLDER_NAME)
        if (!targetFolder.exists()) {
            targetFolder.mkdirs()
        }
        return targetFolder
    }

    fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    }

    fun scanMediaFile(context: Context, file: File, mimeType: String) {
        try {
            MediaScannerConnection.scanFile(
                context.applicationContext,
                arrayOf(file.absolutePath),
                arrayOf(mimeType),
                null
            )
        } catch (_: Exception) {}
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
}
