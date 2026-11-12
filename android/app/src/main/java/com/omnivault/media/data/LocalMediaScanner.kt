package com.omnivault.media.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.omnivault.media.model.DownloadItem
import com.omnivault.media.model.DownloadStatus
import com.omnivault.media.model.MediaFormat
import com.omnivault.media.model.PlatformType
import com.omnivault.media.utils.StorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object LocalMediaScanner {
    private const val TAG = "LocalMediaScanner"

    /**
     * Escanea todo el audio almacenado en el celular mediante MediaStore (Música, Descargas, etc.)
     * Devuelve una lista de DownloadItem completados para integrarse en la biblioteca.
     */
    suspend fun scanDeviceAudio(context: Context): List<DownloadItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<DownloadItem>()
        val trackEntities = mutableListOf<TrackEntity>()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.ALBUM_ID
        )

        // Filtrar audios de más de 5 segundos y más de 50 KB (ignora audios de WhatsApp/notificaciones cortas)
        val selection = "${MediaStore.Audio.Media.DURATION} >= 5000 AND ${MediaStore.Audio.Media.SIZE} >= 50000"

        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val rawTitle = cursor.getString(titleCol) ?: "Audio"
                    val artist = cursor.getString(artistCol) ?: "Desconocido"
                    val album = cursor.getString(albumCol) ?: "OmniVault"
                    val duration = cursor.getLong(durCol)
                    val path = cursor.getString(dataCol) ?: continue
                    val sizeBytes = cursor.getLong(sizeCol)
                    val dateAddedSec = cursor.getLong(dateCol)
                    val albumId = cursor.getLong(albumIdCol)

                    val file = File(path)
                    if (!file.exists() || file.length() == 0L) continue

                    val sArtworkUri = Uri.parse("content://media/external/audio/albumart")
                    val coverUri = ContentUris.withAppendedId(sArtworkUri, albumId).toString()

                    val cleanArtist = if (artist == "<unknown>" || artist.isBlank()) "Desconocido" else artist
                    val isM4a = path.endsWith(".m4a", ignoreCase = true)
                    val format = if (isM4a) MediaFormat.M4A else MediaFormat.MP3_320

                    val downloadItem = DownloadItem(
                        id = "local_audio_$id",
                        url = path,
                        title = rawTitle,
                        format = format,
                        platform = PlatformType.UNIVERSAL,
                        progress = 100,
                        speedText = "Local",
                        status = DownloadStatus.COMPLETED,
                        localFilePath = path,
                        fileSizeFormatted = StorageHelper.formatFileSize(sizeBytes),
                        thumbnailUrl = coverUri,
                        artist = cleanArtist,
                        timestamp = if (dateAddedSec > 0) dateAddedSec * 1000 else file.lastModified()
                    )
                    items.add(downloadItem)

                    trackEntities.add(
                        TrackEntity(
                            id = "track_$id",
                            title = rawTitle,
                            artist = cleanArtist,
                            album = album,
                            durationMs = duration,
                            filePath = path,
                            coverPath = coverUri
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error escaneando MediaStore de audio", e)
        }

        // Persistir en Room
        try {
            if (trackEntities.isNotEmpty()) {
                val db = AppDatabase.getInstance(context)
                db.trackDao().insertTracks(trackEntities)
            }
        } catch (_: Exception) {}

        items
    }

    /**
     * Escanea todos los videos almacenados en el celular mediante MediaStore (Cámara, Descargas, Películas, etc.)
     * Devuelve una lista de DownloadItem de video completados.
     */
    suspend fun scanDeviceVideo(context: Context): List<DownloadItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<DownloadItem>()

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.RESOLUTION
        )

        // Filtrar videos de más de 3 segundos y más de 100 KB
        val selection = "${MediaStore.Video.Media.DURATION} >= 3000 AND ${MediaStore.Video.Media.SIZE} >= 100000"

        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val resCol = cursor.getColumnIndex(MediaStore.Video.Media.RESOLUTION)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val rawTitle = cursor.getString(titleCol) ?: "Video"
                    val path = cursor.getString(dataCol) ?: continue
                    val sizeBytes = cursor.getLong(sizeCol)
                    val dateAddedSec = cursor.getLong(dateCol)
                    val resolution = if (resCol != -1) cursor.getString(resCol) ?: "" else ""

                    val file = File(path)
                    if (!file.exists() || file.length() == 0L) continue

                    val format = if (resolution.contains("1080") || resolution.contains("1920")) {
                        MediaFormat.MP4_1080
                    } else {
                        MediaFormat.MP4_720
                    }

                    // URI de contenido de video para que Coil cargue la miniatura directamente
                    val videoUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id).toString()

                    val downloadItem = DownloadItem(
                        id = "local_video_$id",
                        url = path,
                        title = rawTitle,
                        format = format,
                        platform = PlatformType.UNIVERSAL,
                        progress = 100,
                        speedText = "Local",
                        status = DownloadStatus.COMPLETED,
                        localFilePath = path,
                        fileSizeFormatted = StorageHelper.formatFileSize(sizeBytes),
                        thumbnailUrl = videoUri,
                        artist = "Dispositivo",
                        timestamp = if (dateAddedSec > 0) dateAddedSec * 1000 else file.lastModified()
                    )
                    items.add(downloadItem)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error escaneando MediaStore de video", e)
        }

        items
    }
}
