package com.amurayada.music.data.repository

import android.content.IntentSender
import android.net.Uri
import com.amurayada.music.data.model.Song
import com.omnivault.media.model.DownloadItem
import kotlinx.coroutines.flow.Flow

sealed interface DuplicateDeletionResult {
    data class RequiresPermission(val intentSender: IntentSender) : DuplicateDeletionResult
    data class DeletedDirectly(val count: Int, val isVideo: Boolean) : DuplicateDeletionResult
    data class Error(val message: String) : DuplicateDeletionResult
}

sealed interface SingleVideoDeleteResult {
    data object Deleted : SingleVideoDeleteResult
    data class RequiresPermission(val intentSender: IntentSender) : SingleVideoDeleteResult
    data object Failed : SingleVideoDeleteResult
}

sealed interface BatchVideoDeleteResult {
    data class Completed(val deletedItems: List<DownloadItem>) : BatchVideoDeleteResult
    data class RequiresPermission(
        val intentSender: IntentSender,
        val deletedItems: List<DownloadItem>
    ) : BatchVideoDeleteResult
}

sealed interface AudioDeleteResult {
    data object Deleted : AudioDeleteResult
    data class RequiresPermission(val intentSender: IntentSender) : AudioDeleteResult
}

/**
 * Desacopla las consultas, observadores y solicitudes de borrado en MediaStore de UI y ViewModels.
 */
interface MediaCleanupRepository {
    fun observeVideoStoreChanges(): Flow<Unit>
    fun observeAudioStoreChanges(): Flow<Unit>
    suspend fun scanAllDeviceVideos(): List<DownloadItem>
    fun getMimeTypeForUri(uriString: String): String?
    fun resolveAudioUriAndName(rawPath: String): Pair<String?, String?>
    suspend fun resolveAudioMediaStoreUri(song: Song): Uri
    suspend fun deleteSingleVideo(item: DownloadItem): SingleVideoDeleteResult
    suspend fun deleteBatchVideos(items: List<DownloadItem>): BatchVideoDeleteResult
    suspend fun deleteAudioSong(song: Song): AudioDeleteResult
    suspend fun findDuplicateAudios(songs: List<Song>): List<Pair<Song, Long>>
    suspend fun findDuplicateVideos(): List<DownloadItem>
    suspend fun deleteDuplicateVideos(items: List<DownloadItem>): DuplicateDeletionResult
    suspend fun deleteDuplicateAudios(songs: List<Song>): DuplicateDeletionResult
}
