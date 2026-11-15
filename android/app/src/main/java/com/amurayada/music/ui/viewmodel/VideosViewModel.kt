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
    val hideShortClips = MutableStateFlow(prefs.getBoolean("hide_short_videos", false))
    val onlyDownloaded = MutableStateFlow(false)

    fun setSortOption(sort: SortOption) {
        sortOption.value = sort
        prefs.edit().putString("video_sort_order", sort.name).apply()
    }

    fun setHideShortClips(enabled: Boolean) {
        hideShortClips.value = enabled
        prefs.edit().putBoolean("hide_short_videos", enabled).apply()
    }

    fun setOnlyDownloaded(enabled: Boolean) {
        onlyDownloaded.value = enabled
    }

    private fun parseDurationSeconds(dur: String): Long {
        if (dur.isBlank()) return 0L
        val parts = dur.trim().split(":").mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            1 -> parts[0]
            else -> 0L
        }
    }

    val videos: StateFlow<List<DownloadItem>> = combine(
        _rawVideos,
        searchQuery,
        sortOption,
        hideShortClips,
        onlyDownloaded
    ) { list, query, sort, hideShort, onlyDl ->
        var filtered = if (query.isBlank()) {
            list
        } else {
            val q = query.lowercase().trim()
            list.filter { it.title.lowercase().contains(q) }
        }

        if (onlyDl) {
            filtered = filtered.filter {
                it.id.startsWith("db_video_") ||
                    (it.localFilePath?.contains("OmniVault", ignoreCase = true) == true)
            }
        }

        if (hideShort) {
            filtered = filtered.filter {
                it.durationFormatted.isBlank() || parseDurationSeconds(it.durationFormatted) >= 5L
            }
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
                val seenPaths = HashSet<String>()
                val seenTitleSizes = HashSet<String>()
                val merged = ArrayList<DownloadItem>()
                for (item in (dbVideos + scanned)) {
                    val pathKey = (item.localFilePath ?: item.id).lowercase()
                    val normTitle = item.title.lowercase().replace(Regex("[^a-z0-9]"), "")
                    val sizeBucket = item.fileSizeBytes / 4096L
                    val contentKey = if (normTitle.isNotBlank() && item.fileSizeBytes > 0L) "${normTitle}_$sizeBucket" else ""

                    if (pathKey in seenPaths) continue
                    if (contentKey.isNotEmpty() && contentKey in seenTitleSizes) continue

                    seenPaths.add(pathKey)
                    if (contentKey.isNotEmpty()) seenTitleSizes.add(contentKey)
                    merged.add(item)
                }
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
