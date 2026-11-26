package com.amurayada.music.data.repository

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import com.amurayada.music.data.database.DownloadDao
import com.amurayada.music.data.database.DownloadEntity
import com.amurayada.music.data.model.DownloadProgress
import com.amurayada.music.data.model.DownloadStatus
import com.amurayada.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

class DownloadRepositoryImpl(
    private val context: Context,
    private val downloadDao: DownloadDao
) : DownloadRepository {
    
    private val activeDownloads = MutableStateFlow<Map<Long, DownloadProgress>>(emptyMap())
    
    override suspend fun downloadSong(song: Song): Flow<DownloadProgress> = flow {
        val processId = "song_${song.id}"
        try {
            val queuedProgress = DownloadProgress(
                songId = song.id,
                progress = 0f,
                status = DownloadStatus.QUEUED
            )
            emit(queuedProgress)
            updateActiveDownload(song.id, queuedProgress)

            val videoUrl = when {
                song.path.startsWith("http") -> song.path
                song.path.contains("watch?v=") -> song.path
                song.path.matches(Regex("^[a-zA-Z0-9_-]{11}$")) -> "https://music.youtube.com/watch?v=${song.path}"
                else -> {
                    val videoIdRegex = Regex("(?:v=|/)([a-zA-Z0-9_-]{11})(?:[&?/]|$)")
                    val match = videoIdRegex.find(song.path)
                    if (match != null) {
                        "https://music.youtube.com/watch?v=${match.groupValues[1]}"
                    } else {
                        "https://music.youtube.com/watch?v=${song.path}"
                    }
                }
            }

            val engine = com.omnivault.media.downloader.UniversalDownloadEngine.getInstance(context)
            val result = engine.executeDownload(
                url = videoUrl,
                format = com.omnivault.media.model.MediaFormat.M4A,
                processId = processId,
                preloadedSongId = song.id
            ) { state ->
                val mappedStatus = when (state.status) {
                    com.omnivault.media.model.DownloadStatus.QUEUED,
                    com.omnivault.media.model.DownloadStatus.FETCHING_METADATA,
                    com.omnivault.media.model.DownloadStatus.PAUSED -> DownloadStatus.QUEUED
                    com.omnivault.media.model.DownloadStatus.DOWNLOADING,
                    com.omnivault.media.model.DownloadStatus.PROCESSING -> DownloadStatus.DOWNLOADING
                    com.omnivault.media.model.DownloadStatus.COMPLETED -> DownloadStatus.COMPLETED
                    com.omnivault.media.model.DownloadStatus.CANCELLED -> DownloadStatus.CANCELLED
                    com.omnivault.media.model.DownloadStatus.ERROR -> DownloadStatus.FAILED
                }
                updateActiveDownload(
                    song.id,
                    DownloadProgress(
                        songId = song.id,
                        progress = state.progress.coerceIn(0f, 1f),
                        status = mappedStatus,
                        totalBytes = state.fileSizeBytes,
                        error = state.errorReason
                    )
                )
            }

            val completedProgress = DownloadProgress(
                songId = song.id,
                progress = 1f,
                status = DownloadStatus.COMPLETED,
                totalBytes = result.fileSizeBytes
            )
            emit(completedProgress)
            removeActiveDownload(song.id)

        } catch (e: Exception) {
            e.printStackTrace()
            val failedProgress = DownloadProgress(
                songId = song.id,
                progress = 0f,
                status = DownloadStatus.FAILED,
                error = e.message
            )
            emit(failedProgress)
            removeActiveDownload(song.id)
        }
    }
    
    override suspend fun cancelDownload(songId: Long) {
        com.omnivault.media.downloader.UniversalDownloadEngine.getInstance(context)
            .cancelDownload("song_$songId")
        val cancelledProgress = DownloadProgress(
            songId = songId,
            progress = 0f,
            status = DownloadStatus.CANCELLED
        )
        updateActiveDownload(songId, cancelledProgress)
        removeActiveDownload(songId)
    }
    
    private fun isVideoDownload(entity: DownloadEntity): Boolean {
        if (entity.isVideo) return true
        if (entity.mimeType?.startsWith("video/", ignoreCase = true) == true) return true
        val path = entity.localPath ?: return false
        if (path.startsWith("content://")) {
            val resolvedMime = try {
                context.contentResolver.getType(Uri.parse(path))
            } catch (_: Exception) {
                null
            }
            if (resolvedMime?.startsWith("video/", ignoreCase = true) == true) return true
            if (path.contains("/video/", ignoreCase = true)) return true
        }
        val lower = path.lowercase()
        return lower.endsWith(".mp4") ||
            lower.endsWith(".mkv") ||
            lower.endsWith(".webm") ||
            lower.endsWith(".mov") ||
            lower.endsWith(".avi") ||
            entity.album.equals("Video", ignoreCase = true)
    }

    override suspend fun getDownloadedSongs(): List<Song> = withContext(Dispatchers.IO) {
        downloadDao.getAllDownloads().first()
            .filterNot { isVideoDownload(it) }
            .map { entity ->
                Song(
                    id = entity.songId,
                    title = entity.title,
                    artist = entity.artist,
                    album = entity.album,
                    duration = entity.duration,
                    albumArtUri = entity.thumbnailUrl?.let { Uri.parse(it) },
                    path = entity.localPath ?: entity.youtubeUrl,
                    dateAdded = entity.downloadedAt,
                    albumId = entity.album.hashCode().toLong(),
                    mimeType = entity.mimeType,
                    fileSize = entity.fileSize
                )
            }
    }
    
    override fun observeDownloadedSongs(): Flow<List<Song>> {
        return downloadDao.getAllDownloads().map { entities ->
            entities
                .filterNot { isVideoDownload(it) }
                .map { entity ->
                    Song(
                        id = entity.songId,
                        title = entity.title,
                        artist = entity.artist,
                        album = entity.album,
                        duration = entity.duration,
                        albumArtUri = entity.thumbnailUrl?.let { Uri.parse(it) },
                        path = entity.localPath ?: entity.youtubeUrl,
                        dateAdded = entity.downloadedAt,
                        albumId = entity.album.hashCode().toLong(),
                        mimeType = entity.mimeType,
                        fileSize = entity.fileSize
                    )
                }
        }
    }
    
    override suspend fun deleteDownload(songId: Long): Unit = withContext(Dispatchers.IO) {
        val download = downloadDao.getDownload(songId)
        download?.let {
            it.localPath?.let { path ->
                try {
                    if (path.startsWith("content://")) {
                        context.contentResolver.delete(Uri.parse(path), null, null)
                    } else {
                        val file = File(path)
                        if (file.exists()) {
                            file.delete()
                        }
                    }
                } catch (_: Exception) {}
            }
            downloadDao.deleteDownloadById(songId)
        }
    }
    
    override suspend fun isDownloaded(songId: Long): Boolean = withContext(Dispatchers.IO) {
        downloadDao.isDownloaded(songId)
    }
    
    override fun getDownloadProgress(songId: Long): Flow<DownloadProgress?> {
        return activeDownloads.map { it[songId] }
    }
    
    override fun getActiveDownloads(): Flow<List<DownloadProgress>> {
        return activeDownloads.map { it.values.toList() }
    }
    
    private fun updateActiveDownload(songId: Long, progress: DownloadProgress) {
        activeDownloads.value = activeDownloads.value + (songId to progress)
    }
    
    private fun removeActiveDownload(songId: Long) {
        activeDownloads.value = activeDownloads.value - songId
    }
    

    override suspend fun validateDownloads() {
        withContext(Dispatchers.IO) {
            try {
                val entities = downloadDao.getAllDownloads().first()
                for (entity in entities) {
                    val path = entity.localPath ?: continue
                    if (!fileExists(path)) {
                        android.util.Log.i("DownloadRepo", "🗑️ Removing ghost download (file not found): ${entity.title}")
                        downloadDao.deleteDownloadById(entity.songId)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("DownloadRepo", "Error validating downloads", e)
            }
        }
    }

    private fun fileExists(path: String): Boolean {
        return try {
            if (path.startsWith("content://")) {
                val uri = Uri.parse(path)
                val docFile = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)
                docFile?.exists() == true
            } else {
                File(path).exists()
            }
        } catch (e: Exception) {
            false
        }
    }
}
