package com.amurayada.music.data.repository

import android.content.IntentSender
import com.amurayada.music.data.model.Song
import com.omnivault.media.model.DownloadItem

sealed interface DuplicateDeletionResult {
    data class RequiresPermission(val intentSender: IntentSender) : DuplicateDeletionResult
    data class DeletedDirectly(val count: Int, val isVideo: Boolean) : DuplicateDeletionResult
    data class Error(val message: String) : DuplicateDeletionResult
}

/**
 * Desacopla las consultas y solicitudes de borrado en MediaStore de la capa de UI.
 */
interface MediaCleanupRepository {
    suspend fun findDuplicateAudios(songs: List<Song>): List<Pair<Song, Long>>
    suspend fun findDuplicateVideos(): List<DownloadItem>
    suspend fun deleteDuplicateVideos(items: List<DownloadItem>): DuplicateDeletionResult
    suspend fun deleteDuplicateAudios(songs: List<Song>): DuplicateDeletionResult
}
