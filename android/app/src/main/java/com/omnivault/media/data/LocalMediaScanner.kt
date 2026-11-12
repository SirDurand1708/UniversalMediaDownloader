package com.omnivault.media.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object LocalMediaScanner {
    private const val TAG = "LocalMediaScanner"

    /**
     * Escanea la biblioteca de audio del dispositivo mediante MediaStore y la carpeta oficial
     * de OmniVault (Music/OmniVault), extrayendo metadatos, duración y carátulas nativas.
     */
    suspend fun scanDeviceAudio(context: Context): List<TrackEntity> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<TrackEntity>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 5000"

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
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol) ?: "Audio"
                    val artist = cursor.getString(artistCol) ?: "Desconocido"
                    val album = cursor.getString(albumCol) ?: "OmniVault"
                    val duration = cursor.getLong(durCol)
                    val path = cursor.getString(dataCol) ?: continue
                    val albumId = cursor.getLong(albumIdCol)

                    val file = File(path)
                    if (!file.exists()) continue

                    val sArtworkUri = Uri.parse("content://media/external/audio/albumart")
                    val coverUri = ContentUris.withAppendedId(sArtworkUri, albumId).toString()

                    tracks.add(
                        TrackEntity(
                            id = "media_$id",
                            title = title,
                            artist = if (artist == "<unknown>") "Desconocido" else artist,
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

        // También sincronizar las pistas encontradas en la base de datos de Room
        try {
            val db = AppDatabase.getInstance(context)
            db.trackDao().insertTracks(tracks)
        } catch (e: Exception) {
            Log.e(TAG, "Error persistiendo pistas en Room", e)
        }

        tracks
    }
}
