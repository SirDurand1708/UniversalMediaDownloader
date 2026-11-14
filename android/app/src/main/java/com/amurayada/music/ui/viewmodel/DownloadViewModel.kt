package com.amurayada.music.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.amurayada.music.data.database.DownloadDatabase
import com.amurayada.music.data.model.DownloadProgress
import com.amurayada.music.data.model.DownloadStatus as AppDownloadStatus
import com.amurayada.music.data.model.Song
import com.amurayada.music.worker.DownloadWorker
import com.omnivault.media.downloader.DownloadManager
import com.omnivault.media.downloader.MediaExtractor
import com.omnivault.media.downloader.MediaPreview
import com.omnivault.media.downloader.UniversalDownloadEngine
import com.omnivault.media.downloader.UniversalDownloadState
import com.omnivault.media.model.DownloadItem
import com.omnivault.media.model.DownloadStatus
import com.omnivault.media.model.MediaFormat
import com.omnivault.media.model.PlatformType
import com.omnivault.media.service.MediaDownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DownloadViewModel(
    application: Application
) : AndroidViewModel(application) {
    
    private val downloadDao = DownloadDatabase.getDatabase(application).downloadDao()
    private val context = application
    private val omniDownloadManager = DownloadManager.getInstance(application)
    private val universalEngine = UniversalDownloadEngine.getInstance(application)
    
    private val _downloadedSongs = MutableStateFlow<List<Song>>(emptyList())
    val downloadedSongs: StateFlow<List<Song>> = _downloadedSongs.asStateFlow()
    
    private val _activeDownloads = MutableStateFlow<List<DownloadProgress>>(emptyList())
    val activeDownloads: StateFlow<List<DownloadProgress>> = _activeDownloads.asStateFlow()

    // Estados en tiempo real (0f..1f) emitidos por UniversalDownloadEngine (youtubedl-android)
    val universalDownloadStates: StateFlow<Map<String, UniversalDownloadState>> = universalEngine.downloadsState

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
            downloadDao.getAllDownloads()
                .map { entities ->
                    entities.map { entity ->
                        Song(
                            id = entity.songId,
                            title = entity.title,
                            artist = entity.artist,
                            album = entity.album,
                            duration = entity.duration,
                            albumArtUri = entity.thumbnailUrl?.let { Uri.parse(it) },
                            path = entity.localPath ?: entity.youtubeUrl,
                            dateAdded = entity.downloadedAt,
                            albumId = entity.album.hashCode().toLong()
                        )
                    }
                }
                .collect { songs ->
                    _downloadedSongs.value = songs
                }
        }
    }
    
    private fun observeActiveDownloads() {
        viewModelScope.launch {
            universalEngine.downloadsState
                .map { stateMap ->
                    stateMap.values.map { state ->
                        val songId = state.processId.removePrefix("song_").toLongOrNull()
                            ?: Math.abs(state.url.hashCode().toLong())
                        val mappedStatus = when (state.status) {
                            DownloadStatus.QUEUED, DownloadStatus.FETCHING_METADATA -> AppDownloadStatus.QUEUED
                            DownloadStatus.DOWNLOADING, DownloadStatus.PROCESSING -> AppDownloadStatus.DOWNLOADING
                            DownloadStatus.COMPLETED -> AppDownloadStatus.COMPLETED
                            DownloadStatus.CANCELLED -> AppDownloadStatus.CANCELLED
                            DownloadStatus.ERROR -> AppDownloadStatus.FAILED
                        }
                        DownloadProgress(
                            songId = songId,
                            progress = state.progress.coerceIn(0f, 1f),
                            status = mappedStatus,
                            totalBytes = state.fileSizeBytes,
                            error = state.errorReason
                        )
                    }
                }
                .collect { downloads ->
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
                val meta = try {
                    universalEngine.extractMetadata(trimmed, selectedFormat.value)
                } catch (_: Exception) {
                    null
                }
                if (meta != null) {
                    val platform = PlatformType.fromUrl(trimmed)
                    mediaPreview.value = MediaPreview(
                        title = meta.title,
                        author = meta.artist,
                        thumbnailUrl = meta.thumbnailUrl,
                        platform = platform,
                        originalUrl = meta.sourceUrl
                    )
                } else {
                    mediaPreview.value = MediaExtractor.analyze(trimmed)
                }
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

        // 2. Iniciar Foreground Service (que delega a DownloadManager -> UniversalDownloadEngine con yt-dlp + FFmpeg)
        try {
            MediaDownloadService.startDownload(getApplication(), trimmed, format)
        } catch (e: Exception) {
            android.util.Log.e("DownloadViewModel", "Error starting MediaDownloadService, fallback to direct enqueue", e)
            omniDownloadManager.enqueueDownload(trimmed, format)
        }

        urlInput.value = ""
        mediaPreview.value = null
    }

    fun cancelOmniDownload(taskId: String) {
        universalEngine.cancelDownload(taskId)
        omniDownloadManager.cancelDownload(taskId)
    }

    fun updateYoutubeDLEngine(onResult: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            val status = com.amurayada.music.MusicApplication.getInstance()?.updateYoutubeDLIfNeeded(force = true)
            onResult?.invoke(status != null)
        }
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
        universalEngine.cancelDownload("song_$songId")
    }
    
    fun deleteDownload(songId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val download = downloadDao.getDownload(songId)
                download?.localPath?.let { path ->
                    if (path.startsWith("content://")) {
                        try {
                            context.contentResolver.delete(Uri.parse(path), null, null)
                        } catch (_: Exception) {}
                    } else {
                        val file = File(path)
                        if (file.exists()) file.delete()
                    }
                }
                downloadDao.deleteDownloadById(songId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    fun isDownloaded(songId: Long): Flow<Boolean> = flow {
        emit(withContext(Dispatchers.IO) { downloadDao.isDownloaded(songId) })
    }
    
    fun getDownloadProgress(songId: Long): Flow<DownloadProgress?> {
        return _activeDownloads.map { list -> list.firstOrNull { it.songId == songId } }
    }
}
