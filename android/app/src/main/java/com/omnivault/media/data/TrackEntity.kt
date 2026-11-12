package com.omnivault.media.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val artist: String = "Desconocido",
    val album: String = "OmniVault",
    val durationMs: Long = 0L,
    val filePath: String,
    val coverPath: String? = null,
    val isFavorite: Boolean = false,
    val playCount: Int = 0,
    val dateAdded: Long = System.currentTimeMillis()
)
