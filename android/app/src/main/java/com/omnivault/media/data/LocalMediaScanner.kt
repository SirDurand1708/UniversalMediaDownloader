package com.omnivault.media.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.omnivault.media.model.DownloadItem
import com.omnivault.media.model.DownloadStatus
import com.omnivault.media.model.MediaFormat
import com.omnivault.media.model.PlatformType
import com.omnivault.media.utils.FilenameStandardizer
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

        // Filtrar audios genuinos por tamaño y duración flexible para no descartar descargas recientes
        val selection = "${MediaStore.Audio.Media.SIZE} >= 30000 AND (${MediaStore.Audio.Media.DURATION} >= 3000 OR ${MediaStore.Audio.Media.DURATION} IS NULL OR ${MediaStore.Audio.Media.DURATION} = 0)"

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
                    val coverUri = if (albumId > 0) ContentUris.withAppendedId(sArtworkUri, albumId).toString() else null

                    val cleanArtist = if (artist == "<unknown>" || artist.isBlank()) "Desconocido" else artist
                    val isM4a = path.endsWith(".m4a", ignoreCase = true)
                    val format = if (isM4a) MediaFormat.M4A else MediaFormat.MP3_320
                    val durFormatted = if (duration > 0) {
                        val sec = duration / 1000
                        String.format("%02d:%02d", sec / 60, sec % 60)
                    } else ""

                    val downloadItem = DownloadItem(
                        id = "local_audio_$id",
                        url = path,
                        title = rawTitle,
                        format = format,
                        platform = PlatformType.GENERIC,
                        progress = 100,
                        speedText = "Local",
                        status = DownloadStatus.COMPLETED,
                        localFilePath = path,
                        fileSizeFormatted = StorageHelper.formatFileSize(sizeBytes),
                        fileSizeBytes = sizeBytes,
                        durationFormatted = durFormatted,
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

        // Persistir en Room en lotes seguros de 100 para no exceder los límites de parámetros SQL de Android
        try {
            if (trackEntities.isNotEmpty()) {
                val db = AppDatabase.getInstance(context)
                trackEntities.chunked(100).forEach { chunk ->
                    db.trackDao().insertTracks(chunk)
                }
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

        // Filtrar videos de más de 20 KB sin exigir duración previa para no descartar descargas recientes
        val selection = "${MediaStore.Video.Media.SIZE} >= 20000"
        val seenPaths = mutableSetOf<String>()

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
                val durCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val resCol = cursor.getColumnIndex(MediaStore.Video.Media.RESOLUTION)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val rawTitle = cursor.getString(titleCol) ?: "Video"
                    val path = cursor.getString(dataCol) ?: continue
                    val sizeBytes = cursor.getLong(sizeCol)
                    val dateAddedSec = cursor.getLong(dateCol)
                    val resolution = if (resCol != -1) cursor.getString(resCol) ?: "" else ""
                    val durMs = if (durCol != -1) cursor.getLong(durCol) else 0L

                    val file = File(path)
                    if (!file.exists() || file.length() == 0L) continue
                    seenPaths.add(file.absolutePath)

                    val format = if (resolution.contains("1080") || resolution.contains("1920")) {
                        MediaFormat.MP4_1080
                    } else {
                        MediaFormat.MP4_720
                    }

                    val durFormatted = if (durMs > 0) {
                        val totalSec = durMs / 1000
                        String.format("%02d:%02d", totalSec / 60, totalSec % 60)
                    } else if (format == MediaFormat.MP4_1080) {
                        "1080p"
                    } else {
                        "720p"
                    }

                    val downloadItem = DownloadItem(
                        id = "local_video_$id",
                        url = path,
                        title = rawTitle,
                        format = format,
                        platform = PlatformType.GENERIC,
                        progress = 100,
                        speedText = "Local",
                        status = DownloadStatus.COMPLETED,
                        localFilePath = path,
                        fileSizeFormatted = StorageHelper.formatFileSize(sizeBytes),
                        fileSizeBytes = sizeBytes,
                        durationFormatted = durFormatted,
                        thumbnailUrl = path,
                        artist = "Dispositivo",
                        timestamp = if (dateAddedSec > 0) dateAddedSec * 1000 else file.lastModified()
                    )
                    items.add(downloadItem)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error escaneando MediaStore de video", e)
        }

        // Descubrimiento directo en carpetas públicas y privadas de OmniVault para disponibilidad inmediata
        try {
            val omniFolders = listOfNotNull(
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "OmniVault"),
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OmniVault"),
                context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                StorageHelper.getCustomStoragePath(context)?.let { File(it, "Movies") }
            )

            for (folder in omniFolders) {
                if (folder.exists() && folder.isDirectory) {
                    val videoFiles = folder.listFiles { file ->
                        file.isFile && file.length() >= 20000 &&
                                (file.name.endsWith(".mp4", ignoreCase = true) ||
                                 file.name.endsWith(".mkv", ignoreCase = true) ||
                                 file.name.endsWith(".webm", ignoreCase = true))
                    } ?: emptyArray()

                    for (file in videoFiles) {
                        if (!seenPaths.contains(file.absolutePath)) {
                            seenPaths.add(file.absolutePath)
                            val is1080 = file.name.contains("1080", ignoreCase = true)
                            val format = if (is1080) MediaFormat.MP4_1080 else MediaFormat.MP4_720
                            val cleanTitle = FilenameStandardizer.standardizeTitle(file.nameWithoutExtension)
                            val item = DownloadItem(
                                id = "omnivault_video_${file.name.hashCode()}",
                                url = file.absolutePath,
                                title = cleanTitle,
                                format = format,
                                platform = PlatformType.GENERIC,
                                progress = 100,
                                speedText = "OmniVault",
                                status = DownloadStatus.COMPLETED,
                                localFilePath = file.absolutePath,
                                fileSizeFormatted = StorageHelper.formatFileSize(file.length()),
                                fileSizeBytes = file.length(),
                                durationFormatted = if (is1080) "1080p" else "720p",
                                thumbnailUrl = file.absolutePath,
                                artist = "OmniVault",
                                timestamp = file.lastModified()
                            )
                            items.add(0, item) // Mostrar al inicio por ser descarga reciente
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error en descubrimiento directo de carpetas de video OmniVault", e)
        }

        items
    }
}
