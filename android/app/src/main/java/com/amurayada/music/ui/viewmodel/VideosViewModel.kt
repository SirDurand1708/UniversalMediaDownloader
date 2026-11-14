package com.amurayada.music.ui.viewmodel

import android.app.Application
import android.media.MediaScannerConnection
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omnivault.media.data.LocalMediaScanner
import com.omnivault.media.model.DownloadItem
import com.omnivault.media.model.SortOption
import com.omnivault.media.utils.FilenameStandardizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class VideosViewModel(application: Application) : AndroidViewModel(application) {

    private val _rawVideos = MutableStateFlow<List<DownloadItem>>(emptyList())
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val prefs = application.getSharedPreferences("omnivault_prefs", android.content.Context.MODE_PRIVATE)

    val searchQuery = MutableStateFlow("")
    val sortOption = MutableStateFlow<SortOption>(
        SortOption.fromKey(prefs.getString("video_sort_order", SortOption.DATE_DESC.name) ?: SortOption.DATE_DESC.name)
    )

    fun setSortOption(sort: SortOption) {
        sortOption.value = sort
        prefs.edit().putString("video_sort_order", sort.name).apply()
    }

    val videos: StateFlow<List<DownloadItem>> = combine(
        _rawVideos,
        searchQuery,
        sortOption
    ) { list, query, sort ->
        val filtered = if (query.isBlank()) {
            list
        } else {
            val q = query.lowercase().trim()
            list.filter { it.title.lowercase().contains(q) }
        }

        when (sort) {
            SortOption.DATE_DESC -> filtered.sortedByDescending { it.timestamp }
            SortOption.DATE_ASC -> filtered.sortedBy { it.timestamp }
            SortOption.NAME_ASC -> filtered.sortedBy { it.title.lowercase() }
            SortOption.NAME_DESC -> filtered.sortedByDescending { it.title.lowercase() }
            SortOption.SIZE_DESC -> filtered.sortedByDescending { it.fileSizeBytes }
            SortOption.SIZE_ASC -> filtered.sortedBy { it.fileSizeBytes }
            SortOption.MOST_PLAYED -> filtered.sortedByDescending { it.timestamp }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        loadVideos()
    }

    fun loadVideos() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val app = getApplication<Application>()
                val scanned = LocalMediaScanner.scanDeviceVideo(app)
                val dbVideos = withContext(Dispatchers.IO) {
                    val dao = com.amurayada.music.data.database.DownloadDatabase.getDatabase(app).downloadDao()
                    dao.getAllDownloads().first()
                        .filter { entity ->
                            val path = entity.localPath ?: ""
                            val lower = path.lowercase()
                            val mime = entity.mimeType ?: if (path.startsWith("content://")) {
                                try { app.contentResolver.getType(android.net.Uri.parse(path)) } catch (_: Exception) { null }
                            } else null
                            entity.isVideo ||
                                mime?.startsWith("video/", ignoreCase = true) == true ||
                                path.contains("/video/", ignoreCase = true) ||
                                lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
                        }
                        .mapNotNull { entity ->
                            val path = entity.localPath ?: return@mapNotNull null
                            val sec = (entity.duration / 1000L).coerceAtLeast(0L)
                            val durFormatted = if (sec > 0L) String.format("%02d:%02d", sec / 60, sec % 60) else ""
                            DownloadItem(
                                id = "db_video_${entity.songId}",
                                url = entity.youtubeUrl,
                                title = entity.title,
                                format = com.omnivault.media.model.MediaFormat.MP4_1080,
                                platform = com.omnivault.media.model.PlatformType.fromUrl(entity.youtubeUrl),
                                progress = 100,
                                speedText = "Descargado",
                                status = com.omnivault.media.model.DownloadStatus.COMPLETED,
                                localFilePath = path,
                                fileSizeFormatted = com.omnivault.media.utils.StorageHelper.formatFileSize(entity.fileSize),
                                fileSizeBytes = entity.fileSize,
                                durationFormatted = durFormatted,
                                thumbnailUrl = entity.thumbnailUrl ?: path,
                                artist = entity.artist,
                                timestamp = entity.downloadedAt
                            )
                        }
                }
                val merged = (dbVideos + scanned).distinctBy { it.localFilePath ?: it.id }
                _rawVideos.value = merged
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun standardizeVideo(item: DownloadItem, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val oldPath = item.localFilePath
            val success = FilenameStandardizer.standardizeItemFile(item)
            if (success) {
                val newPath = item.localFilePath
                if (oldPath != null && newPath != null) {
                    MediaScannerConnection.scanFile(
                        getApplication(),
                        arrayOf(oldPath, newPath),
                        null
                    ) { _, _ -> }
                }
                loadVideos()
                withContext(Dispatchers.Main) { onComplete(true) }
            } else {
                withContext(Dispatchers.Main) { onComplete(false) }
            }
        }
    }
}
