package com.amurayada.music.ui.viewmodel

import android.app.Application
import android.content.ContentUris
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omnivault.media.data.LocalMediaScanner
import com.omnivault.media.model.DownloadItem
import com.omnivault.media.model.SortOption
import com.omnivault.media.utils.FilenameStandardizer
import com.omnivault.media.utils.PlaylistBackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class VideoPlaylist(
    val id: String,
    val name: String,
    val videoIds: List<String>,
    val createdAt: Long = System.currentTimeMillis()
)

class VideosViewModel(application: Application) : AndroidViewModel(application) {

    private val _rawVideos = MutableStateFlow<List<DownloadItem>>(emptyList())
    val rawVideos: StateFlow<List<DownloadItem>> = _rawVideos.asStateFlow()
    private val _videoPlaylists = MutableStateFlow<List<VideoPlaylist>>(emptyList())
    val videoPlaylists: StateFlow<List<VideoPlaylist>> = _videoPlaylists.asStateFlow()
    private val _playbackQueue = MutableStateFlow<List<DownloadItem>>(emptyList())
    val playbackQueue: StateFlow<List<DownloadItem>> = _playbackQueue.asStateFlow()
    private val _isLoading = MutableStateFlow(false)

    fun setPlaybackQueue(items: List<DownloadItem>) {
        _playbackQueue.value = items
    }
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val prefs = application.getSharedPreferences("omnivault_prefs", android.content.Context.MODE_PRIVATE)

    val searchQuery = MutableStateFlow("")
    val sortOption = MutableStateFlow<SortOption>(
        SortOption.fromKey(prefs.getString("video_sort_order", SortOption.DATE_DESC.name) ?: SortOption.DATE_DESC.name)
    )
    val hideShortClips = MutableStateFlow(prefs.getBoolean("hide_short_videos", false))
    val hideWhatsAppClips = MutableStateFlow(prefs.getBoolean("hide_whatsapp_videos", true))
    val onlyDownloaded = MutableStateFlow(false)
    val onlyCamera = MutableStateFlow(false)

    fun setSortOption(sort: SortOption) {
        sortOption.value = sort
        prefs.edit().putString("video_sort_order", sort.name).apply()
    }

    fun setHideShortClips(enabled: Boolean) {
        hideShortClips.value = enabled
        prefs.edit().putBoolean("hide_short_videos", enabled).apply()
    }

    fun setHideWhatsAppClips(enabled: Boolean) {
        hideWhatsAppClips.value = enabled
        prefs.edit().putBoolean("hide_whatsapp_videos", enabled).apply()
    }

    fun setOnlyDownloaded(enabled: Boolean) {
        onlyDownloaded.value = enabled
        if (enabled) onlyCamera.value = false
    }

    fun setOnlyCamera(enabled: Boolean) {
        onlyCamera.value = enabled
        if (enabled) onlyDownloaded.value = false
    }

    private fun isWhatsAppVideo(item: DownloadItem): Boolean {
        val path = item.localFilePath ?: ""
        return path.contains("WhatsApp", ignoreCase = true) ||
            (item.title.startsWith("VID-", ignoreCase = true) && item.title.contains("-WA", ignoreCase = true))
    }

    private fun isGifItem(item: DownloadItem): Boolean {
        val path = item.localFilePath ?: ""
        return path.endsWith(".gif", ignoreCase = true) ||
            item.url.endsWith(".gif", ignoreCase = true) ||
            item.title.endsWith(".gif", ignoreCase = true) ||
            path.contains("WhatsApp Animated Gifs", ignoreCase = true) ||
            path.contains("Animated Gifs", ignoreCase = true)
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
        hideWhatsAppClips,
        onlyDownloaded,
        onlyCamera
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val list = args[0] as List<DownloadItem>
        val query = args[1] as String
        val sort = args[2] as SortOption
        val hideShort = args[3] as Boolean
        val hideWa = args[4] as Boolean
        val onlyDl = args[5] as Boolean
        val onlyCam = args[6] as Boolean
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

        if (onlyCam) {
            filtered = filtered.filter {
                val p = it.localFilePath ?: ""
                p.contains("DCIM", ignoreCase = true) || p.contains("Camera", ignoreCase = true)
            }
        }

        if (hideShort) {
            filtered = filtered.filter {
                it.durationFormatted.isBlank() || parseDurationSeconds(it.durationFormatted) >= 5L
            }
        }

        if (hideWa) {
            filtered = filtered.filterNot { isWhatsAppVideo(it) }
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

    private var videoRefreshDebounceJob: Job? = null

    private val videoContentObserver = object : android.database.ContentObserver(
        android.os.Handler(android.os.Looper.getMainLooper())
    ) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            scheduleVideoRefresh()
        }

        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            scheduleVideoRefresh()
        }
    }

    private fun scheduleVideoRefresh() {
        videoRefreshDebounceJob?.cancel()
        videoRefreshDebounceJob = viewModelScope.launch {
            delay(450)
            loadVideos()
        }
    }

    init {
        loadVideoPlaylists()
        loadVideos()
        try {
            application.contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true,
                videoContentObserver
            )
        } catch (_: Exception) {}

        viewModelScope.launch {
            try {
                val dao = com.amurayada.music.data.database.DownloadDatabase
                    .getDatabase(application)
                    .downloadDao()
                dao.getAllDownloads()
                    .map { list -> list.count { it.isVideo } to list.filter { it.isVideo }.map { "${it.songId}:${it.localPath}:${it.title}" } }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect {
                        scheduleVideoRefresh()
                    }
            } catch (_: Exception) {}
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            getApplication<Application>().contentResolver.unregisterContentObserver(videoContentObserver)
        } catch (_: Exception) {}
    }

    private var loadVideosJob: Job? = null
    private val playlistLock = Any()

    fun loadVideos() {
        loadVideosJob?.cancel()
        loadVideosJob = viewModelScope.launch {
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
                    if (isGifItem(item)) continue
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
                if (scanned.isNotEmpty() && merged.isNotEmpty()) {
                    pruneMissingVideosFromPlaylists(merged.mapTo(HashSet()) { it.id })
                }
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
                    try {
                        com.amurayada.music.data.database.DownloadDatabase
                            .getDatabase(getApplication())
                            .downloadDao()
                            .updateDownloadPathAndTitle(oldPath, newPath, item.title)
                    } catch (_: Exception) {}
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

    private suspend fun removeVideoFromDb(item: DownloadItem) {
        val ctx = getApplication<Application>()
        val dao = com.amurayada.music.data.database.DownloadDatabase.getDatabase(ctx).downloadDao()
        val path = item.localFilePath
        if (item.id.startsWith("db_video_")) {
            val songId = item.id.removePrefix("db_video_").toLongOrNull()
            if (songId != null) {
                dao.deleteDownloadById(songId)
            }
        } else {
            if (!path.isNullOrBlank()) {
                dao.deleteDownloadByPath(path)
            }
            try {
                val dbVideos = dao.getDownloadsByType(true)
                val targetName = path?.let { java.io.File(it).name }
                dbVideos.filter { entity ->
                    (item.fileSizeBytes > 0L && entity.fileSize == item.fileSizeBytes && entity.title.equals(item.title, ignoreCase = true)) ||
                        (!targetName.isNullOrBlank() && entity.localPath?.endsWith("/$targetName", ignoreCase = true) == true)
                }.forEach { entity ->
                    dao.deleteDownloadById(entity.songId)
                }
            } catch (_: Exception) {}
        }
        purgeVideoFromPlaylists(item.id)
    }

    private fun purgeVideoFromPlaylists(videoId: String) {
        val current = _videoPlaylists.value
        if (current.none { videoId in it.videoIds }) return
        val updated = current.map { pl ->
            if (videoId in pl.videoIds) pl.copy(videoIds = pl.videoIds.filterNot { it == videoId }) else pl
        }
        _videoPlaylists.value = updated
        persistVideoPlaylists(updated)
    }

    private fun pruneMissingVideosFromPlaylists(validVideoIds: Set<String>) {
        val current = _videoPlaylists.value
        if (current.isEmpty() || validVideoIds.isEmpty()) return
        var changed = false
        val updated = current.map { pl ->
            val filtered = pl.videoIds.filter { it in validVideoIds }
            if (filtered.size != pl.videoIds.size) {
                changed = true
                pl.copy(videoIds = filtered)
            } else pl
        }
        if (changed) {
            _videoPlaylists.value = updated
            persistVideoPlaylists(updated)
        }
    }

    fun confirmVideoDeleted(item: DownloadItem) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                removeVideoFromDb(item)
            } catch (_: Exception) {}
            loadVideos()
        }
    }

    fun confirmVideosBatchDeleted(items: List<DownloadItem>) {
        viewModelScope.launch(Dispatchers.IO) {
            items.forEach { item ->
                try { removeVideoFromDb(item) } catch (_: Exception) {}
            }
            loadVideos()
        }
    }

    fun deleteVideosBatch(
        items: List<DownloadItem>,
        onRequirePermission: (IntentSenderRequest) -> Unit,
        onComplete: (Int) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val pendingUris = mutableListOf<Uri>()
            var deletedCount = 0
            items.forEach { item ->
                val path = item.localFilePath
                if (path.isNullOrBlank()) {
                    removeVideoFromDb(item)
                    deletedCount++
                } else if (path.startsWith("content://")) {
                    val uri = Uri.parse(path)
                    try {
                        if (ctx.contentResolver.delete(uri, null, null) > 0) {
                            removeVideoFromDb(item)
                            deletedCount++
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            pendingUris.add(uri)
                        }
                    } catch (_: SecurityException) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            pendingUris.add(uri)
                        }
                    }
                } else {
                    val f = File(path)
                    if (!f.exists() || f.delete()) {
                        removeVideoFromDb(item)
                        MediaScannerConnection.scanFile(ctx, arrayOf(path), null, null)
                        deletedCount++
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        ctx.contentResolver.query(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            arrayOf(MediaStore.Video.Media._ID),
                            "${MediaStore.Video.Media.DATA} = ?",
                            arrayOf(path),
                            null
                        )?.use { c ->
                            if (c.moveToFirst()) {
                                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                                pendingUris.add(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id))
                            }
                        }
                    }
                }
            }
            if (pendingUris.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val pi = MediaStore.createDeleteRequest(ctx.contentResolver, pendingUris)
                withContext(Dispatchers.Main) {
                    onRequirePermission(IntentSenderRequest.Builder(pi.intentSender).build())
                }
            } else {
                loadVideos()
                withContext(Dispatchers.Main) { onComplete(deletedCount) }
            }
        }
    }

    fun deleteVideo(
        item: DownloadItem,
        onRequirePermission: (IntentSenderRequest) -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val path = item.localFilePath
            try {
                if (path.isNullOrBlank()) {
                    removeVideoFromDb(item)
                    loadVideos()
                    withContext(Dispatchers.Main) { onComplete(true) }
                    return@launch
                }

                if (path.startsWith("content://")) {
                    val contentUri = Uri.parse(path)
                    try {
                        val rows = ctx.contentResolver.delete(contentUri, null, null)
                        if (rows > 0) {
                            removeVideoFromDb(item)
                            loadVideos()
                            withContext(Dispatchers.Main) { onComplete(true) }
                            return@launch
                        }
                    } catch (_: SecurityException) {}

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val pi = MediaStore.createDeleteRequest(ctx.contentResolver, listOf(contentUri))
                        withContext(Dispatchers.Main) {
                            onRequirePermission(IntentSenderRequest.Builder(pi.intentSender).build())
                        }
                        return@launch
                    }
                    withContext(Dispatchers.Main) { onComplete(false) }
                    return@launch
                }

                val file = File(path)
                val deletedDirect = if (file.exists()) file.delete() else true
                if (deletedDirect) {
                    removeVideoFromDb(item)
                    MediaScannerConnection.scanFile(ctx, arrayOf(path), null, null)
                    loadVideos()
                    withContext(Dispatchers.Main) { onComplete(true) }
                    return@launch
                }

                // Buscar URI en MediaStore.Video para solicitar eliminación en 1 solo diálogo del sistema
                var mediaUri: Uri? = null
                ctx.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Video.Media._ID),
                    "${MediaStore.Video.Media.DATA} = ?",
                    arrayOf(path),
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                        mediaUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    }
                }

                if (mediaUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val pi = MediaStore.createDeleteRequest(ctx.contentResolver, listOf(mediaUri!!))
                    withContext(Dispatchers.Main) {
                        onRequirePermission(IntentSenderRequest.Builder(pi.intentSender).build())
                    }
                } else {
                    withContext(Dispatchers.Main) { onComplete(false) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) { onComplete(false) }
            }
        }
    }

    private fun playlistsFile(): File =
        File(getApplication<Application>().filesDir, "video_playlists.json")

    private fun loadVideoPlaylists() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ctx = getApplication<Application>()
                val file = playlistsFile()
                var content: String? = if (file.exists()) {
                    file.readText().takeIf { it.isNotBlank() && it.trim() != "[]" }
                } else null

                if (content == null) {
                    val backup = PlaylistBackupManager.readBackup(ctx, "video_playlists.json")
                    if (!backup.isNullOrBlank() && backup.trim() != "[]") {
                        content = backup
                        try { file.writeText(backup) } catch (_: Exception) {}
                    }
                } else {
                    if (PlaylistBackupManager.readBackup(ctx, "video_playlists.json").isNullOrBlank()) {
                        PlaylistBackupManager.saveBackup(ctx, "video_playlists.json", content)
                    }
                }

                if (content == null) return@launch
                val arr = JSONArray(content)
                val parsed = buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                        val name = obj.optString("name").takeIf { it.isNotBlank() } ?: continue
                        val idsArr = obj.optJSONArray("videoIds") ?: JSONArray()
                        val ids = buildList {
                            for (j in 0 until idsArr.length()) {
                                val vid = idsArr.optString(j)
                                if (vid.isNotBlank()) add(vid)
                            }
                        }
                        add(
                            VideoPlaylist(
                                id = id,
                                name = name,
                                videoIds = ids,
                                createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                            )
                        )
                    }
                }
                _videoPlaylists.value = parsed
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val playlistWriteDispatcher = Dispatchers.IO.limitedParallelism(1)

    private fun persistVideoPlaylists(list: List<VideoPlaylist>) {
        viewModelScope.launch(playlistWriteDispatcher) {
            synchronized(playlistLock) {
                try {
                    val arr = JSONArray()
                    list.forEach { pl ->
                        val obj = JSONObject().apply {
                            put("id", pl.id)
                            put("name", pl.name)
                            put("createdAt", pl.createdAt)
                            put("videoIds", JSONArray(pl.videoIds))
                        }
                        arr.put(obj)
                    }
                    val serialized = arr.toString()
                    val dest = playlistsFile()
                    val tmp = File(dest.parentFile, "video_playlists.json.tmp")
                    tmp.writeText(serialized)
                    if (!tmp.renameTo(dest)) {
                        dest.writeText(serialized)
                        tmp.delete()
                    }
                    PlaylistBackupManager.saveBackup(getApplication(), "video_playlists.json", serialized)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private inline fun mutateVideoPlaylists(transform: (List<VideoPlaylist>) -> List<VideoPlaylist>): List<VideoPlaylist> {
        return synchronized(playlistLock) {
            val updated = transform(_videoPlaylists.value)
            _videoPlaylists.value = updated
            persistVideoPlaylists(updated)
            updated
        }
    }

    fun createVideoPlaylist(name: String, initialVideoIds: List<String> = emptyList()): VideoPlaylist? {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        val created = VideoPlaylist(
            id = UUID.randomUUID().toString(),
            name = clean,
            videoIds = initialVideoIds.distinct()
        )
        mutateVideoPlaylists { listOf(created) + it }
        return created
    }

    fun renameVideoPlaylist(playlistId: String, newName: String) {
        val clean = newName.trim()
        if (clean.isEmpty()) return
        mutateVideoPlaylists { list ->
            list.map { if (it.id == playlistId) it.copy(name = clean) else it }
        }
    }

    fun deleteVideoPlaylist(playlistId: String) {
        mutateVideoPlaylists { list -> list.filterNot { it.id == playlistId } }
    }

    fun addVideosToPlaylist(playlistId: String, videoIds: List<String>) {
        if (videoIds.isEmpty()) return
        mutateVideoPlaylists { list ->
            list.map { pl ->
                if (pl.id == playlistId) pl.copy(videoIds = (pl.videoIds + videoIds).distinct()) else pl
            }
        }
    }

    fun removeVideoFromPlaylist(playlistId: String, videoId: String) {
        mutateVideoPlaylists { list ->
            list.map { pl ->
                if (pl.id == playlistId) pl.copy(videoIds = pl.videoIds.filterNot { it == videoId }) else pl
            }
        }
    }
}
