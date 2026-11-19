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
import com.omnivault.media.downloader.UniversalMediaMetadata
import com.omnivault.media.model.DownloadItem
import com.omnivault.media.model.DownloadStatus
import com.omnivault.media.model.MediaFormat
import com.omnivault.media.model.PlatformType
import com.omnivault.media.service.MediaDownloadService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    val downloadedAudioSongs: StateFlow<List<Song>> = _downloadedSongs
        .map { list ->
            list.filterNot { song ->
                val lower = song.path.lowercase()
                val mime = if (song.path.startsWith("content://")) {
                    try { context.contentResolver.getType(Uri.parse(song.path)) } catch (_: Exception) { null }
                } else null
                mime?.startsWith("video/", ignoreCase = true) == true ||
                    song.album.equals("Video", ignoreCase = true) ||
                    song.path.contains("/video/", ignoreCase = true) ||
                    lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    
    private val _activeDownloads = MutableStateFlow<List<DownloadProgress>>(emptyList())
    val activeDownloads: StateFlow<List<DownloadProgress>> = _activeDownloads.asStateFlow()

    // Estados en tiempo real (0f..1f) emitidos por UniversalDownloadEngine (youtubedl-android)
    val universalDownloadStates: StateFlow<Map<String, UniversalDownloadState>> = universalEngine.downloadsState

    // OmniVault Universal Engine State
    val urlInput = MutableStateFlow("")
    val selectedFormat = MutableStateFlow(MediaFormat.M4A)
    val mediaPreview = MutableStateFlow<MediaPreview?>(null)
    val isAnalyzing = MutableStateFlow(false)
    val analyzeTimeoutMessage = MutableStateFlow<String?>(null)
    val batchUrls = MutableStateFlow<List<String>>(emptyList())
    val hybridPlaylistPromptUrl = MutableStateFlow<String?>(null)
    val playlistEntries = MutableStateFlow<List<com.omnivault.media.downloader.PlaylistEntryPreview>>(emptyList())
    val isExtractingPlaylist = MutableStateFlow(false)
    val duplicateWarning = MutableStateFlow<Pair<String, MediaFormat>?>(null)
    val lastQueuedUrl = MutableStateFlow<String?>(null)
    val batchFeedbackMessage = MutableStateFlow<String?>(null)
    private var analyzeJob: Job? = null
    private var activeAnalyzeProcessId: String? = null
    private var omniListener: ((DownloadItem) -> Unit)? = null

    private val _omniActiveDownloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val omniActiveDownloads: StateFlow<List<DownloadItem>> = _omniActiveDownloads.asStateFlow()

    private val _omniHistory = MutableStateFlow<List<DownloadItem>>(emptyList())
    val omniHistory: StateFlow<List<DownloadItem>> = _omniHistory.asStateFlow()
    
    init {
        observeDownloadedSongs()
        observeActiveDownloads()
        initOmniVaultListener()
        restorePersistedQueue()
    }

    private fun restorePersistedQueue() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val toAutoResume = universalEngine.restorePersistedQueueOnStartup()
                if (toAutoResume.isNotEmpty()) {
                    com.omnivault.media.utils.NotificationHelper.createNotificationChannels(getApplication())
                    toAutoResume.forEach { item ->
                        val fmt = MediaFormat.entries.firstOrNull { it.name == item.formatName } ?: MediaFormat.MP3_320
                        try {
                            MediaDownloadService.startDownload(getApplication(), item.url, fmt)
                        } catch (_: Exception) {
                            omniDownloadManager.enqueueDownload(item.url, fmt)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun initOmniVaultListener() {
        _omniActiveDownloads.value = omniDownloadManager.getActiveDownloads()
        _omniHistory.value = omniDownloadManager.getHistory()

        val listener: (DownloadItem) -> Unit = {
            viewModelScope.launch(Dispatchers.Main) {
                _omniActiveDownloads.value = omniDownloadManager.getActiveDownloads()
                _omniHistory.value = omniDownloadManager.getHistory()
            }
        }
        omniListener = listener
        omniDownloadManager.addDownloadListener(listener)
    }

    override fun onCleared() {
        omniListener?.let { omniDownloadManager.removeDownloadListener(it) }
        super.onCleared()
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

    fun abortAnalysis() {
        analyzeJob?.cancel()
        analyzeJob = null
        activeAnalyzeProcessId?.let { universalEngine.cancelAnalysisProcess(it) }
        activeAnalyzeProcessId = null
        isAnalyzing.value = false
        analyzeTimeoutMessage.value = null
    }

    fun analyzeUrl(
        url: String,
        forceSingleVideo: Boolean = false,
        forcePlaylist: Boolean = false
    ) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return

        analyzeJob?.cancel()
        activeAnalyzeProcessId?.let { universalEngine.cancelAnalysisProcess(it) }
        activeAnalyzeProcessId = null
        analyzeTimeoutMessage.value = null
        hybridPlaylistPromptUrl.value = null

        val allExtracted = com.omnivault.media.utils.UrlUtils.extractAllUrlsFromText(trimmed)
        if (allExtracted.size > 1) {
            batchUrls.value = allExtracted
            mediaPreview.value = null
            isAnalyzing.value = false
            return
        }
        batchUrls.value = emptyList()

        val singleUrl = allExtracted.firstOrNull() ?: trimmed

        if (!forceSingleVideo && !forcePlaylist && com.omnivault.media.utils.UrlUtils.isHybridPlaylistUrl(singleUrl)) {
            hybridPlaylistPromptUrl.value = singleUrl
            isAnalyzing.value = false
            return
        }

        if (forcePlaylist || com.omnivault.media.utils.UrlUtils.isDirectPlaylistUrl(singleUrl)) {
            extractPlaylist(singleUrl)
            return
        }

        val targetUrl = if (forceSingleVideo) {
            com.omnivault.media.utils.UrlUtils.normalizeMediaUrl(singleUrl)
        } else {
            singleUrl
        }

        // Respuesta instantánea desde caché en memoria si ya fue analizado recientemente
        universalEngine.getCachedMetadata(targetUrl)?.let { cached ->
            val durStr = if (cached.durationMs > 0) {
                com.amurayada.music.ui.screens.nowplaying.formatDuration(cached.durationMs)
            } else ""
            mediaPreview.value = MediaPreview(
                title = cached.title,
                author = cached.artist,
                thumbnailUrl = cached.thumbnailUrl,
                durationText = durStr,
                platform = PlatformType.fromUrl(targetUrl),
                originalUrl = cached.sourceUrl
            )
            isAnalyzing.value = false
            return
        }

        val processId = "analyze_${System.currentTimeMillis()}"
        activeAnalyzeProcessId = processId

        analyzeJob = viewModelScope.launch {
            isAnalyzing.value = true
            try {
                val platform = PlatformType.fromUrl(targetUrl)
                val fastPlatforms = setOf(
                    PlatformType.YOUTUBE,
                    PlatformType.TIKTOK,
                    PlatformType.INSTAGRAM,
                    PlatformType.FACEBOOK,
                    PlatformType.TWITTER
                )

                var extractedDurationMs = 0L
                val preview = withTimeoutOrNull(4_000L) {
                    if (platform in fastPlatforms) {
                        val fastPreview = try {
                            MediaExtractor.analyze(targetUrl)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (_: Exception) {
                            null
                        }

                        val isMeaningfulFastPreview = fastPreview != null &&
                            fastPreview.title.isNotBlank() &&
                            (!fastPreview.title.startsWith("Enlace de ") || fastPreview.thumbnailUrl.isNotBlank())

                        if (isMeaningfulFastPreview) {
                            fastPreview
                        } else {
                            val meta = universalEngine.extractMetadata(targetUrl, selectedFormat.value, processId)
                            extractedDurationMs = meta.durationMs
                            val durStr = if (meta.durationMs > 0) {
                                com.amurayada.music.ui.screens.nowplaying.formatDuration(meta.durationMs)
                            } else ""
                            MediaPreview(
                                title = meta.title,
                                author = meta.artist,
                                thumbnailUrl = meta.thumbnailUrl,
                                durationText = durStr,
                                platform = platform,
                                originalUrl = meta.sourceUrl
                            )
                        }
                    } else {
                        val meta = universalEngine.extractMetadata(targetUrl, selectedFormat.value, processId)
                        extractedDurationMs = meta.durationMs
                        val durStr = if (meta.durationMs > 0) {
                            com.amurayada.music.ui.screens.nowplaying.formatDuration(meta.durationMs)
                        } else ""
                        MediaPreview(
                            title = meta.title,
                            author = meta.artist,
                            thumbnailUrl = meta.thumbnailUrl,
                            durationText = durStr,
                            platform = platform,
                            originalUrl = meta.sourceUrl
                        )
                    }
                }

                if (preview != null) {
                    universalEngine.cacheMetadata(
                        targetUrl,
                        UniversalMediaMetadata(
                            title = preview.title,
                            artist = preview.author.ifBlank { "OmniVault" },
                            album = preview.author.ifBlank { "OmniVault" },
                            durationMs = extractedDurationMs,
                            thumbnailUrl = preview.thumbnailUrl,
                            sourceUrl = preview.originalUrl.ifBlank { targetUrl }
                        )
                    )
                    mediaPreview.value = preview
                    analyzeTimeoutMessage.value = null
                } else {
                    universalEngine.cancelAnalysisProcess(processId)
                    analyzeTimeoutMessage.value = "El análisis tardó más de 4s. Puedes reintentar, abortar o descargar directo."
                }
            } catch (_: CancellationException) {
                universalEngine.cancelAnalysisProcess(processId)
            } catch (e: Exception) {
                e.printStackTrace()
                analyzeTimeoutMessage.value = "No se pudo obtener la vista previa a tiempo. Puedes descargar directo."
            } finally {
                if (activeAnalyzeProcessId == processId) {
                    activeAnalyzeProcessId = null
                }
                isAnalyzing.value = false
            }
        }
    }

    private fun extractPlaylist(playlistUrl: String) {
        analyzeJob?.cancel()
        analyzeJob = viewModelScope.launch {
            isExtractingPlaylist.value = true
            isAnalyzing.value = true
            analyzeTimeoutMessage.value = null
            try {
                val entries = withTimeoutOrNull(20_000L) {
                    universalEngine.extractPlaylistEntries(playlistUrl)
                } ?: emptyList()

                if (entries.isNotEmpty()) {
                    playlistEntries.value = entries
                } else {
                    analyzeTimeoutMessage.value = "No se pudieron extraer los elementos de la playlist. Intenta de nuevo."
                }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                analyzeTimeoutMessage.value = "Error leyendo playlist: ${e.localizedMessage ?: "revisa tu conexión"}"
            } finally {
                isExtractingPlaylist.value = false
                isAnalyzing.value = false
            }
        }
    }

    fun dismissPlaylistSheet() {
        playlistEntries.value = emptyList()
    }

    fun startUniversalDownload(url: String, format: MediaFormat, forceDuplicate: Boolean = false) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return

        viewModelScope.launch {
            if (!forceDuplicate && withContext(Dispatchers.IO) { universalEngine.isAlreadyDownloaded(trimmed, !format.isAudio) }) {
                duplicateWarning.value = trimmed to format
                return@launch
            }
            dispatchSingleDownload(trimmed, format)
        }
    }

    private fun dispatchSingleDownload(trimmed: String, format: MediaFormat) {
        lastQueuedUrl.value = trimmed
        com.omnivault.media.utils.NotificationHelper.createNotificationChannels(getApplication())

        try {
            MediaDownloadService.startDownload(getApplication(), trimmed, format)
        } catch (e: Exception) {
            android.util.Log.e("DownloadViewModel", "Error starting MediaDownloadService, fallback to direct enqueue", e)
            omniDownloadManager.enqueueDownload(trimmed, format)
        }

        urlInput.value = ""
        mediaPreview.value = null
        batchUrls.value = emptyList()
    }

    fun enqueueBatch(urls: List<String>, format: MediaFormat, skipDuplicates: Boolean = true) {
        val unique = urls.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (unique.isEmpty()) return

        viewModelScope.launch {
            com.omnivault.media.utils.NotificationHelper.createNotificationChannels(getApplication())
            var enqueued = 0
            var skipped = 0
            for (u in unique) {
                val isDup = if (skipDuplicates) {
                    withContext(Dispatchers.IO) { universalEngine.isAlreadyDownloaded(u, !format.isAudio) }
                } else false
                if (isDup) {
                    skipped++
                    continue
                }
                try {
                    MediaDownloadService.startDownload(getApplication(), u, format)
                } catch (_: Exception) {
                    omniDownloadManager.enqueueDownload(u, format)
                }
                enqueued++
            }
            urlInput.value = ""
            mediaPreview.value = null
            batchUrls.value = emptyList()
            playlistEntries.value = emptyList()
            batchFeedbackMessage.value = buildString {
                append("Encoladas $enqueued descargas")
                if (skipped > 0) append(" ($skipped duplicadas omitidas)")
            }
        }
    }

    fun retryFailedAndPaused() {
        viewModelScope.launch {
            val targets = universalEngine.downloadsState.value.values.filter {
                it.status == DownloadStatus.ERROR || it.status == DownloadStatus.PAUSED
            }
            if (targets.isEmpty()) return@launch
            com.omnivault.media.utils.NotificationHelper.createNotificationChannels(getApplication())
            for (item in targets) {
                withContext(Dispatchers.IO) { universalEngine.removePersistedQueueItem(item.processId) }
                try {
                    MediaDownloadService.startDownload(getApplication(), item.url, item.format)
                } catch (_: Exception) {
                    omniDownloadManager.enqueueDownload(item.url, item.format)
                }
            }
        }
    }

    fun cancelAllDownloads() {
        viewModelScope.launch {
            val allPending = universalEngine.downloadsState.value.values.filter {
                it.status != DownloadStatus.COMPLETED
            }
            for (item in allPending) {
                universalEngine.cancelDownload(item.processId)
                omniDownloadManager.cancelDownload(item.processId)
                withContext(Dispatchers.IO) { universalEngine.removePersistedQueueItem(item.processId) }
            }
        }
    }

    fun cancelOmniDownload(taskId: String) {
        universalEngine.cancelDownload(taskId)
        omniDownloadManager.cancelDownload(taskId)
        viewModelScope.launch(Dispatchers.IO) {
            universalEngine.removePersistedQueueItem(taskId)
        }
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

    /**
     * Limpia únicamente el historial de descargas completadas en la base de datos local
     * sin eliminar los archivos multimedia guardados en el dispositivo.
     */
    fun clearCompletedHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val all = downloadDao.getAllDownloads().first()
                all.forEach { downloadDao.deleteDownloadById(it.songId) }
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
