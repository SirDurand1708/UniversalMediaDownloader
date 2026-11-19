package com.amurayada.music.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadQueueDao {
    @Query("SELECT * FROM download_queue ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<DownloadQueueEntity>>

    @Query("SELECT * FROM download_queue ORDER BY createdAt ASC")
    suspend fun getAll(): List<DownloadQueueEntity>

    @Query("SELECT * FROM download_queue WHERE processId = :processId LIMIT 1")
    suspend fun getById(processId: String): DownloadQueueEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: DownloadQueueEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<DownloadQueueEntity>)

    @Query("UPDATE download_queue SET status = :status, errorReason = :errorReason WHERE processId = :processId")
    suspend fun updateStatus(processId: String, status: String, errorReason: String? = null)

    @Query("UPDATE download_queue SET title = :title WHERE processId = :processId")
    suspend fun updateTitle(processId: String, title: String)

    @Query("DELETE FROM download_queue WHERE processId = :processId")
    suspend fun deleteById(processId: String)

    @Query("DELETE FROM download_queue")
    suspend fun clearAll()
}
