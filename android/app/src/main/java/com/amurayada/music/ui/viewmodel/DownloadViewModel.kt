package com.amurayada.music.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.amurayada.music.data.database.DownloadDatabase
import com.amurayada.music.data.model.DownloadProgress
import com.amurayada.music.data.model.Song
import com.amurayada.music.data.repository.DownloadRepositoryImpl
import com.amurayada.music.data.repository.StreamRepositoryImpl
import com.amurayada.music.worker.DownloadWorker
import com.omnivault.media.downloader.DownloadManager
import com.omnivault.media.downloader.MediaExtractor
import com.omnivault.media.downloader.MediaPreview
import com.omnivault.media.model.DownloadItem
import com.omnivault.media.model.MediaFormat
import com.omnivault.media.service.MediaDownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DownloadViewModel(
    application: Application
) : AndroidViewModel(application) {
    
    private val downloadDao = DownloadDatabase.getDatabase(application).downloadDao()
    private val streamRepository = StreamRepositoryImpl()
    private val downloadRepository = DownloadRepositoryImpl(application, downloadDao, streamRepository)
    private val context = application
    private val omniDownloadManager = DownloadManager.getInstance(application)
    
    private val _downloadedSongs = MutableStateFlow<List<Song>>(emptyList())
    val downloadedSongs: StateFlow<List<Song>> = _downloadedSongs.asStateFlow()
    
    private val _activeDownloads = MutableStateFlow<List<DownloadProgress>>(emptyList())
    val activeDownloads: StateFlow<List<DownloadProgress>> = _activeDownloads.asStateFlow()

    // OmniVault Universal Engine State
    val urlInput = MutableStateFlow("")
    val selectedFormat = MutableStateFlow(MediaFormat.M4A)
    val mediaPreview = MutableStateFlow<MediaPreview?>(null)
    val isAnalyzing = MutableStateFlow(false)

    private val _omniActiveDownloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val omniActiveDownloads: StateFlow<List<DownloadItem>> = _omniActiveDownloads.asStateFlow()

    private val _omniHistory = MutableStateFlow<List<DownloadItem>>(emptyList())
    val omniHistory: StateFlow<List<DownloadItem>> = _omniHistory.asStateFlow()
    
    init {
        observeDownloadedSongs()
        observeActiveDownloads()
        initOmniVaultListener()
    }

    private fun initOmniVaultListener() {
        _omniActiveDownloads.value = omniDownloadManager.getActiveDownloads()
        _omniHistory.value = omniDownloadManager.getHistory()

        omniDownloadManager.addDownloadListener {
            viewModelScope.launch(Dispatchers.Main) {
                _omniActiveDownloads.value = omniDownloadManager.getActiveDownloads()
                _omniHistory.value = omniDownloadManager.getHistory()
            }
        }
    }
    
    private fun observeDownloadedSongs() {
        viewModelScope.launch {
            downloadRepository.observeDownloadedSongs().collect { songs ->
                _downloadedSongs.value = songs
            }
        }
    }
    
    private fun observeActiveDownloads() {
        viewModelScope.launch {
            downloadRepository.getActiveDownloads().collect { downloads ->
                _activeDownloads.value = downloads
            }
        }
    }
    
    private val _showDownloadLocationDialog = MutableStateFlow(false)
    val showDownloadLocationDialog: StateFlow<Boolean> = _showDownloadLocationDialog.asStateFlow()
    
    private val pendingSongs = mutableListOf<Song>()
    
    fun setDownloadLocation(mode: String, uri: String?) {
        val prefs = context.getSharedPreferences("music_settings", android.content.Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("download_path_mode", mode)
            putString("download_path_uri", uri)
            apply()
        }
        _showDownloadLocationDialog.value = false
        
        // Process pending
        if (pendingSongs.isNotEmpty()) {
            val songsToDownload = pendingSongs.toList()
            pendingSongs.clear()
            processDownloads(songsToDownload)
        }
    }
    
    fun dismissDialog() {
        _showDownloadLocationDialog.value = false
        pendingSongs.clear()
    }
    
    fun requestChangeLocation() {
        _showDownloadLocationDialog.value = true
    }

    fun downloadSong(song: Song) {
        // Use universal downloader directly
        val sourceUrl = if (song.path.startsWith("http")) song.path else "https://music.youtube.com/watch?v=${song.path}"
        startUniversalDownload(sourceUrl, MediaFormat.M4A)
    }

    fun downloadAlbum(songs: List<Song>) {
        songs.forEach { downloadSong(it) }
    }

    // =========================================================================
    // UNIVERSAL DOWNLOAD METHODS (OmniVault)
    // =========================================================================

    fun analyzeUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return

        viewModelScope.launch {
            isAnalyzing.value = true
            try {
                val preview = MediaExtractor.analyze(trimmed)
                mediaPreview.value = preview
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isAnalyzing.value = false
            }
        }
    }

    fun startUniversalDownload(url: String, format: MediaFormat) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return

        // 1. Asegurar canales del sistema
        com.omnivault.media.utils.NotificationHelper.createNotificationChannels(getApplication())

        // 2. Encolar directamente en DownloadManager (inicia de inmediato en memoria)
        omniDownloadManager.enqueueDownload(trimmed, format)

        // 3. Iniciar Foreground Service de forma segura (con try/catch) para persistencia en segundo plano
        try {
            MediaDownloadService.startDownload(getApplication(), trimmed, format)
        } catch (e: Exception) {
            android.util.Log.e("DownloadViewModel", "Error starting MediaDownloadService", e)
        }

        urlInput.value = ""
        mediaPreview.value = null
    }

    fun cancelOmniDownload(taskId: String) {
        omniDownloadManager.cancelDownload(taskId)
    }
    
    private fun checkAndDownload(songs: List<Song>) {
        val prefs = context.getSharedPreferences("music_settings", android.content.Context.MODE_PRIVATE)
        val mode = prefs.getString("download_path_mode", null)
        
        if (mode == null) {
            // First time - ask user
            pendingSongs.addAll(songs)
            _showDownloadLocationDialog.value = true
        } else {
            // Already configured
            processDownloads(songs)
        }
    }
    
    private fun processDownloads(songs: List<Song>) {
        songs.forEach { song ->
            enqueueWorker(song)
        }
    }
    
    private fun enqueueWorker(song: Song) {
        val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(
                    "songId" to song.id,
                    "title" to song.title,
                    "artist" to song.artist,
                    "album" to song.album,
                    "duration" to song.duration,
                    "path" to song.path,
                    "albumArtUri" to song.albumArtUri?.toString()
                )
            )
            .build()
        
        WorkManager.getInstance(context).enqueue(workRequest)
    }
    
    fun cancelDownload(songId: Long) {
        viewModelScope.launch {
            downloadRepository.cancelDownload(songId)
        }
    }
    
    fun deleteDownload(songId: Long) {
        viewModelScope.launch {
            try {
                downloadRepository.deleteDownload(songId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    fun isDownloaded(songId: Long): Flow<Boolean> = flow {
        emit(downloadRepository.isDownloaded(songId))
    }
    
    fun getDownloadProgress(songId: Long): Flow<DownloadProgress?> {
        return downloadRepository.getDownloadProgress(songId)
    }
}
