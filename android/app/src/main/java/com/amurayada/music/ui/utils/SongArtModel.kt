package com.amurayada.music.ui.utils

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import com.amurayada.music.data.model.Song
import okio.Buffer
import java.io.File

data class SongArtModel(
    val songId: Long,
    val path: String,
    val fallbackUri: Uri?
)

fun toArtModel(song: Song): Any? {
    val isLocalFile = song.path.isNotBlank() &&
        !song.path.startsWith("http://", ignoreCase = true) &&
        !song.path.startsWith("https://", ignoreCase = true)
    return if (isLocalFile && (song.id > 0L || File(song.path).exists())) {
        SongArtModel(songId = song.id, path = song.path, fallbackUri = song.albumArtUri)
    } else {
        song.albumArtUri
    }
}

class SongArtKeyer : Keyer<SongArtModel> {
    override fun key(data: SongArtModel, options: Options): String {
        val lastModified = if (!data.path.startsWith("content://")) {
            try {
                val f = File(data.path)
                if (f.exists()) f.lastModified() else 0L
            } catch (_: Exception) {
                0L
            }
        } else {
            0L
        }
        return "song_art_${data.songId}_${data.path.hashCode()}_$lastModified"
    }
}

class SongArtFetcher(
    private val context: Context,
    private val data: SongArtModel
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val embedded = extractEmbeddedArt()
        if (embedded != null && embedded.isNotEmpty()) {
            return SourceResult(
                source = ImageSource(Buffer().write(embedded), context),
                mimeType = "image/jpeg",
                dataSource = DataSource.DISK
            )
        }

        val fallback = data.fallbackUri ?: return null
        return try {
            val bytes = context.contentResolver.openInputStream(fallback)?.use { it.readBytes() }
            if (bytes != null && bytes.isNotEmpty()) {
                SourceResult(
                    source = ImageSource(Buffer().write(bytes), context),
                    mimeType = context.contentResolver.getType(fallback) ?: "image/jpeg",
                    dataSource = DataSource.DISK
                )
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractEmbeddedArt(): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            val file = if (!data.path.startsWith("content://")) File(data.path) else null
            when {
                data.path.startsWith("content://") -> {
                    retriever.setDataSource(context, Uri.parse(data.path))
                }
                file != null && file.exists() && file.canRead() -> {
                    retriever.setDataSource(file.absolutePath)
                }
                data.songId > 0L -> {
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        data.songId
                    )
                    retriever.setDataSource(context, contentUri)
                }
                else -> return null
            }
            retriever.embeddedPicture
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
    }

    class Factory(private val context: Context) : Fetcher.Factory<SongArtModel> {
        override fun create(data: SongArtModel, options: Options, imageLoader: ImageLoader): Fetcher {
            return SongArtFetcher(context.applicationContext, data)
        }
    }
}
