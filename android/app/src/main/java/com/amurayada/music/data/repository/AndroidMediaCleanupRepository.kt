package com.amurayada.music.data.repository

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.amurayada.music.data.model.Song
import com.omnivault.media.data.LocalMediaScanner
import com.omnivault.media.model.DownloadItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File

class AndroidMediaCleanupRepository(context: Context) : MediaCleanupRepository {

    private val appContext = context.applicationContext

    override fun observeVideoStoreChanges(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                trySend(Unit)
            }
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        try {
            appContext.contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
        } catch (_: Exception) {}
        awaitClose {
            try {
                appContext.contentResolver.unregisterContentObserver(observer)
            } catch (_: Exception) {}
        }
    }

    override fun observeAudioStoreChanges(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                trySend(Unit)
            }
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        try {
            val resolver = appContext.contentResolver
            resolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
            resolver.registerContentObserver(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, true, observer)
            resolver.registerContentObserver(MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI, true, observer)
        } catch (_: Exception) {}
        awaitClose {
            try {
                appContext.contentResolver.unregisterContentObserver(observer)
            } catch (_: Exception) {}
        }
    }

    override suspend fun scanAllDeviceVideos(): List<DownloadItem> =
        withContext(Dispatchers.IO) { LocalMediaScanner.scanDeviceVideo(appContext) }

    override fun getMimeTypeForUri(uriString: String): String? =
        try {
            appContext.contentResolver.getType(Uri.parse(uriString))
        } catch (_: Exception) {
            null
        }

    override fun resolveAudioUriAndName(rawPath: String): Pair<String?, String?> {
        var resolvedDataPath: String? = null
        var resolvedDisplayName: String? = null
        try {
            appContext.contentResolver.query(
                Uri.parse(rawPath),
                arrayOf(MediaStore.Audio.Media.DATA, MediaStore.MediaColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val dataIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                    if (dataIdx != -1) resolvedDataPath = cursor.getString(dataIdx)?.trim()
                    val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    if (nameIdx != -1) resolvedDisplayName = cursor.getString(nameIdx)?.trim()
                }
            }
        } catch (_: Exception) {}
        return resolvedDataPath to resolvedDisplayName
    }

    override suspend fun resolveAudioMediaStoreUri(song: Song): Uri = withContext(Dispatchers.IO) {
        if (song.path.startsWith("content://")) {
            return@withContext Uri.parse(song.path)
        }
        if (song.path.isNotBlank()) {
            try {
                appContext.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media._ID),
                    "${MediaStore.Audio.Media.DATA} = ?",
                    arrayOf(song.path),
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                        if (idCol != -1) {
                            val mediaId = cursor.getLong(idCol)
                            return@withContext ContentUris.withAppendedId(
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                mediaId
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
    }

    override suspend fun deleteSingleVideo(item: DownloadItem): SingleVideoDeleteResult =
        withContext(Dispatchers.IO) {
            val path = item.localFilePath
            if (path.isNullOrBlank()) return@withContext SingleVideoDeleteResult.Deleted

            if (path.startsWith("content://")) {
                val contentUri = Uri.parse(path)
                try {
                    if (appContext.contentResolver.delete(contentUri, null, null) > 0) {
                        return@withContext SingleVideoDeleteResult.Deleted
                    }
                } catch (_: SecurityException) {}
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val pi = MediaStore.createDeleteRequest(appContext.contentResolver, listOf(contentUri))
                    return@withContext SingleVideoDeleteResult.RequiresPermission(pi.intentSender)
                }
                return@withContext SingleVideoDeleteResult.Failed
            }

            val file = File(path)
            val deletedDirect = if (file.exists()) file.delete() else true
            if (deletedDirect) {
                MediaScannerConnection.scanFile(appContext, arrayOf(path), null, null)
                return@withContext SingleVideoDeleteResult.Deleted
            }

            var mediaUri: Uri? = null
            appContext.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID),
                "${MediaStore.Video.Media.DATA} = ?",
                arrayOf(path),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                    mediaUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                }
            }

            if (mediaUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val pi = MediaStore.createDeleteRequest(appContext.contentResolver, listOf(mediaUri!!))
                SingleVideoDeleteResult.RequiresPermission(pi.intentSender)
            } else {
                SingleVideoDeleteResult.Failed
            }
        }

    override suspend fun deleteBatchVideos(items: List<DownloadItem>): BatchVideoDeleteResult =
        withContext(Dispatchers.IO) {
            val pendingUris = mutableListOf<Uri>()
            val deletedItems = mutableListOf<DownloadItem>()
            for (item in items) {
                val path = item.localFilePath
                if (path.isNullOrBlank()) {
                    deletedItems.add(item)
                } else if (path.startsWith("content://")) {
                    val uri = Uri.parse(path)
                    try {
                        if (appContext.contentResolver.delete(uri, null, null) > 0) {
                            deletedItems.add(item)
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            pendingUris.add(uri)
                        }
                    } catch (_: SecurityException) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            pendingUris.add(uri)
                        }
                    }
                } else {
                    val f = File(path)
                    if (!f.exists() || f.delete()) {
                        deletedItems.add(item)
                        MediaScannerConnection.scanFile(appContext, arrayOf(path), null, null)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        appContext.contentResolver.query(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            arrayOf(MediaStore.Video.Media._ID),
                            "${MediaStore.Video.Media.DATA} = ?",
                            arrayOf(path),
                            null
                        )?.use { c ->
                            if (c.moveToFirst()) {
                                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                                pendingUris.add(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id))
                            }
                        }
                    }
                }
            }
            if (pendingUris.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val pi = MediaStore.createDeleteRequest(appContext.contentResolver, pendingUris)
                BatchVideoDeleteResult.RequiresPermission(pi.intentSender, deletedItems)
            } else {
                BatchVideoDeleteResult.Completed(deletedItems)
            }
        }

    override suspend fun deleteAudioSong(song: Song): AudioDeleteResult = withContext(Dispatchers.IO) {
        val mediaUri = resolveAudioMediaStoreUri(song)
        var securityException: SecurityException? = null
        var deletedRows = 0

        try {
            deletedRows = appContext.contentResolver.delete(mediaUri, null, null)
        } catch (se: SecurityException) {
            securityException = se
        } catch (_: Exception) {}

        val isContentUri = song.path.startsWith("content://")
        var fileDeleted = deletedRows > 0
        if (song.path.isNotBlank() && !isContentUri) {
            if (!fileDeleted) {
                try {
                    deletedRows = appContext.contentResolver.delete(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        "${MediaStore.Audio.Media.DATA} = ?",
                        arrayOf(song.path)
                    )
                    fileDeleted = deletedRows > 0
                } catch (se: SecurityException) {
                    if (securityException == null) securityException = se
                } catch (_: Exception) {}
            }

            val file = File(song.path)
            if (file.exists()) {
                try {
                    if (file.delete()) fileDeleted = true
                } catch (_: Exception) {}
            } else if (!fileDeleted && securityException == null) {
                fileDeleted = true
            }
        }

        val stillExists = if (isContentUri) {
            !fileDeleted && securityException != null
        } else if (song.path.isNotBlank()) {
            File(song.path).exists()
        } else {
            !fileDeleted
        }

        if (stillExists && securityException != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val pendingIntent = MediaStore.createDeleteRequest(
                        appContext.contentResolver,
                        listOf(mediaUri)
                    )
                    return@withContext AudioDeleteResult.RequiresPermission(pendingIntent.intentSender)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    securityException is RecoverableSecurityException
                ) {
                    return@withContext AudioDeleteResult.RequiresPermission(
                        securityException.userAction.actionIntent.intentSender
                    )
                }
            } catch (_: Exception) {}
        }
        AudioDeleteResult.Deleted
    }

    override suspend fun findDuplicateAudios(songs: List<Song>): List<Pair<Song, Long>> =
        withContext(Dispatchers.IO) {
            songs
                .mapNotNull { song ->
                    val f = File(song.path)
                    val len = if (f.exists()) f.length() else 0L
                    if (len > 0L) Pair(song, len) else null
                }
                .groupBy { (song, len) ->
                    val norm = song.title.lowercase().replace(Regex("[^a-z0-9]"), "").take(24)
                    Pair(len, norm)
                }
                .values
                .filter { it.size > 1 }
                .flatMap { it.drop(1) }
        }

    override suspend fun findDuplicateVideos(): List<DownloadItem> =
        withContext(Dispatchers.IO) {
            LocalMediaScanner.scanDeviceVideo(appContext)
                .filter { it.fileSizeBytes > 0L }
                .groupBy {
                    val norm = it.title.lowercase().replace(Regex("[^a-z0-9]"), "").take(24)
                    Pair(it.fileSizeBytes, norm)
                }
                .values
                .filter { it.size > 1 }
                .flatMap { group -> group.drop(1) }
        }

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
