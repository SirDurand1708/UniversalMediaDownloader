package com.omnivault.media.utils

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Mantiene una copia espejo automática de playlists (música y video) en almacenamiento
 * compartido (Documents/OmniVault/Backups) para sobrevivir actualizaciones, limpieza de
 * caché o reinstalaciones del APK.
 */
object PlaylistBackupManager {
    private const val BACKUP_SUBDIR = "OmniVault/Backups"

    fun saveBackup(context: Context, fileName: String, jsonContent: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val collection = MediaStore.Files.getContentUri("external")
                val relativePath = "${Environment.DIRECTORY_DOCUMENTS}/$BACKUP_SUBDIR"

                val existingUri = findMediaStoreUri(context, fileName)
                if (existingUri != null) {
                    try {
                        resolver.openOutputStream(existingUri, "wt")?.use { out ->
                            out.write(jsonContent.toByteArray(Charsets.UTF_8))
                        }
                        return
                    } catch (_: Exception) {
                        // Si no se pudo sobrescribir (p. ej. tras reinstalación), recreamos el registro
                        try { resolver.delete(existingUri, null, null) } catch (_: Exception) {}
                    }
                }

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                }
                val newUri = resolver.insert(collection, values)
                if (newUri != null) {
                    resolver.openOutputStream(newUri, "wt")?.use { out ->
                        out.write(jsonContent.toByteArray(Charsets.UTF_8))
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    BACKUP_SUBDIR
                )
                if (!dir.exists()) dir.mkdirs()
                File(dir, fileName).writeText(jsonContent, Charsets.UTF_8)
            }
        } catch (_: Exception) {
            // Silencioso si no hay permiso de almacenamiento todavía
        }
    }

    fun readBackup(context: Context, fileName: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val uri = findMediaStoreUri(context, fileName) ?: return null
                context.contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader(Charsets.UTF_8).readText()
                }?.takeIf { it.isNotBlank() }
            } else {
                @Suppress("DEPRECATION")
                val file = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    "$BACKUP_SUBDIR/$fileName"
                )
                if (file.exists()) file.readText(Charsets.UTF_8).takeIf { it.isNotBlank() } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun findMediaStoreUri(context: Context, fileName: String): android.net.Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val baseName = fileName.substringBeforeLast(".")
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("${baseName}%.json", "%$BACKUP_SUBDIR%")
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        context.contentResolver.query(collection, projection, selection, args, sortOrder)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                return ContentUris.withAppendedId(collection, id)
            }
        }
        return null
    }
}
