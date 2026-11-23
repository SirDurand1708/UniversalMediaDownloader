package com.amurayada.music.data.repository

import android.content.Context
import com.amurayada.music.data.model.Playlist
import com.omnivault.media.utils.PlaylistBackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class PlaylistRepository(private val context: Context) {
    private val playlistsFile = File(context.filesDir, "playlists.json")
    private val writeMutex = Mutex()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    suspend fun loadPlaylists() {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                var content: String? = if (playlistsFile.exists()) {
                    playlistsFile.readText().takeIf { it.isNotBlank() && it.trim() != "[]" }
                } else null

                if (content == null) {
                    val backup = PlaylistBackupManager.readBackup(context, "playlists.json")
                    if (!backup.isNullOrBlank() && backup.trim() != "[]") {
                        content = backup
                        try { playlistsFile.writeText(backup) } catch (_: Exception) {}
                    }
                } else {
                    // Asegurar respaldo inicial de playlists ya existentes
                    if (PlaylistBackupManager.readBackup(context, "playlists.json").isNullOrBlank()) {
                        PlaylistBackupManager.saveBackup(context, "playlists.json", content)
                    }
                }

                if (content != null) {
                    try {
                        val jsonArray = JSONArray(content)
                        val loadedPlaylists = mutableListOf<Playlist>()

                        for (i in 0 until jsonArray.length()) {
                            val jsonObject = jsonArray.getJSONObject(i)
                            val id = jsonObject.getLong("id")
                            val name = jsonObject.getString("name")
                            val dateCreated = jsonObject.optLong("dateCreated", System.currentTimeMillis())
                            val artworkUri = jsonObject.optString("artworkUri", "").takeIf { it.isNotBlank() }

                            val songIdsJsonArray = jsonObject.optJSONArray("songIds")
                            val songIds = mutableListOf<Long>()
                            if (songIdsJsonArray != null) {
                                for (j in 0 until songIdsJsonArray.length()) {
                                    val sid = songIdsJsonArray.optLong(j, -1L)
                                    if (sid >= 0L) songIds.add(sid)
                                }
                            }

                            loadedPlaylists.add(Playlist(id = id, name = name, songIds = songIds, dateCreated = dateCreated, artworkUri = artworkUri))
                        }

                        if (_playlists.value.isEmpty()) {
                            _playlists.value = loadedPlaylists
                        } else {
                            val loadedIds = loadedPlaylists.mapTo(HashSet()) { it.id }
                            val inMemoryNew = _playlists.value.filter { it.id !in loadedIds }
                            _playlists.value = loadedPlaylists + inMemoryNew
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    private suspend fun savePlaylists() {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                try {
                    val jsonArray = JSONArray()
                    _playlists.value.forEach { playlist ->
                        val jsonObject = JSONObject()
                        jsonObject.put("id", playlist.id)
                        jsonObject.put("name", playlist.name)
                        jsonObject.put("dateCreated", playlist.dateCreated)
                        if (!playlist.artworkUri.isNullOrBlank()) {
                            jsonObject.put("artworkUri", playlist.artworkUri)
                        }

                        val songIdsJsonArray = JSONArray()
                        playlist.songIds.forEach { songId ->
                            songIdsJsonArray.put(songId)
                        }
                        jsonObject.put("songIds", songIdsJsonArray)

                        jsonArray.put(jsonObject)
                    }

                    val serialized = jsonArray.toString()
                    val tmpFile = File(context.filesDir, "playlists.json.tmp")
                    tmpFile.writeText(serialized)
                    if (!tmpFile.renameTo(playlistsFile)) {
                        playlistsFile.writeText(serialized)
                        tmpFile.delete()
                    }
                    PlaylistBackupManager.saveBackup(context, "playlists.json", serialized)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    suspend fun removeSongFromAllPlaylists(songId: Long) {
        val current = _playlists.value
        if (current.none { songId in it.songIds }) return
        _playlists.value = current.map { pl ->
            if (songId in pl.songIds) pl.copy(songIds = pl.songIds.filterNot { it == songId }) else pl
        }
        savePlaylists()
    }

    suspend fun createPlaylist(name: String, initialSongIds: List<Long> = emptyList()) {
        val newPlaylist = Playlist(
            id = System.currentTimeMillis(),
            name = name,
            songIds = initialSongIds.distinct()
        )
        _playlists.value = _playlists.value + newPlaylist
        savePlaylists()
    }

    suspend fun deletePlaylist(playlistId: Long) {
        _playlists.value = _playlists.value.filter { it.id != playlistId }
        savePlaylists()
    }

    suspend fun addSongToPlaylist(playlistId: Long, songId: Long) {
        _playlists.value = _playlists.value.map { playlist ->
            if (playlist.id == playlistId) {
                if (!playlist.songIds.contains(songId)) {
                    playlist.copy(songIds = playlist.songIds + songId)
                } else {
                    playlist
                }
            } else {
                playlist
            }
        }
        savePlaylists()
    }

    suspend fun addSongsToPlaylist(playlistId: Long, songIds: List<Long>) {
        if (songIds.isEmpty()) return
        _playlists.value = _playlists.value.map { playlist ->
            if (playlist.id == playlistId) {
                val existing = playlist.songIds.toSet()
                val toAdd = songIds.filter { it !in existing }
                if (toAdd.isNotEmpty()) {
                    playlist.copy(songIds = playlist.songIds + toAdd)
                } else {
                    playlist
                }
            } else {
                playlist
            }
        }
        savePlaylists()
    }

    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long) {
        _playlists.value = _playlists.value.map { playlist ->
            if (playlist.id == playlistId) {
                playlist.copy(songIds = playlist.songIds - songId)
            } else {
                playlist
            }
        }
        savePlaylists()
    }
    
    suspend fun renamePlaylist(playlistId: Long, newName: String) {
        _playlists.value = _playlists.value.map { playlist ->
            if (playlist.id == playlistId) {
                playlist.copy(name = newName)
            } else {
                playlist
            }
        }
        savePlaylists()
    }

    suspend fun moveSongInPlaylist(playlistId: Long, fromIndex: Int, toIndex: Int) {
        _playlists.value = _playlists.value.map { playlist ->
            if (playlist.id == playlistId &&
                fromIndex in playlist.songIds.indices &&
                toIndex in playlist.songIds.indices &&
                fromIndex != toIndex
            ) {
                val mutable = playlist.songIds.toMutableList()
                val item = mutable.removeAt(fromIndex)
                mutable.add(toIndex, item)
                playlist.copy(songIds = mutable)
            } else {
                playlist
            }
        }
        savePlaylists()
    }

    suspend fun removeSongsFromPlaylist(playlistId: Long, songIds: List<Long>) {
        if (songIds.isEmpty()) return
        val toRemove = songIds.toSet()
        _playlists.value = _playlists.value.map { playlist ->
            if (playlist.id == playlistId) {
                playlist.copy(songIds = playlist.songIds.filterNot { it in toRemove })
            } else {
                playlist
            }
        }
        savePlaylists()
    }

    suspend fun moveSongToBoundary(playlistId: Long, songId: Long, toTop: Boolean) {
        _playlists.value = _playlists.value.map { playlist ->
            if (playlist.id == playlistId && playlist.songIds.contains(songId)) {
                val mutable = playlist.songIds.toMutableList()
                mutable.remove(songId)
                if (toTop) mutable.add(0, songId) else mutable.add(songId)
                playlist.copy(songIds = mutable)
            } else {
                playlist
            }
        }
        savePlaylists()
    }

    suspend fun duplicatePlaylist(playlistId: Long) {
        val original = _playlists.value.find { it.id == playlistId } ?: return
        val copy = Playlist(
            id = System.currentTimeMillis(),
            name = "${original.name} (Copia)",
            songIds = original.songIds.toList(),
            dateCreated = System.currentTimeMillis(),
            artworkUri = original.artworkUri
        )
        _playlists.value = _playlists.value + copy
        savePlaylists()
    }

    suspend fun updatePlaylistArtwork(playlistId: Long, sourceUri: android.net.Uri?) {
        val savedPath: String? = withContext(Dispatchers.IO) {
            val coversDir = File(context.filesDir, "playlist_covers").apply { mkdirs() }
            val destFile = File(coversDir, "cover_${playlistId}.jpg")
            if (sourceUri == null) {
                if (destFile.exists()) destFile.delete()
                return@withContext null
            }
            try {
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 65_536)
                    }
                }
                if (destFile.exists() && destFile.length() > 0L) android.net.Uri.fromFile(destFile).toString() else null
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
        _playlists.value = _playlists.value.map { playlist ->
            if (playlist.id == playlistId) playlist.copy(artworkUri = savedPath) else playlist
        }
        savePlaylists()
    }

    fun exportPlaylistsJson(): String {
        val jsonArray = JSONArray()
        _playlists.value.forEach { playlist ->
            val obj = JSONObject()
            obj.put("id", playlist.id)
            obj.put("name", playlist.name)
            obj.put("dateCreated", playlist.dateCreated)
            if (!playlist.artworkUri.isNullOrBlank()) {
                obj.put("artworkUri", playlist.artworkUri)
            }
            val ids = JSONArray()
            playlist.songIds.forEach { ids.put(it) }
            obj.put("songIds", ids)
            jsonArray.put(obj)
        }
        return jsonArray.toString(2)
    }

    suspend fun importPlaylistsJson(jsonText: String): Int {
        return withContext(Dispatchers.IO) {
            val jsonArray = JSONArray(jsonText)
            val current = _playlists.value.toMutableList()
            val existingIds = current.map { it.id }.toMutableSet()
            var imported = 0
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                var id = obj.optLong("id", System.currentTimeMillis() + i)
                if (id <= 0L || id in existingIds) {
                    id = System.currentTimeMillis() + i + 1
                    while (id in existingIds) id++
                }
                val name = obj.optString("name", "").trim()
                if (name.isEmpty()) continue
                val dateCreated = obj.optLong("dateCreated", System.currentTimeMillis())
                val artworkUri = obj.optString("artworkUri", "").takeIf { it.isNotBlank() && it.startsWith("http") }
                val songIdsArray = obj.optJSONArray("songIds")
                val songIds = mutableListOf<Long>()
                if (songIdsArray != null) {
                    for (j in 0 until songIdsArray.length()) {
                        val sid = songIdsArray.optLong(j, -1L)
                        if (sid >= 0L) songIds.add(sid)
                    }
                }
                current.add(Playlist(id = id, name = name, songIds = songIds, dateCreated = dateCreated, artworkUri = artworkUri))
                existingIds.add(id)
                imported++
            }
            if (imported > 0) {
                _playlists.value = current
                savePlaylists()
            }
            imported
        }
    }

    suspend fun pruneMissingSongs(validSongIds: Set<Long>) {
        if (validSongIds.isEmpty()) return
        val current = _playlists.value
        if (current.isEmpty()) return
        var changed = false
        val updated = current.map { pl ->
            val filtered = pl.songIds.filter { it in validSongIds }
            if (filtered.size != pl.songIds.size) {
                changed = true
                pl.copy(songIds = filtered)
            } else {
                pl
            }
        }
        if (changed) {
            _playlists.value = updated
            savePlaylists()
        }
    }
}
