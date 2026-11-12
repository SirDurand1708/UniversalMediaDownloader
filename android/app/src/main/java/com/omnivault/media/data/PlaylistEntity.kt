package com.omnivault.media.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val coverPath: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
