package com.amurayada.music.data.repository

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.amurayada.music.data.model.Song
import com.omnivault.media.model.DownloadItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AndroidMediaCleanupRepository(context: Context) : MediaCleanupRepository {

    private val appContext = context.applicationContext

    override suspend fun deleteDuplicateVideos(items: List<DownloadItem>): DuplicateDeletionResult =
        withContext(Dispatchers.IO) {
            val pendingUris = mutableListOf<Uri>()
            var deletedDirect = 0

            items.forEach { item ->
                val mediaId = item.id.removePrefix("media_video_").toLongOrNull()
                if (mediaId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    pendingUris.add(
                        ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            mediaId
                        )
                    )
                } else {
                    val path = item.localFilePath
                    if (!path.isNullOrBlank() && File(path).delete()) {
                        deletedDirect++
                    }
                }
            }

            if (pendingUris.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val pi = MediaStore.createDeleteRequest(appContext.contentResolver, pendingUris)
                    DuplicateDeletionResult.RequiresPermission(pi.intentSender)
                } catch (e: Exception) {
                    DuplicateDeletionResult.Error(e.message ?: "Error al solicitar eliminación")
                }
            } else {
                DuplicateDeletionResult.DeletedDirectly(deletedDirect, isVideo = true)
            }
        }

    override suspend fun deleteDuplicateAudios(songs: List<Song>): DuplicateDeletionResult =
        withContext(Dispatchers.IO) {
            val pendingUris = mutableListOf<Uri>()
            var deletedDirect = 0

            songs.forEach { song ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !song.path.contains(appContext.packageName)) {
                    val candidateUri = if (song.path.startsWith("content://")) {
                        Uri.parse(song.path)
                    } else {
                        ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            song.id
                        )
                    }
                    val existsInMediaStore = try {
                        appContext.contentResolver.query(
                            candidateUri,
                            arrayOf(MediaStore.Audio.Media._ID),
                            null,
                            null,
                            null
                        )?.use { it.moveToFirst() } == true
                    } catch (_: Exception) {
                        false
                    }
                    if (existsInMediaStore) {
                        pendingUris.add(candidateUri)
                    } else if (!song.path.startsWith("content://") && File(song.path).delete()) {
                        deletedDirect++
                    }
                } else if (!song.path.startsWith("content://") && File(song.path).delete()) {
                    deletedDirect++
                }
            }

            if (pendingUris.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val pi = MediaStore.createDeleteRequest(appContext.contentResolver, pendingUris)
                    DuplicateDeletionResult.RequiresPermission(pi.intentSender)
                } catch (e: Exception) {
                    DuplicateDeletionResult.Error(e.message ?: "Error al solicitar eliminación")
                }
            } else {
                DuplicateDeletionResult.DeletedDirectly(deletedDirect, isVideo = false)
            }
        }
}
