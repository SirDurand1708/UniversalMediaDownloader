package com.amurayada.music.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "download_queue")
data class DownloadQueueEntity(
    @PrimaryKey
    val processId: String,
    val url: String,
    val normalizedUrl: String,
    val formatName: String,
    val title: String = "En cola…",
    val status: String = "QUEUED",
    val errorReason: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
