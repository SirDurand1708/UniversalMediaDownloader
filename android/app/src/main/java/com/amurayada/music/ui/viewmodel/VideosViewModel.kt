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
                val scanned = LocalMediaScanner.scanDeviceVideo(getApplication())
                _rawVideos.value = scanned
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
