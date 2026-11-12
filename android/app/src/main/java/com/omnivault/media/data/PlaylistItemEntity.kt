package com.omnivault.media.data

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "playlist_items",
    primaryKeys = ["playlistId", "trackPath"],
    indices = [Index(value = ["playlistId"])]
)
data class PlaylistItemEntity(
    val playlistId: String,
    val trackPath: String,
    val title: String,
    val artist: String = "OmniVault",
    val durationMs: Long = 0L,
    val coverPath: String? = null,
    val addedAt: Long = System.currentTimeMillis()
)
