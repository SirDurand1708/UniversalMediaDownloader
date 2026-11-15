package com.omnivault.media.utils

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class StandardizeCandidate(
    val mediaUri: Uri,
    val currentPath: String,
    val currentBaseName: String,
    val extension: String,
    val targetBaseName: String,
    val newTitle: String,
    val isVideo: Boolean
)

data class StandardizeExecutionResult(
    val renamedCount: Int,
    val failedCount: Int,
    val pendingPermissionChunks: List<List<StandardizeCandidate>>
)

object BulkMediaStandardizer {

    private const val PERMISSION_CHUNK_SIZE = 80

    suspend fun scanCandidates(
        context: Context,
        includeAudio: Boolean = true,
        includeVideo: Boolean = true
    ): List<StandardizeCandidate> = withContext(Dispatchers.IO) {
        val results = mutableListOf<StandardizeCandidate>()
        val usedTargetPaths = HashSet<String>()

        if (includeAudio) {
            scanMediaStoreCollection(
                context = context,
                collectionUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                isVideo = false,
                minDurationMs = 5_000L,
                usedTargetPaths = usedTargetPaths,
                out = results
            )
        }
        if (includeVideo) {
            scanMediaStoreCollection(
                context = context,
                collectionUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                isVideo = true,
                minDurationMs = 5_000L,
                usedTargetPaths = usedTargetPaths,
                out = results
            )
        }
        results
    }

    private fun scanMediaStoreCollection(
        context: Context,
        collectionUri: Uri,
        isVideo: Boolean,
        minDurationMs: Long,
        usedTargetPaths: HashSet<String>,
        out: MutableList<StandardizeCandidate>
    ) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.TITLE,
            MediaStore.MediaColumns.DURATION,
            "artist"
        )
        try {
            context.contentResolver.query(
                collectionUri,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val titleCol = cursor.getColumnIndex(MediaStore.MediaColumns.TITLE)
                val durCol = cursor.getColumnIndex(MediaStore.MediaColumns.DURATION)
                val artistCol = cursor.getColumnIndex("artist")

                while (cursor.moveToNext()) {
                    if (idCol < 0 || dataCol < 0) continue
                    val id = cursor.getLong(idCol)
                    val path = cursor.getString(dataCol) ?: continue
                    val file = File(path)
                    if (!file.exists()) continue

                    val duration = if (durCol >= 0) cursor.getLong(durCol) else 0L
                    if (duration in 1L until minDurationMs) continue

                    val displayName = if (nameCol >= 0) cursor.getString(nameCol) ?: file.name else file.name
                    if (FilenameStandardizer.isExcludedFromBulkStandardize(path, displayName)) continue

                    val currentBase = file.nameWithoutExtension
                    val ext = file.extension
                    if (currentBase.isBlank() || ext.isBlank()) continue

                    val rawTitle = if (titleCol >= 0) {
                        cursor.getString(titleCol)?.takeIf { it.isNotBlank() } ?: currentBase
                    } else {
                        currentBase
                    }
                    val artist = if (artistCol >= 0) cursor.getString(artistCol) else null

                    // Para audios aplicamos "Artista - Título" si hay artista válido;
                    // para videos limpiamos etiquetas basura sobre el nombre actual.
                    val proposedBase = if (!isVideo) {
                        FilenameStandardizer.formatStandardName(currentBase, artist)
                    } else {
                        FilenameStandardizer.standardizeTitle(currentBase)
                    }

                    if (proposedBase == currentBase || proposedBase.isBlank()) continue

                    val parentPath = file.parent ?: continue
                    val candidateTargetPath = File(parentPath, "$proposedBase.$ext").absolutePath
                    val lowerTargetKey = candidateTargetPath.lowercase()
                    if (lowerTargetKey in usedTargetPaths) continue

                    val targetFile = File(candidateTargetPath)
                    if (targetFile.exists() && targetFile.absolutePath != file.absolutePath) continue

                    usedTargetPaths.add(lowerTargetKey)
                    val cleanTitleTag = FilenameStandardizer.standardizeTitle(rawTitle)
                    val mediaUri = ContentUris.withAppendedId(collectionUri, id)

                    out.add(
                        StandardizeCandidate(
                            mediaUri = mediaUri,
                            currentPath = path,
                            currentBaseName = currentBase,
                            extension = ext,
                            targetBaseName = proposedBase,
                            newTitle = cleanTitleTag,
                            isVideo = isVideo
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("BulkMediaStandardizer", "Scan error ($collectionUri): ${e.message}")
        }
    }

    suspend fun applyBatch(
        context: Context,
        candidates: List<StandardizeCandidate>,
        collectPermissionChunks: Boolean = true
    ): StandardizeExecutionResult = withContext(Dispatchers.IO) {
        var renamedCount = 0
        var failedCount = 0
        val needsPermission = mutableListOf<StandardizeCandidate>()

        for (candidate in candidates) {
            val newDisplayName = "${candidate.targetBaseName}.${candidate.extension}"
            var updated = false

            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, newDisplayName)
                    put(MediaStore.MediaColumns.TITLE, candidate.newTitle)
                }
                val rows = context.contentResolver.update(candidate.mediaUri, values, null, null)
                if (rows > 0) {
                    updated = true
                }
            } catch (_: SecurityException) {
                if (collectPermissionChunks && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    needsPermission.add(candidate)
                    continue
                }
            } catch (_: Exception) {}

            // Fallback directo si es archivo accesible directamente en File API
            val oldFile = File(candidate.currentPath)
            val newFile = File(oldFile.parentFile, newDisplayName)
            if (!updated && oldFile.exists() && (!newFile.exists() || newFile.absolutePath == oldFile.absolutePath)) {
                try {
                    if (oldFile.renameTo(newFile)) {
                        updated = true
                        MediaScannerConnection.scanFile(
                            context,
                            arrayOf(oldFile.absolutePath, newFile.absolutePath),
                            null,
                            null
                        )
                    }
                } catch (_: Exception) {}
            }

            if (updated) {
                renamedCount++
                renameCompanionFilesSafely(oldFile.parentFile, candidate.currentBaseName, candidate.targetBaseName)
            } else {
                failedCount++
            }
        }

        val chunks = if (collectPermissionChunks && needsPermission.isNotEmpty()) {
            needsPermission.chunked(PERMISSION_CHUNK_SIZE)
        } else {
            emptyList()
        }

        StandardizeExecutionResult(
            renamedCount = renamedCount,
            failedCount = failedCount,
            pendingPermissionChunks = chunks
        )
    }

    fun createWriteRequestIntentSender(
        context: Context,
        chunk: List<StandardizeCandidate>
    ): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || chunk.isEmpty()) return null
        return try {
            MediaStore.createWriteRequest(
                context.contentResolver,
                chunk.map { it.mediaUri }
            ).intentSender
        } catch (e: Exception) {
            android.util.Log.w("BulkMediaStandardizer", "createWriteRequest error: ${e.message}")
            null
        }
    }

    private fun renameCompanionFilesSafely(parentDir: File?, oldBase: String, newBase: String) {
        if (parentDir == null) return
        listOf("lrc", "jpg", "png").forEach { ext ->
            try {
                val src = File(parentDir, "$oldBase.$ext")
                if (src.exists()) {
                    val dst = File(parentDir, "$newBase.$ext")
                    if (!dst.exists()) {
                        src.renameTo(dst)
                    }
                }
            } catch (_: Exception) {}
        }
    }
}
