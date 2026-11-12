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

    val searchQuery = MutableStateFlow("")
    val sortOption = MutableStateFlow(SortOption.RECENT)

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
            SortOption.RECENT -> filtered.sortedByDescending { it.timestamp }
            SortOption.OLDEST -> filtered.sortedBy { it.timestamp }
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
            val path = item.localFilePath ?: return@launch
            val file = File(path)
            if (!file.exists()) {
                withContext(Dispatchers.Main) { onComplete(false) }
                return@launch
            }

            val standardized = FilenameStandardizer.standardizeFilename(file)
            if (standardized != null && standardized.exists()) {
                MediaScannerConnection.scanFile(
                    getApplication(),
                    arrayOf(file.absolutePath, standardized.absolutePath),
                    null
                ) { _, _ -> }
                loadVideos()
                withContext(Dispatchers.Main) { onComplete(true) }
            } else {
                withContext(Dispatchers.Main) { onComplete(false) }
            }
        }
    }
}
