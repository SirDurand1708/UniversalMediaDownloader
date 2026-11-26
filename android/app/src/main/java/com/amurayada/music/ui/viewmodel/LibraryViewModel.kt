package com.amurayada.music.ui.viewmodel

import android.app.Application
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amurayada.music.data.model.Album
import com.amurayada.music.data.model.Artist
import com.amurayada.music.data.model.Song
import com.amurayada.music.data.model.SongSort
import com.amurayada.music.data.repository.MediaRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext

class LibraryViewModel(
    application: Application,
    private val preferencesRepository: com.amurayada.music.data.repository.PreferencesRepository =
        com.amurayada.music.data.repository.SharedPreferencesRepositoryImpl(application),
    private val repository: MediaRepository = MediaRepository(application),
    private val searchRepository: com.amurayada.music.data.repository.SearchRepository =
        com.amurayada.music.data.repository.SearchRepositoryImpl(application.applicationContext)
) : AndroidViewModel(application) {
    
    // Download Repository for integrating downloaded songs
    private val downloadDatabase = com.amurayada.music.data.database.DownloadDatabase.getDatabase(application)
    private val streamRepository = com.amurayada.music.data.repository.StreamRepositoryImpl()
    private val downloadRepository = com.amurayada.music.data.repository.DownloadRepositoryImpl(
        application,
        downloadDatabase.downloadDao(),
        streamRepository
    )
    
    private var debounceJob: kotlinx.coroutines.Job? = null
    
    private val _permissionEvent = androidx.lifecycle.MutableLiveData<androidx.activity.result.IntentSenderRequest?>()
    val permissionEvent: androidx.lifecycle.LiveData<androidx.activity.result.IntentSenderRequest?> = _permissionEvent
    
    private val contentObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
            super.onChange(selfChange, uri)
            android.util.Log.d("LibraryViewModel", "ContentObserver.onChange triggered! uri=$uri")
            triggerLibraryUpdate()
        }
        
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            android.util.Log.d("LibraryViewModel", "ContentObserver.onChange (no uri) triggered!")
            triggerLibraryUpdate()
        }
    }
    
    private fun triggerLibraryUpdate() {
        android.util.Log.d("LibraryViewModel", "triggerLibraryUpdate called, debouncing...")
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            // Debounce corto de 500ms para agrupar operaciones rápidas sin perder respuesta en tiempo real
            kotlinx.coroutines.delay(500)
            loadLibrary()
        }
    }

    init {
        // Observe downloads database changes to auto-refresh library
        viewModelScope.launch {
            downloadRepository.observeDownloadedSongs()
                .drop(1)
                .collect { songs ->
                    android.util.Log.d("LibraryViewModel", "Downloads changed (size: ${songs.size}), refreshing library...")
                    loadLibrary()
                }
        }
    }

    private fun mergeSongsDeduplicated(
        localSongs: List<Song>,
        downloadedSongs: List<Song>
    ): List<Song> {
        val resolver = getApplication<Application>().contentResolver
        return com.omnivault.media.utils.LibraryExclusionHelper.mergeSongsDeduplicated(
            localSongs = localSongs,
            downloadedSongs = downloadedSongs,
            keySelector = { s -> com.omnivault.media.utils.SongKey(s.id, s.title, s.path, s.fileSize) },
            contentUriResolver = { rawPath ->
                var resolvedDataPath: String? = null
                var resolvedDisplayName: String? = null
                try {
                    resolver.query(
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
                resolvedDataPath to resolvedDisplayName
            }
        )
    }

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().contentResolver.unregisterContentObserver(contentObserver)
    }
    
    var songs by mutableStateOf<List<Song>>(emptyList())
        private set
    
    var albums by mutableStateOf<List<Album>>(emptyList())
        private set
    
    var artists by mutableStateOf<List<Artist>>(emptyList())
        private set
    
    var genres by mutableStateOf<List<com.amurayada.music.data.model.Genre>>(emptyList())
        private set
        
    var userPlaylists by mutableStateOf<List<com.amurayada.music.data.model.Playlist>>(emptyList())
        private set

    var songSortOrder by mutableStateOf(
        SongSort.fromKey(preferencesRepository.getSongSortOrder(SongSort.RECENT.name))
    )
        private set

    fun setSongSort(sort: SongSort) {
        songSortOrder = sort
        preferencesRepository.setSongSortOrder(sort.name)
    }

    var hiddenSongIds by mutableStateOf<Set<Long>>(preferencesRepository.getHiddenSongIds())
        private set

    var deletedSongPaths by mutableStateOf<Set<String>>(preferencesRepository.getDeletedSongPaths())
        private set

    fun hideSong(songId: Long) {
        synchronized(com.omnivault.media.downloader.UniversalDownloadEngine.EXCLUSION_PREFS_LOCK) {
            val updated = hiddenSongIds + songId
            hiddenSongIds = updated
            preferencesRepository.setHiddenSongIds(updated)
        }
        loadLibrary()
    }

    var hiddenSongs by mutableStateOf<List<Song>>(emptyList())
        private set

    var lrcKeys by mutableStateOf<Set<String>>(emptySet())
        private set

    private var _filterShortAudio by mutableStateOf(preferencesRepository.getFilterShortAudio())
    val filterShortAudio: Boolean get() = _filterShortAudio

    fun setFilterShortAudio(enabled: Boolean) {
        _filterShortAudio = enabled
        preferencesRepository.setFilterShortAudio(enabled)
        loadLibrary()
    }

    private var _filterCallRecordings by mutableStateOf(
        preferencesRepository.getFilterCallRecordings(defaultValue = true)
    )
    val filterCallRecordings: Boolean get() = _filterCallRecordings

    fun setFilterCallRecordings(enabled: Boolean) {
        _filterCallRecordings = enabled
        preferencesRepository.setFilterCallRecordings(enabled)
        loadLibrary()
    }

    private var loadJob: kotlinx.coroutines.Job? = null
    private data class PendingTagEdit(
        val song: Song,
        val title: String,
        val artist: String,
        val album: String,
        val onResult: (Boolean, String) -> Unit
    )
    private var pendingTagEdit: PendingTagEdit? = null

    fun unhideSong(songId: Long) {
        synchronized(com.omnivault.media.downloader.UniversalDownloadEngine.EXCLUSION_PREFS_LOCK) {
            val updated = hiddenSongIds - songId
            hiddenSongIds = updated
            preferencesRepository.setHiddenSongIds(updated)
        }
        loadLibrary()
    }

    fun unhideAllSongs() {
        synchronized(com.omnivault.media.downloader.UniversalDownloadEngine.EXCLUSION_PREFS_LOCK) {
            val keepDeletedIds = deletedSongPaths.let { delPaths ->
                val normDel = delPaths.mapTo(HashSet()) { it.lowercase() }
                hiddenSongs.filter { normDel.contains(it.path.lowercase()) }.map { it.id }.toSet()
            }
            hiddenSongIds = keepDeletedIds
            preferencesRepository.setHiddenSongIds(keepDeletedIds)
        }
        loadLibrary()
    }

    fun updateSongMetadata(
        context: android.content.Context,
        song: Song,
        newTitle: String,
        newArtist: String,
        newAlbum: String,
        onResult: (Boolean, String) -> Unit
    ) {
        val cleanTitle = newTitle.trim().ifBlank { song.title }
        val cleanArtist = newArtist.trim().ifBlank { song.artist }
        val cleanAlbum = newAlbum.trim().ifBlank { song.album }

        viewModelScope.launch {
            try {
                val resolvedUri = if (song.path.startsWith("content://")) {
                    android.net.Uri.parse(song.path)
                } else {
                    withContext(Dispatchers.IO) {
                        resolveMediaStoreAudioUri(context, song)
                    }
                }

                val repoResult = repository.updateSongTags(song, resolvedUri, cleanTitle, cleanArtist, cleanAlbum)
                withContext(Dispatchers.IO) {
                    val dao = com.amurayada.music.data.database.DownloadDatabase.getDatabase(context).downloadDao()
                    dao.updateDownloadMetadata(song.id, cleanTitle, cleanArtist, cleanAlbum)
                    if (song.path.isNotBlank()) {
                        dao.updateDownloadMetadataByPath(song.path, cleanTitle, cleanArtist, cleanAlbum)
                    }
                }
                pendingTagEdit = null
                loadLibrary()
                if (repoResult.isSuccess) {
                    onResult(true, "Etiquetas actualizadas")
                } else {
                    onResult(true, "Etiquetas actualizadas en biblioteca")
                }
            } catch (req: MediaRepository.RequiresPermissionException) {
                pendingTagEdit = PendingTagEdit(song, cleanTitle, cleanArtist, cleanAlbum, onResult)
                _permissionEvent.value = androidx.activity.result.IntentSenderRequest.Builder(req.intentSender).build()
            } catch (e: Exception) {
                onResult(false, e.localizedMessage ?: "No se pudieron editar las etiquetas")
            }
        }
    }

    fun retryPendingTagEdit(context: android.content.Context) {
        val pending = pendingTagEdit ?: return
        pendingTagEdit = null
        updateSongMetadata(context, pending.song, pending.title, pending.artist, pending.album, pending.onResult)
    }

    private fun markSongDeleted(song: Song) {
        synchronized(com.omnivault.media.downloader.UniversalDownloadEngine.EXCLUSION_PREFS_LOCK) {
            val updatedIds = hiddenSongIds + song.id
            hiddenSongIds = updatedIds
            val updatedPaths = if (song.path.isNotBlank()) deletedSongPaths + song.path else deletedSongPaths
            deletedSongPaths = updatedPaths
            preferencesRepository.setHiddenSongIds(updatedIds)
            preferencesRepository.setDeletedSongPaths(updatedPaths)
        }
    }

    fun clearPermissionEvent() {
        _permissionEvent.value = null
    }

    private fun resolveMediaStoreAudioUri(context: android.content.Context, song: Song): android.net.Uri {
        if (song.path.isNotBlank()) {
            try {
                context.contentResolver.query(
                    android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(android.provider.MediaStore.Audio.Media._ID),
                    "${android.provider.MediaStore.Audio.Media.DATA} = ?",
                    arrayOf(song.path),
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idCol = cursor.getColumnIndex(android.provider.MediaStore.Audio.Media._ID)
                        if (idCol != -1) {
                            val mediaId = cursor.getLong(idCol)
                            return android.content.ContentUris.withAppendedId(
                                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                mediaId
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return android.content.ContentUris.withAppendedId(
            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            song.id
        )
    }

    fun deleteSong(song: Song, context: android.content.Context, onComplete: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. Intentar eliminar primero de MediaStore y almacenamiento físico
                val mediaUri = resolveMediaStoreAudioUri(context, song)
                var securityException: SecurityException? = null
                var deletedRows = 0

                try {
                    deletedRows = context.contentResolver.delete(mediaUri, null, null)
                } catch (se: SecurityException) {
                    securityException = se
                    android.util.Log.w("LibraryViewModel", "SecurityException eliminando URI $mediaUri: ${se.message}")
                } catch (e: Exception) {
                    android.util.Log.w("LibraryViewModel", "Error eliminando de MediaStore por URI: ${e.message}")
                }

                val isContentUri = song.path.startsWith("content://")
                var fileDeleted = deletedRows > 0
                if (song.path.isNotBlank() && !isContentUri) {
                    if (!fileDeleted) {
                        try {
                            deletedRows = context.contentResolver.delete(
                                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                "${android.provider.MediaStore.Audio.Media.DATA} = ?",
                                arrayOf(song.path)
                            )
                            fileDeleted = deletedRows > 0
                        } catch (se: SecurityException) {
                            if (securityException == null) securityException = se
                        } catch (e: Exception) {
                            android.util.Log.w("LibraryViewModel", "Error eliminando de MediaStore por DATA: ${e.message}")
                        }
                    }

                    val file = java.io.File(song.path)
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
                    java.io.File(song.path).exists()
                } else {
                    !fileDeleted
                }

                var requestedSystemPermission = false
                if (stillExists && securityException != null) {
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                            val pendingIntent = android.provider.MediaStore.createDeleteRequest(
                                context.contentResolver,
                                listOf(mediaUri)
                            )
                            requestedSystemPermission = true
                            _permissionEvent.postValue(
                                androidx.activity.result.IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                            )
                        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
                            securityException is android.app.RecoverableSecurityException
                        ) {
                            requestedSystemPermission = true
                            _permissionEvent.postValue(
                                androidx.activity.result.IntentSenderRequest.Builder(
                                    securityException.userAction.actionIntent.intentSender
                                ).build()
                            )
                        }
                    } catch (reqEx: Exception) {
                        android.util.Log.w("LibraryViewModel", "No se pudo emitir solicitud de eliminación: ${reqEx.message}")
                    }
                }

                if (requestedSystemPermission) {
                    // Esperar confirmación del sistema; no destruir BD/archivos auxiliares si el usuario cancela
                    return@launch
                }

                // 2. Solo tras confirmar eliminación física (o inexistencia real), limpiar BD, caché y estado UI
                markSongDeleted(song)
                withContext(Dispatchers.Main) {
                    songs = songs.filter { it.id != song.id && (song.path.isBlank() || it.path != song.path) }
                }

                try {
                    downloadDatabase.downloadDao().deleteDownloadById(song.id)
                    if (song.path.isNotBlank()) {
                        downloadDatabase.downloadDao().deleteDownloadByPath(song.path)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("LibraryViewModel", "Error eliminando de DownloadDatabase: ${e.message}")
                }

                if (song.path.isNotBlank()) {
                    if (!isContentUri) {
                        try {
                            val audioFile = java.io.File(song.path)
                            val parentDir = audioFile.parentFile
                            if (parentDir != null && parentDir.exists()) {
                                val baseName = audioFile.nameWithoutExtension
                                java.io.File(parentDir, "$baseName.lrc").takeIf { it.exists() }?.delete()
                                java.io.File(parentDir, "$baseName.jpg").takeIf { it.exists() }?.delete()
                            }
                        } catch (_: Exception) {}
                        android.media.MediaScannerConnection.scanFile(context, arrayOf(song.path), null, null)
                    }
                }

                // 5. Recargar biblioteca local y notificar término
                loadLibrary()
                withContext(Dispatchers.Main) {
                    onComplete()
                }
            } catch (e: Exception) {
                android.util.Log.e("LibraryViewModel", "Error eliminando canción: ${e.message}")
                com.omnivault.media.utils.LocalCrashLogger.logError("LibraryVM", "Error eliminando canción ${song.title}", e)
                withContext(Dispatchers.Main) {
                    onComplete()
                }
            }
        }
    }

    var isLoading by mutableStateOf(false)
        private set
    
    var searchQuery by mutableStateOf("")
        private set
    
    val filteredSongs: List<Song> by derivedStateOf {
        val normDeletedPaths = deletedSongPaths.mapTo(HashSet()) { it.lowercase() }
        val visibleSongs = songs.filter {
            !hiddenSongIds.contains(it.id) && (it.path.isBlank() || !normDeletedPaths.contains(it.path.lowercase()))
        }
        if (searchQuery.isEmpty()) {
            visibleSongs
        } else {
            visibleSongs.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                it.artist.contains(searchQuery, ignoreCase = true) ||
                it.album.contains(searchQuery, ignoreCase = true)
            }
        }
    }
    
    val filteredAlbums: List<Album> by derivedStateOf {
        if (searchQuery.isEmpty()) {
            albums
        } else {
            albums.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                it.artist.contains(searchQuery, ignoreCase = true)
            }
        }
    }
    
    val filteredArtists: List<Artist> by derivedStateOf {
        if (searchQuery.isEmpty()) {
            artists
        } else {
            artists.filter {
                it.name.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    val filteredGenres: List<com.amurayada.music.data.model.Genre> by derivedStateOf {
        if (searchQuery.isEmpty()) {
            genres
        } else {
            genres.filter {
                it.name.contains(searchQuery, ignoreCase = true)
            }
        }
    }
        
    val recentlyAddedSongs: List<Song> by derivedStateOf {
        songs.sortedByDescending { it.dateAdded }.take(20)
    }

    val timeCapsuleSongs: List<Song> by derivedStateOf {
        // Songs added more than 1 year ago (approx 365 days)
        // Ensure we filter out songs with 0 or invalid dates (downloaded songs might have 0)
        val validSongs = songs.filter { it.dateAdded > 1000000 } // Basic check: > 1970
        
        val oneYearAgo = System.currentTimeMillis() / 1000 - (365 * 24 * 60 * 60)
        val oldSongs = validSongs.filter { it.dateAdded < oneYearAgo }
        
        if (oldSongs.size >= 10) {
            oldSongs.shuffled().take(20)
        } else {
            // Fallback to just the oldest 20 songs if we don't have enough "old" songs
            validSongs.sortedBy { it.dateAdded }.take(20).shuffled()
        }
    }
    
    var libraryVersion by mutableStateOf(System.currentTimeMillis())
        private set

    fun loadLibrary() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            isLoading = true
            try {
                android.util.Log.d("LibraryViewModel", "loadLibrary() starting...")
                
                // Move heavy calculation to IO dispatcher
                val result = withContext(Dispatchers.IO) {
                    // Clean up "ghost" downloads (files deleted externally)
                    downloadRepository.validateDownloads()
                    
                    val localSongs = repository.getAllSongs()
                    
                    // Get downloaded songs and combine with local
                    val downloadedSongs = downloadRepository.getDownloadedSongs()
                    android.util.Log.d("LibraryViewModel", "Found ${downloadedSongs.size} downloaded songs")
                    
                    // Refrescar listas de exclusión desde SharedPreferences y desbloquear descargas re-descargadas
                    val (freshHiddenIds, freshDeletedPaths) = synchronized(
                        com.omnivault.media.downloader.UniversalDownloadEngine.EXCLUSION_PREFS_LOCK
                    ) {
                        val hIds = preferencesRepository.getHiddenSongIds()
                        val dPaths = preferencesRepository.getDeletedSongPaths()

                        val mergedPreview = mergeSongsDeduplicated(localSongs, downloadedSongs)
                        val reconciled = com.omnivault.media.utils.LibraryExclusionHelper.reconcileRedownloadedExclusions(
                            mergedSongs = mergedPreview.map { com.omnivault.media.utils.SongKey(it.id, it.title, it.path, it.fileSize) },
                            downloadedSongs = downloadedSongs.map { com.omnivault.media.utils.SongKey(it.id, it.title, it.path, it.fileSize) },
                            deletedPaths = dPaths,
                            hiddenIds = hIds
                        )
                        if (reconciled.changed) {
                            preferencesRepository.setHiddenSongIds(reconciled.cleanedHiddenIds)
                            preferencesRepository.setDeletedSongPaths(reconciled.cleanedDeletedPaths)
                        }
                        reconciled.cleanedHiddenIds to reconciled.cleanedDeletedPaths
                    }
                    withContext(Dispatchers.Main) {
                        hiddenSongIds = freshHiddenIds
                        deletedSongPaths = freshDeletedPaths
                    }
                    val excludedIds = freshHiddenIds
                    val excludedPathsNorm = freshDeletedPaths.mapTo(HashSet()) { it.lowercase() }
                    val mergedAll = mergeSongsDeduplicated(localSongs, downloadedSongs)
                    val hiddenFound = mergedAll.filter {
                        excludedIds.contains(it.id) && (it.path.isBlank() || !excludedPathsNorm.contains(it.path.lowercase()))
                    }
                    val allSongs = mergedAll.filter {
                        !excludedIds.contains(it.id) &&
                            (it.path.isBlank() || !excludedPathsNorm.contains(it.path.lowercase())) &&
                            (!_filterCallRecordings || !com.omnivault.media.utils.LibraryExclusionHelper.isCallOrVoiceRecording(
                                path = it.path,
                                title = it.title,
                                album = it.album,
                                artist = it.artist
                            ))
                    }
                    val sortedSongs = allSongs.sortedBy { it.title }

                    // Pre-indexar archivos .lrc en IO (O(1) para UI Thread y compatible con content://)
                    val lrcFound = HashSet<String>()
                    try {
                        val dirsToScan = linkedSetOf<java.io.File>()
                        dirsToScan.add(com.omnivault.media.utils.StorageHelper.getOutputDirectory(true, getApplication()))
                        allSongs.forEach { s ->
                            if (s.path.startsWith("/")) {
                                java.io.File(s.path).parentFile?.let { dirsToScan.add(it) }
                            }
                        }
                        dirsToScan.forEach { dir ->
                            if (dir.isDirectory) {
                                dir.listFiles { f -> f.isFile && f.extension.equals("lrc", ignoreCase = true) && f.length() > 0L }
                                    ?.forEach { lrcFile ->
                                        lrcFound.add(lrcFile.absolutePath.substringBeforeLast("."))
                                        lrcFound.add(lrcFile.nameWithoutExtension.lowercase())
                                    }
                            }
                        }
                    } catch (_: Exception) {}

                    // Merge Albums: Start with MediaStore albums, add downloaded albums
                    val mediaStoreAlbums = repository.getAllAlbums()
                    val downloadedAlbums = downloadedSongs.map { song ->
                        Album(
                            id = song.albumId,
                            name = song.album ?: "Unknown Album",
                            artist = song.artist,
                            artworkUri = song.albumArtUri,
                            songCount = 1, // Will be corrected by grouping
                            year = 0 // Metadata might be missing
                        )
                    }.distinctBy { it.id }
                    
                    // Combine albums, favoring MediaStore versions, filtering out 1-song singles and generic placeholders
                    val genericAlbumNames = setOf(
                        "<unknown>", "unknown", "unknown album", "álbum desconocido",
                        "omnivault", "descargas", "download", "downloads",
                        "music", "música", "musica", "whatsapp audio", "snaptube audio", "sencillos"
                    )
                    val allAlbums = (mediaStoreAlbums + downloadedAlbums).distinctBy { it.id }
                    // Recalculate song counts correctly based on FULL song list in O(N) linear time
                    val songCountByAlbum = sortedSongs.groupingBy { it.albumId }.eachCount()
                    val finalAlbums = allAlbums.mapNotNull { album ->
                        val count = songCountByAlbum[album.id] ?: 0
                        val normName = album.name.trim().lowercase()
                        if (count >= 2 && normName.isNotEmpty() && !genericAlbumNames.contains(normName)) {
                            val cleanArtist = if (album.artist.trim().lowercase() in setOf("<unknown>", "unknown", "unknown artist", "")) {
                                "Varios artistas"
                            } else album.artist
                            album.copy(songCount = count, artist = cleanArtist)
                        } else null
                    }.sortedBy { it.name.lowercase() }

                    // Merge Artists: normalize <unknown> and keep artists with >= 2 songs (avoids 1-track uploader clutter)
                    val mediaStoreArtists = repository.getAllArtists()
                    val normalizeArtistName: (String) -> String = { raw ->
                        val trimmed = raw.trim()
                        val lower = trimmed.lowercase()
                        if (trimmed.isEmpty() || lower == "<unknown>" || lower == "unknown" || lower == "unknown artist") {
                            "Artista desconocido"
                        } else trimmed
                    }
                    val knownArtistNames = mediaStoreArtists.associate { normalizeArtistName(it.name).lowercase() to it.id }

                    val downloadedArtists = downloadedSongs.map { song ->
                        val cleanName = normalizeArtistName(song.artist)
                        val existingId = knownArtistNames[cleanName.lowercase()]
                        Artist(
                            id = existingId ?: cleanName.lowercase().hashCode().toLong(),
                            name = cleanName,
                            albumCount = 0,
                            songCount = 0
                        )
                    }.distinctBy { it.id }

                    val allArtists = (mediaStoreArtists.map { it.copy(name = normalizeArtistName(it.name)) } + downloadedArtists)
                        .distinctBy { it.name.lowercase() }
                    val songCountByArtist = sortedSongs.groupingBy { normalizeArtistName(it.artist).lowercase() }.eachCount()
                    val albumCountByArtist = finalAlbums.groupingBy { normalizeArtistName(it.artist).lowercase() }.eachCount()

                    val finalArtists = allArtists.mapNotNull { artist ->
                        val key = artist.name.lowercase().trim()
                        val tracksCount = songCountByArtist[key] ?: 0
                        if (tracksCount >= 2 && key != "artista desconocido" && key != "omnivault") {
                            val albumsCount = albumCountByArtist[key] ?: 0
                            artist.copy(songCount = tracksCount, albumCount = albumsCount)
                        } else null
                    }.sortedBy { it.name.lowercase() }

                    val finalGenres = repository.getAllGenres()
                    
                    // Return result as a data class
                    LibraryData(
                        songs = sortedSongs,
                        albums = finalAlbums,
                        artists = finalArtists,
                        genres = finalGenres,
                        playlists = emptyList(), // Don't block for playlists
                        hiddenSongs = hiddenFound,
                        lrcKeys = lrcFound
                    )
                }
                
                // Update State on Main Thread
                songs = result.songs
                albums = result.albums
                artists = result.artists
                genres = result.genres
                hiddenSongs = result.hiddenSongs
                lrcKeys = result.lrcKeys
                
                // Update version to force UI refresh (especially images)
                libraryVersion = System.currentTimeMillis()
                
                android.util.Log.d("LibraryViewModel", "loadLibrary() completed: ${songs.size} songs, ${albums.size} albums")
                
                // NOW fetch online playlists asynchronously
                fetchOnlinePlaylists()

            } finally {
                isLoading = false
            }
        }
    }
    
    private fun fetchOnlinePlaylists() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                 if (com.amurayada.music.data.auth.YouTubeAuthManager.getInstance(getApplication()).isLoggedIn()) {
                     val result = searchRepository.getUserPlaylists()
                     if (result.isNotEmpty()) {
                         android.util.Log.d("LibraryViewModel", "Loaded ${result.size} online playlists")
                         withContext(Dispatchers.Main) {
                             userPlaylists = result
                         }
                     }
                 }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    
    fun updateSearchQuery(query: String) {
        searchQuery = query
    }
    
    fun getSongsByAlbum(albumId: Long): List<Song> {
        return songs.filter { it.albumId == albumId }
    }
    
    fun getSongsByArtist(artistName: String): List<Song> {
        val target = artistName.trim()
        return songs.filter { it.artist.trim().equals(target, ignoreCase = true) }
    }

    suspend fun getSongsByGenre(genreId: Long): List<Song> {
        return repository.getSongsByGenre(genreId)
    }

    // Simplified version for now that assumes permission is granted or handles it via standard flow
    // In a real app, we'd use a channel to send the IntentSenderRequest to the UI
    suspend fun performDeleteSong(songId: Long) {
        repository.deleteSong(songId)
    }

    suspend fun getAlbumGenre(albumId: Long): String? {
        // Find a song from this album to get the audio ID
        val song = songs.find { it.albumId == albumId }
        return if (song != null) {
            repository.getGenreForAudio(song.id)
        } else {
            null
        }
    }

    suspend fun performUpdateAlbum(albumId: Long, newTitle: String, newArtist: String, newGenre: String, imageUri: android.net.Uri?): Result<Long> {
        return try {
            val result = repository.updateAlbum(albumId, newTitle, newArtist, newGenre, imageUri)
            if (result.isSuccess) {
                // Clear Coil's image cache so album art reloads with new data
                com.amurayada.music.MusicApplication.getInstance()?.clearImageCache()
                loadLibrary() // Reload to reflect changes
            }
            result
        } catch (e: Exception) {
            // Handle error
            if (e is com.amurayada.music.data.repository.MediaRepository.RequiresPermissionException) {
                _permissionEvent.value = androidx.activity.result.IntentSenderRequest.Builder(e.intentSender).build()
                Result.failure(e)
            } else if (e is android.app.RecoverableSecurityException) {
                _permissionEvent.value = androidx.activity.result.IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build()
                Result.failure(e)
            } else {
                Result.failure(e)
            }
        }
    }

    // Hoisted state for LibraryScreen tabs
    var selectedLibraryTab by mutableStateOf(0) // 0: Songs, 1: Albums, 2: Artists, 3: Genres

    // Search History
    var searchHistory by mutableStateOf<List<String>>(emptyList())
        private set

    private fun loadSearchHistory() {
        val historyString = preferencesRepository.getOnlineSearchHistory()
        if (historyString.isNotEmpty()) {
            searchHistory = historyString.split("|").filter { it.isNotEmpty() }
        }
    }

    private fun saveSearchHistory() {
        preferencesRepository.setOnlineSearchHistory(searchHistory.joinToString("|"))
    }

    fun addToSearchHistory(query: String) {
        if (query.isBlank()) return
        val currentHistory = searchHistory.toMutableList()
        currentHistory.remove(query) // Remove if exists to move to top
        currentHistory.add(0, query)
        if (currentHistory.size > 5) {
            currentHistory.removeAt(currentHistory.lastIndex)
        }
        searchHistory = currentHistory
        saveSearchHistory()
    }

    fun removeFromSearchHistory(query: String) {
        val currentHistory = searchHistory.toMutableList()
        currentHistory.remove(query)
        searchHistory = currentHistory
        saveSearchHistory()
    }

    fun clearSearchHistory() {
        searchHistory = emptyList()
        saveSearchHistory()
    }

    init {
        loadLibrary()
        loadSearchHistory()
        val contentResolver = application.contentResolver
        contentResolver.registerContentObserver(
            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            true,
            contentObserver
        )
        contentResolver.registerContentObserver(
            android.provider.MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
            true,
            contentObserver
        )
        contentResolver.registerContentObserver(
            android.provider.MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI,
            true,
            contentObserver
        )
    }
    private data class LibraryData(
        val songs: List<Song>,
        val albums: List<Album>,
        val artists: List<Artist>,
        val genres: List<com.amurayada.music.data.model.Genre>,
        val playlists: List<com.amurayada.music.data.model.Playlist>,
        val hiddenSongs: List<Song> = emptyList(),
        val lrcKeys: Set<String> = emptySet()
    )
}
