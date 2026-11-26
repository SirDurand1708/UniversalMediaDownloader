package com.amurayada.music.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amurayada.music.data.model.Playlist
import com.amurayada.music.data.repository.PlaylistRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PlaylistViewModel(
    application: Application,
    private val repository: PlaylistRepository = PlaylistRepository(application)
) : AndroidViewModel(application) {
    val playlists: StateFlow<List<Playlist>> = repository.playlists

    init {
        viewModelScope.launch {
            repository.loadPlaylists()
        }
    }

    fun createPlaylist(name: String, initialSongIds: List<Long> = emptyList()) {
        viewModelScope.launch {
            repository.createPlaylist(name, initialSongIds)
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            repository.deletePlaylist(playlistId)
        }
    }

    fun addSongToPlaylist(playlistId: Long, songId: Long) {
        viewModelScope.launch {
            repository.addSongToPlaylist(playlistId, songId)
        }
    }

    fun addSongsToPlaylist(playlistId: Long, songIds: List<Long>) {
        viewModelScope.launch {
            repository.addSongsToPlaylist(playlistId, songIds)
        }
    }

    fun removeSongFromPlaylist(playlistId: Long, songId: Long) {
        viewModelScope.launch {
            repository.removeSongFromPlaylist(playlistId, songId)
        }
    }

    fun removeSongsFromPlaylist(playlistId: Long, songIds: List<Long>) {
        viewModelScope.launch {
            repository.removeSongsFromPlaylist(playlistId, songIds)
        }
    }

    fun removeSongFromAllPlaylists(songId: Long) {
        viewModelScope.launch {
            repository.removeSongFromAllPlaylists(songId)
        }
    }

    fun moveSongToBoundary(playlistId: Long, songId: Long, toTop: Boolean) {
        viewModelScope.launch {
            repository.moveSongToBoundary(playlistId, songId, toTop)
        }
    }

    fun duplicatePlaylist(playlistId: Long) {
        viewModelScope.launch {
            repository.duplicatePlaylist(playlistId)
        }
    }

    fun updatePlaylistArtwork(playlistId: Long, sourceUri: android.net.Uri?) {
        viewModelScope.launch {
            repository.updatePlaylistArtwork(playlistId, sourceUri)
        }
    }
    
    fun renamePlaylist(playlistId: Long, newName: String) {
        viewModelScope.launch {
            repository.renamePlaylist(playlistId, newName)
        }
    }

    fun moveSongInPlaylist(playlistId: Long, fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            repository.moveSongInPlaylist(playlistId, fromIndex, toIndex)
        }
    }

    fun exportPlaylistsJson(): String = repository.exportPlaylistsJson()

    fun importPlaylistsJson(jsonText: String, onResult: (Int) -> Unit) {
        viewModelScope.launch {
            val count = try {
                repository.importPlaylistsJson(jsonText)
            } catch (_: Exception) {
                -1
            }
            onResult(count)
        }
    }

    fun pruneMissingSongs(validSongIds: Set<Long>) {
        viewModelScope.launch {
            repository.pruneMissingSongs(validSongIds)
        }
    }
}
