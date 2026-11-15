package com.amurayada.music.data.repository

import android.content.Context
import com.amurayada.music.data.model.Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class PlaylistRepository(private val context: Context) {
    private val playlistsFile = File(context.filesDir, "playlists.json")

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    suspend fun loadPlaylists() {
        withContext(Dispatchers.IO) {
            if (playlistsFile.exists()) {
                try {
                    val content = playlistsFile.readText()
                    val jsonArray = JSONArray(content)
                    val loadedPlaylists = mutableListOf<Playlist>()
                    
                    for (i in 0 until jsonArray.length()) {
                        val jsonObject = jsonArray.getJSONObject(i)
                        val id = jsonObject.getLong("id")
                        val name = jsonObject.getString("name")
                        val dateCreated = jsonObject.optLong("dateCreated", System.currentTimeMillis())
                        
                        val songIdsJsonArray = jsonObject.optJSONArray("songIds")
                        val songIds = mutableListOf<Long>()
                        if (songIdsJsonArray != null) {
                            for (j in 0 until songIdsJsonArray.length()) {
                                songIds.add(songIdsJsonArray.getLong(j))
                            }
                        }
                        
                        loadedPlaylists.add(Playlist(id, name, songIds, dateCreated))
                    }
                    
                    _playlists.value = loadedPlaylists
                } catch (e: Exception) {
                    e.printStackTrace()
                    _playlists.value = emptyList()
                }
            } else {
                _playlists.value = emptyList()
            }
        }
    }

    private suspend fun savePlaylists() {
        withContext(Dispatchers.IO) {
            try {
                val jsonArray = JSONArray()
                _playlists.value.forEach { playlist ->
                    val jsonObject = JSONObject()
                    jsonObject.put("id", playlist.id)
                    jsonObject.put("name", playlist.name)
                    jsonObject.put("dateCreated", playlist.dateCreated)
                    
                    val songIdsJsonArray = JSONArray()
                    playlist.songIds.forEach { songId ->
                        songIdsJsonArray.put(songId)
                    }
                    jsonObject.put("songIds", songIdsJsonArray)
                    
                    jsonArray.put(jsonObject)
                }
                
                playlistsFile.writeText(jsonArray.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun createPlaylist(name: String) {
        val newPlaylist = Playlist(
            id = System.currentTimeMillis(),
            name = name
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

    fun exportPlaylistsJson(): String {
        val jsonArray = JSONArray()
        _playlists.value.forEach { playlist ->
            val obj = JSONObject()
            obj.put("id", playlist.id)
            obj.put("name", playlist.name)
            obj.put("dateCreated", playlist.dateCreated)
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
                val id = obj.optLong("id", System.currentTimeMillis() + i)
                val name = obj.optString("name", "").trim()
                if (name.isEmpty()) continue
                val dateCreated = obj.optLong("dateCreated", System.currentTimeMillis())
                val songIdsArray = obj.optJSONArray("songIds")
                val songIds = mutableListOf<Long>()
                if (songIdsArray != null) {
                    for (j in 0 until songIdsArray.length()) {
                        songIds.add(songIdsArray.getLong(j))
                    }
                }
                if (id !in existingIds) {
                    current.add(Playlist(id = id, name = name, songIds = songIds, dateCreated = dateCreated))
                    existingIds.add(id)
                    imported++
                }
            }
            if (imported > 0) {
                _playlists.value = current
                savePlaylists()
            }
            imported
        }
    }
}
