package com.amurayada.music.data.repository

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.amurayada.music.data.model.Album
import com.amurayada.music.data.model.Artist
import com.amurayada.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaRepository(private val context: Context) {

    suspend fun getAllSongs(): List<Song> = withContext(Dispatchers.IO) {
        val songs = mutableListOf<Song>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.ALBUM_ID
        )

        val prefs = context.getSharedPreferences("music_settings", Context.MODE_PRIVATE)
        val filterShortAudio = prefs.getBoolean("filter_short_audio", true)
        val selection = if (filterShortAudio) {
            "(${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.SIZE} >= 30000) AND ${MediaStore.Audio.Media.DURATION} >= 30000"
        } else {
            "(${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.SIZE} >= 30000)"
        }
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
            val durationColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
            val dataColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            val dateAddedColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
            val albumIdColumn = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)

            if (idColumn == -1) return@use

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = if (titleColumn != -1) cursor.getString(titleColumn) ?: "Unknown" else "Unknown"
                val artist = if (artistColumn != -1) cursor.getString(artistColumn) ?: "Unknown Artist" else "Unknown Artist"
                val album = if (albumColumn != -1) cursor.getString(albumColumn) ?: "Unknown Album" else "Unknown Album"
                val duration = if (durationColumn != -1) cursor.getLong(durationColumn) else 0L
                val dateAdded = if (dateAddedColumn != -1) cursor.getLong(dateAddedColumn) else 0L
                val albumId = if (albumIdColumn != -1) cursor.getLong(albumIdColumn) else 0L
                val rawPath = if (dataColumn != -1) cursor.getString(dataColumn) else null

                val albumArtUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    albumId
                )

                // Use content:// URI instead of absolute path for better Scoped Storage compatibility
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    id
                ).toString()

                songs.add(
                    Song(
                        id = id,
                        title = title,
                        artist = artist,
                        album = album,
                        duration = duration,
                        albumArtUri = albumArtUri,
                        path = if (!rawPath.isNullOrBlank()) rawPath else contentUri,
                        dateAdded = dateAdded,
                        albumId = albumId
                    )
                )
            }
        }
        songs
    }

    suspend fun getAllAlbums(): List<Album> = withContext(Dispatchers.IO) {
        val albums = mutableListOf<Album>()
        val projection = arrayOf(
            MediaStore.Audio.Albums._ID,
            MediaStore.Audio.Albums.ALBUM,
            MediaStore.Audio.Albums.ARTIST,
            MediaStore.Audio.Albums.FIRST_YEAR,
            MediaStore.Audio.Albums.NUMBER_OF_SONGS
        )

        val sortOrder = "${MediaStore.Audio.Albums.ALBUM} ASC"

        context.contentResolver.query(
            MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndex(MediaStore.Audio.Albums._ID)
            val albumColumn = cursor.getColumnIndex(MediaStore.Audio.Albums.ALBUM)
            val artistColumn = cursor.getColumnIndex(MediaStore.Audio.Albums.ARTIST)
            val yearColumn = cursor.getColumnIndex(MediaStore.Audio.Albums.FIRST_YEAR)
            val songCountColumn = cursor.getColumnIndex(MediaStore.Audio.Albums.NUMBER_OF_SONGS)

            if (idColumn == -1) return@use

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = if (albumColumn != -1) cursor.getString(albumColumn) ?: "Unknown Album" else "Unknown Album"
                val artist = if (artistColumn != -1) cursor.getString(artistColumn) ?: "Unknown Artist" else "Unknown Artist"
                val year = if (yearColumn != -1) cursor.getInt(yearColumn) else 0
                val songCount = if (songCountColumn != -1) cursor.getInt(songCountColumn) else 0

                val artworkUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    id
                )

                albums.add(
                    Album(
                        id = id,
                        name = name,
                        artist = artist,
                        artworkUri = artworkUri,
                        year = year,
                        songCount = songCount
                    )
                )
            }
        }
        albums
    }

    suspend fun getAllArtists(): List<Artist> = withContext(Dispatchers.IO) {
        val artists = mutableListOf<Artist>()
        val projection = arrayOf(
            MediaStore.Audio.Artists._ID,
            MediaStore.Audio.Artists.ARTIST,
            MediaStore.Audio.Artists.NUMBER_OF_ALBUMS,
            MediaStore.Audio.Artists.NUMBER_OF_TRACKS
        )

        val sortOrder = "${MediaStore.Audio.Artists.ARTIST} ASC"

        context.contentResolver.query(
            MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndex(MediaStore.Audio.Artists._ID)
            val artistColumn = cursor.getColumnIndex(MediaStore.Audio.Artists.ARTIST)
            val albumCountColumn = cursor.getColumnIndex(MediaStore.Audio.Artists.NUMBER_OF_ALBUMS)
            val songCountColumn = cursor.getColumnIndex(MediaStore.Audio.Artists.NUMBER_OF_TRACKS)

            if (idColumn == -1) return@use

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = if (artistColumn != -1) cursor.getString(artistColumn) ?: "Unknown Artist" else "Unknown Artist"
                val albumCount = if (albumCountColumn != -1) cursor.getInt(albumCountColumn) else 0
                val songCount = if (songCountColumn != -1) cursor.getInt(songCountColumn) else 0

                artists.add(
                    Artist(
                        id = id,
                        name = name,
                        albumCount = albumCount,
                        songCount = songCount
                    )
                )
            }
        }
        artists
    }

    suspend fun getSongsByAlbum(albumId: Long): List<Song> = withContext(Dispatchers.IO) {
        getAllSongs().filter { it.albumId == albumId }
    }

    suspend fun getSongsByArtist(artistName: String): List<Song> = withContext(Dispatchers.IO) {
        getAllSongs().filter { it.artist == artistName }
    }

    suspend fun getAllGenres(): List<com.amurayada.music.data.model.Genre> = withContext(Dispatchers.IO) {
        val genres = mutableListOf<com.amurayada.music.data.model.Genre>()
        val projection = arrayOf(
            MediaStore.Audio.Genres._ID,
            MediaStore.Audio.Genres.NAME
        )
        
        val sortOrder = "${MediaStore.Audio.Genres.NAME} ASC"
        
        context.contentResolver.query(
            MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndex(MediaStore.Audio.Genres._ID)
            val nameColumn = cursor.getColumnIndex(MediaStore.Audio.Genres.NAME)
            if (idColumn == -1) return@use
            
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = if (nameColumn != -1) cursor.getString(nameColumn) ?: "Unknown Genre" else "Unknown Genre"
                
                var songCount = 0
                val membersUri = MediaStore.Audio.Genres.Members.getContentUri("external", id)
                try {
                    context.contentResolver.query(
                        membersUri,
                        arrayOf(MediaStore.Audio.Media._ID),
                        null,
                        null,
                        null
                    )?.use { membersCursor ->
                        songCount = membersCursor.count
                    }
                } catch (_: Exception) {}
                
                if (songCount > 0) {
                    genres.add(
                        com.amurayada.music.data.model.Genre(
                            id = id,
                            name = name,
                            songCount = songCount
                        )
                    )
                }
            }
        }
        genres
    }
    suspend fun getSongsByGenre(genreId: Long): List<Song> = withContext(Dispatchers.IO) {
        val songs = mutableListOf<Song>()
        val uri = MediaStore.Audio.Genres.Members.getContentUri("external", genreId)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.ALBUM_ID
        )

        val prefs = context.getSharedPreferences("music_settings", Context.MODE_PRIVATE)
        val filterShortAudio = prefs.getBoolean("filter_short_audio", true)
        val selection = if (filterShortAudio) {
            "(${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.SIZE} >= 30000) AND ${MediaStore.Audio.Media.DURATION} >= 30000"
        } else {
            "(${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.SIZE} >= 30000)"
        }
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        context.contentResolver.query(
            uri,
            projection,
            selection,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
            val durationColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
            val dataColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            val dateAddedColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
            val albumIdColumn = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)

            if (idColumn == -1) return@use

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = if (titleColumn != -1) cursor.getString(titleColumn) ?: "Unknown" else "Unknown"
                val artist = if (artistColumn != -1) cursor.getString(artistColumn) ?: "Unknown Artist" else "Unknown Artist"
                val album = if (albumColumn != -1) cursor.getString(albumColumn) ?: "Unknown Album" else "Unknown Album"
                val duration = if (durationColumn != -1) cursor.getLong(durationColumn) else 0L
                val dateAdded = if (dateAddedColumn != -1) cursor.getLong(dateAddedColumn) else 0L
                val albumId = if (albumIdColumn != -1) cursor.getLong(albumIdColumn) else 0L
                val rawPath = if (dataColumn != -1) cursor.getString(dataColumn) else null

                val albumArtUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    albumId
                )

                // Use content:// URI instead of absolute path
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    id
                ).toString()

                songs.add(
                    Song(
                        id = id,
                        title = title,
                        artist = artist,
                        album = album,
                        duration = duration,
                        albumArtUri = albumArtUri,
                        path = contentUri,
                        dateAdded = dateAdded,
                        albumId = albumId
                    )
                )
            }
        }
        songs
    }

    suspend fun deleteSong(songId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId)
            val rowsDeleted = context.contentResolver.delete(uri, null, null)
            rowsDeleted > 0
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun getGenreForAudio(audioId: Long): String? = withContext(Dispatchers.IO) {
        var genre: String? = null
        val uri = MediaStore.Audio.Genres.getContentUriForAudioId("external", audioId.toInt())
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Audio.Genres.NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                genre = cursor.getString(0)
            }
        }
        genre
    }

    suspend fun updateAlbum(albumId: Long, newTitle: String, newArtist: String, newGenre: String, imageUri: android.net.Uri?): Result<Long> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val songIds = mutableListOf<Long>()
            val pathsToScan = mutableListOf<String>()
            
            val cursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA),
                "${MediaStore.Audio.Media.ALBUM_ID} = ?",
                arrayOf(albumId.toString()),
                null
            )
            
            cursor?.use { c ->
                val idCol = c.getColumnIndex(MediaStore.Audio.Media._ID)
                val dataCol = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                if (idCol != -1) {
                    while (c.moveToNext()) {
                        songIds.add(c.getLong(idCol))
                        if (dataCol != -1) {
                            c.getString(dataCol)?.let { pathsToScan.add(it) }
                        }
                    }
                }
            }

            if (songIds.isEmpty()) {
                return@withContext Result.failure(Exception("No se encontraron canciones para este álbum (ID: $albumId)"))
            }

            var totalUpdated = 0
            val urisNeedingPermission = mutableListOf<android.net.Uri>()

            var artwork: org.jaudiotagger.tag.images.Artwork? = null
            if (imageUri != null) {
                try {
                    val tempImageFile = java.io.File(context.cacheDir, "temp_cover_${System.currentTimeMillis()}.jpg")
                    context.contentResolver.openInputStream(imageUri)?.use { input ->
                        java.io.FileOutputStream(tempImageFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    artwork = org.jaudiotagger.tag.images.StandardArtwork.createArtworkFromFile(tempImageFile)
                    tempImageFile.delete()
                } catch (e: Exception) {
                    android.util.Log.e("MediaRepository", "Error preparing artwork", e)
                }
            }

            for (i in pathsToScan.indices) {
                val path = pathsToScan[i]
                val id = songIds[i]
                val uri = android.content.ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                
                try {
                    val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
                    if (pfd == null) {
                        urisNeedingPermission.add(uri)
                        continue
                    }
                    
                    pfd.use { descriptor ->
                        val extension = path.substringAfterLast('.', "mp3")
                        val tempFile = java.io.File(context.cacheDir, "temp_audio_${System.currentTimeMillis()}.$extension")
                        try {
                            java.io.FileInputStream(descriptor.fileDescriptor).use { input ->
                                java.io.FileOutputStream(tempFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            
                            val audioFile = org.jaudiotagger.audio.AudioFileIO.read(tempFile)
                            val tag = audioFile.tagOrCreateAndSetDefault
                            
                            tag.setField(org.jaudiotagger.tag.FieldKey.ALBUM, newTitle)
                            tag.setField(org.jaudiotagger.tag.FieldKey.ARTIST, newArtist)
                            
                            if (newGenre.isNotBlank()) {
                                tag.setField(org.jaudiotagger.tag.FieldKey.GENRE, newGenre)
                            }
                            
                            if (artwork != null) {
                                tag.deleteArtworkField()
                                tag.setField(artwork)
                            }
                            
                            audioFile.commit()
                            
                            context.contentResolver.openFileDescriptor(uri, "wt")?.use { writePfd ->
                                java.io.FileInputStream(tempFile).use { input ->
                                    java.io.FileOutputStream(writePfd.fileDescriptor).use { output ->
                                        input.copyTo(output)
                                    }
                                }
                            }
                            
                            totalUpdated++
                        } finally {
                            tempFile.delete()
                        }
                    }
                } catch (e: SecurityException) {
                    urisNeedingPermission.add(uri)
                } catch (e: Exception) {
                    android.util.Log.e("MediaRepository", "Error updating tags for $uri: ${e.message}", e)
                    urisNeedingPermission.add(uri)
                }
            }

            if (urisNeedingPermission.isNotEmpty()) {
                 if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                     val pendingIntent = MediaStore.createWriteRequest(context.contentResolver, urisNeedingPermission)
                     throw RequiresPermissionException(pendingIntent.intentSender)
                 }
            }
            
            if (totalUpdated == 0 && songIds.isNotEmpty()) {
                 return@withContext Result.failure(Exception("No se pudo actualizar ninguna canción. Verifique permisos."))
            }
            
            org.jaudiotagger.tag.TagOptionSingleton.getInstance().iD3V2Version = org.jaudiotagger.tag.reference.ID3V2Version.ID3_V23
            
            if (pathsToScan.isNotEmpty() && totalUpdated > 0) {
                kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                    var completed = 0
                    android.media.MediaScannerConnection.scanFile(context, pathsToScan.toTypedArray(), null) { path, uri ->
                        if (uri != null) context.contentResolver.notifyChange(uri, null)
                        completed++
                        if (completed >= pathsToScan.size && cont.isActive) cont.resume(Unit) {}
                    }
                }
                kotlinx.coroutines.delay(1000)
            }
            
            return@withContext Result.success(albumId)
        } catch (e: SecurityException) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val songIds = mutableListOf<Long>()
                val cursor = context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media._ID),
                    "${MediaStore.Audio.Media.ALBUM_ID} = ?",
                    arrayOf(albumId.toString()),
                    null
                )
                cursor?.use { c ->
                    val idCol = c.getColumnIndex(MediaStore.Audio.Media._ID)
                    if (idCol != -1) {
                        while (c.moveToNext()) songIds.add(c.getLong(idCol))
                    }
                }
                
                if (songIds.isNotEmpty()) {
                    val uris = songIds.map { android.content.ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) }
                    val pendingIntent = MediaStore.createWriteRequest(context.contentResolver, uris)
                    throw RequiresPermissionException(pendingIntent.intentSender)
                }
            }
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext Result.failure(e)
        }
    }

    /**
     * Tries to find a song in MediaStore by title and artist.
     * Useful for recovering from broken SAF URIs or absolute paths.
     */
    suspend fun findSongUriInMediaStore(title: String, artist: String): Uri? = withContext(Dispatchers.IO) {
        val projection = arrayOf(MediaStore.Audio.Media._ID)
        
        // Strategy 1: Title and Artist (Strict)
        val selectionStrict = "${MediaStore.Audio.Media.TITLE} = ? AND ${MediaStore.Audio.Media.ARTIST} = ?"
        val selectionArgsStrict = arrayOf(title, artist)
        
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selectionStrict,
                selectionArgsStrict,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                if (idCol != -1 && cursor.moveToFirst()) {
                    val id = cursor.getLong(idCol)
                    return@withContext ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaRepo", "Error searching strictly (title+artist): ${e.message}")
        }
        
        // Strategy 2: Title only (Relaxed)
        if (title.isNotEmpty()) {
            val selectionRelaxed = "${MediaStore.Audio.Media.TITLE} = ?"
            val selectionArgsRelaxed = arrayOf(title)
            try {
                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selectionRelaxed,
                    selectionArgsRelaxed,
                    null
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                    if (idCol != -1 && cursor.moveToFirst()) {
                        val id = cursor.getLong(idCol)
                        return@withContext ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MediaRepo", "Error searching relaxed (title): ${e.message}")
            }
        }

        null
    }

    /**
     * Tries to find a song in MediaStore by its filename (DISPLAY_NAME).
     */
    suspend fun findSongUriInMediaStoreByFilename(filename: String): Uri? = withContext(Dispatchers.IO) {
        val projection = arrayOf(MediaStore.Audio.Media._ID)
        val selection = "${MediaStore.Audio.Media.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(filename)
        
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                if (idCol != -1 && cursor.moveToFirst()) {
                    val id = cursor.getLong(idCol)
                    return@withContext ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaRepo", "Error searching by filename: ${e.message}")
        }
        null
    }
    class RequiresPermissionException(val intentSender: android.content.IntentSender) : Exception("Permission required")
}
