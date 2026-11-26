package com.amurayada.music.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amurayada.music.data.database.DownloadDatabase
import com.amurayada.music.data.model.DownloadProgress
import com.amurayada.music.data.model.DownloadStatus as AppDownloadStatus
import com.amurayada.music.data.model.Song
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
    application: Application,
    private val preferencesRepository: com.amurayada.music.data.repository.PreferencesRepository =
        com.amurayada.music.data.repository.SharedPreferencesRepositoryImpl(application),
    private val downloadDao: com.amurayada.music.data.database.DownloadDao =
        DownloadDatabase.getDatabase(application).downloadDao(),
    private val downloadRepository: com.amurayada.music.data.repository.DownloadRepository =
        com.amurayada.music.data.repository.DownloadRepositoryImpl(application, downloadDao)
) : AndroidViewModel(application) {

    private val context = application
    private val universalEngine = UniversalDownloadEngine.getInstance(application)
    
    private val _downloadedSongs = MutableStateFlow<List<Song>>(emptyList())
    val downloadedSongs: StateFlow<List<Song>> = _downloadedSongs.asStateFlow()

    val downloadedAudioSongs: StateFlow<List<Song>> = _downloadedSongs
        .map { list ->
            list.filterNot { song ->
                val lower = song.path.lowercase()
                song.mimeType?.startsWith("video/", ignoreCase = true) == true ||
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
    
    init {
        observeDownloadedSongs()
        observeActiveDownloads()
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
                        if (!MediaDownloadService.startDownload(getApplication(), item.url, fmt, item.processId)) {
                            universalEngine.enqueueDownload(item.url, fmt, item.processId)
                        }
                    }
                }
            } catch (e: Exception) {
                com.omnivault.media.utils.LocalCrashLogger.logError("DownloadVM", "Error restaurando cola persistida", e)
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
                            albumId = entity.album.hashCode().toLong(),
                            mimeType = entity.mimeType ?: if (entity.isVideo) "video/mp4" else null,
                            fileSize = entity.fileSize
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
                            DownloadStatus.QUEUED, DownloadStatus.FETCHING_METADATA, DownloadStatus.PAUSED -> AppDownloadStatus.QUEUED
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
        preferencesRepository.setDownloadLocation(mode, uri)
        _showDownloadLocationDialog.value = false
        
        // Process pending
        if (pendingSongs.isNotEmpty()) {
            val songsToDownload = pendingSongs.toList()
            pendingSongs.clear()
            downloadAlbum(songsToDownload)
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
        val raw = song.path.trim()
        if (raw.isBlank() || raw.startsWith("/") || raw.startsWith("content://")) return
        val sourceUrl = if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
            raw
        } else {
            "https://music.youtube.com/watch?v=$raw"
        }
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

    fun startUniversalDownload(
        url: String,
        format: MediaFormat,
        forceDuplicate: Boolean = false,
        onDispatched: () -> Unit = {}
    ) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return

        viewModelScope.launch {
            if (!forceDuplicate && withContext(Dispatchers.IO) { universalEngine.isAlreadyDownloaded(trimmed, !format.isAudio) }) {
                duplicateWarning.value = trimmed to format
                return@launch
            }
            dispatchSingleDownload(trimmed, format)
            onDispatched()
        }
    }

    private fun dispatchSingleDownload(trimmed: String, format: MediaFormat) {
        lastQueuedUrl.value = trimmed
        com.omnivault.media.utils.NotificationHelper.createNotificationChannels(getApplication())

        if (!MediaDownloadService.startDownload(getApplication(), trimmed, format)) {
            universalEngine.enqueueDownload(trimmed, format)
        }

        urlInput.value = ""
        mediaPreview.value = null
        batchUrls.value = emptyList()
    }

    fun enqueueBatch(
        urls: List<String>,
        format: MediaFormat,
        skipDuplicates: Boolean = true,
        onDispatched: () -> Unit = {}
    ) {
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
                if (!MediaDownloadService.startDownload(getApplication(), u, format)) {
                    universalEngine.enqueueDownload(u, format)
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
            if (enqueued > 0) {
                onDispatched()
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
                if (!MediaDownloadService.startDownload(getApplication(), item.url, item.format)) {
                    universalEngine.enqueueDownload(item.url, item.format)
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
                withContext(Dispatchers.IO) { universalEngine.removePersistedQueueItem(item.processId) }
            }
        }
    }

    fun cancelOmniDownload(taskId: String) {
        universalEngine.cancelDownload(taskId)
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
    
    fun cancelDownload(songId: Long) {
        universalEngine.cancelDownload("song_$songId")
    }
    
    fun deleteDownload(songId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            downloadRepository.deleteDownload(songId)
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
