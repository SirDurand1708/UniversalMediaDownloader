package com.amurayada.music.ui.screens.music

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.amurayada.music.data.model.Playlist
import com.amurayada.music.data.model.Song
import com.amurayada.music.data.model.SongSort
import com.amurayada.music.ui.components.AddToPlaylistSheet
import com.amurayada.music.ui.viewmodel.LibraryViewModel
import com.amurayada.music.ui.viewmodel.PlaybackViewModel
import com.amurayada.music.ui.viewmodel.PlaylistViewModel

@Composable
internal fun LarkMusicDialogsHost(
    libraryViewModel: LibraryViewModel,
    playlistViewModel: PlaylistViewModel,
    playbackViewModel: PlaybackViewModel,
    playlists: List<Playlist>,
    currentSort: SongSort,
    songsToDeleteBatch: List<Song>,
    onClearSongsToDeleteBatch: () -> Unit,
    showSortDialog: Boolean,
    onDismissSortDialog: () -> Unit,
    songForPlaylist: Song?,
    onClearSongForPlaylist: () -> Unit,
    songsForPlaylist: List<Song>,
    onClearSongsForPlaylist: () -> Unit,
    showCreatePlaylistDialog: Boolean,
    onShowCreatePlaylistDialogChange: (Boolean) -> Unit,
    newPlaylistName: String,
    onNewPlaylistNameChange: (String) -> Unit,
    playlistToRename: Playlist?,
    onClearPlaylistToRename: () -> Unit,
    renamePlaylistText: String,
    onRenamePlaylistTextChange: (String) -> Unit,
    playlistToDelete: Playlist?,
    onClearPlaylistToDelete: () -> Unit,
    songToDelete: Song?,
    onClearSongToDelete: () -> Unit,
    songToEditTags: Song?,
    onClearSongToEditTags: () -> Unit,
    songForDetails: Song?,
    onClearSongForDetails: () -> Unit,
    showHiddenSongsDialog: Boolean,
    onDismissHiddenSongsDialog: () -> Unit
) {
    val context = LocalContext.current

    if (songsToDeleteBatch.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = onClearSongsToDeleteBatch,
            title = { Text("¿Eliminar ${songsToDeleteBatch.size} canciones?", fontWeight = FontWeight.Bold) },
            text = { Text("Se eliminarán permanentemente los archivos seleccionados de tu dispositivo.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3333)),
                    onClick = {
                        val batch = songsToDeleteBatch
                        onClearSongsToDeleteBatch()
                        batch.forEach { song ->
                            libraryViewModel.deleteSong(song, context) {}
                        }
                        Toast.makeText(context, "${batch.size} canciones eliminadas", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Eliminar", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = onClearSongsToDeleteBatch) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Diálogo de ordenamiento
    if (showSortDialog) {
        AlertDialog(
            onDismissRequest = onDismissSortDialog,
            title = { Text("Ordenar música por", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    SongSort.entries.forEach { sort ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    libraryViewModel.setSongSort(sort)
                                    onDismissSortDialog()
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentSort == sort,
                                onClick = {
                                    libraryViewModel.setSongSort(sort)
                                    onDismissSortDialog()
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(sort.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { libraryViewModel.setFilterShortAudio(!libraryViewModel.filterShortAudio) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Ocultar audios cortos", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("Filtra tonos y notas (< 15 s)", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                        Switch(
                            checked = libraryViewModel.filterShortAudio,
                            onCheckedChange = { libraryViewModel.setFilterShortAudio(it) }
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { libraryViewModel.setFilterCallRecordings(!libraryViewModel.filterCallRecordings) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Ocultar llamadas y notas de voz", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("Filtra grabaciones de llamadas y WhatsApp", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                        Switch(
                            checked = libraryViewModel.filterCallRecordings,
                            onCheckedChange = { libraryViewModel.setFilterCallRecordings(it) }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissSortDialog) {
                    Text("Cerrar")
                }
            }
        )
    }

    // Diálogo para añadir canción a playlist
    if (songForPlaylist != null && !showCreatePlaylistDialog) {
        val targetSong = songForPlaylist
        AddToPlaylistSheet(
            playlists = playlists,
            onDismiss = onClearSongForPlaylist,
            onPlaylistSelected = { playlist ->
                playlistViewModel.addSongToPlaylist(playlist.id, targetSong.id)
                onClearSongForPlaylist()
                Toast.makeText(context, "Añadido a ${playlist.name}", Toast.LENGTH_SHORT).show()
            },
            onCreateNewPlaylist = {
                onShowCreatePlaylistDialogChange(true)
            }
        )
    }

    // Diálogo para añadir múltiples canciones a playlist
    if (songsForPlaylist.isNotEmpty() && !showCreatePlaylistDialog) {
        AddToPlaylistSheet(
            playlists = playlists,
            onDismiss = onClearSongsForPlaylist,
            onPlaylistSelected = { playlist ->
                val count = songsForPlaylist.size
                playlistViewModel.addSongsToPlaylist(playlist.id, songsForPlaylist.map { it.id })
                onClearSongsForPlaylist()
                Toast.makeText(context, "$count canciones añadidas a ${playlist.name}", Toast.LENGTH_SHORT).show()
            },
            onCreateNewPlaylist = {
                onShowCreatePlaylistDialogChange(true)
            }
        )
    }

    // Diálogo para crear nueva playlist
    if (showCreatePlaylistDialog) {
        AlertDialog(
            onDismissRequest = {
                onShowCreatePlaylistDialogChange(false)
                onNewPlaylistNameChange("")
            },
            title = { Text("Nueva Playlist") },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = onNewPlaylistNameChange,
                    label = { Text("Nombre de la lista") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newPlaylistName.isNotBlank()) {
                            val pendingIds = buildList {
                                songForPlaylist?.let { add(it.id) }
                                addAll(songsForPlaylist.map { it.id })
                            }.distinct()
                            playlistViewModel.createPlaylist(newPlaylistName.trim(), pendingIds)
                            onClearSongForPlaylist()
                            onClearSongsForPlaylist()
                            libraryViewModel.loadLibrary()
                            onShowCreatePlaylistDialogChange(false)
                            val msg = if (pendingIds.isNotEmpty()) {
                                "Playlist \"$newPlaylistName\" creada con ${pendingIds.size} ${if (pendingIds.size == 1) "canción" else "canciones"}"
                            } else {
                                "Playlist \"$newPlaylistName\" creada"
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            onNewPlaylistNameChange("")
                        }
                    }
                ) {
                    Text("Crear")
                }
            },
            dismissButton = {
                TextButton(onClick = { onShowCreatePlaylistDialogChange(false) }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Diálogo para renombrar playlist
    playlistToRename?.let { pl ->
        AlertDialog(
            onDismissRequest = onClearPlaylistToRename,
            title = { Text("Renombrar Playlist", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renamePlaylistText,
                    onValueChange = onRenamePlaylistTextChange,
                    label = { Text("Nuevo nombre") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (renamePlaylistText.isNotBlank()) {
                            playlistViewModel.renamePlaylist(pl.id, renamePlaylistText.trim())
                            onClearPlaylistToRename()
                            Toast.makeText(context, "Playlist renombrada", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Guardar")
                }
            },
            dismissButton = {
                TextButton(onClick = onClearPlaylistToRename) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Diálogo para eliminar playlist
    playlistToDelete?.let { pl ->
        AlertDialog(
            onDismissRequest = onClearPlaylistToDelete,
            title = { Text("¿Eliminar Playlist?", fontWeight = FontWeight.Bold) },
            text = {
                Text("¿Deseas eliminar la playlist \"${pl.name}\"? Las canciones originales no se borrarán de tu dispositivo.")
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3333)),
                    onClick = {
                        val name = pl.name
                        playlistViewModel.deletePlaylist(pl.id)
                        onClearPlaylistToDelete()
                        Toast.makeText(context, "Playlist \"$name\" eliminada", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Eliminar", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = onClearPlaylistToDelete) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Diálogo para eliminar canción del almacenamiento local
    songToDelete?.let { song ->
        AlertDialog(
            onDismissRequest = onClearSongToDelete,
            title = { Text("¿Eliminar canción del dispositivo?", fontWeight = FontWeight.Bold) },
            text = {
                Text("Se eliminará permanentemente \"${song.title}\" de la memoria de tu dispositivo. Esta acción no se puede deshacer.")
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3333)),
                    onClick = {
                        val title = song.title
                        if (playbackViewModel.currentSong?.id == song.id) {
                            if (playbackViewModel.queue.size > 1) {
                                playbackViewModel.skipToNext()
                            } else {
                                playbackViewModel.pause()
                            }
                        }
                        playlistViewModel.playlists.value.forEach { playlist ->
                            if (playlist.songIds.contains(song.id)) {
                                playlistViewModel.removeSongFromPlaylist(playlist.id, song.id)
                            }
                        }
                        libraryViewModel.deleteSong(song, context) {
                            Toast.makeText(context, "Canción eliminada: $title", Toast.LENGTH_SHORT).show()
                        }
                        onClearSongToDelete()
                    }
                ) {
                    Text("Eliminar", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = onClearSongToDelete) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Diálogo Editor de Etiquetas (JAudioTagger + MediaStore + DownloadDatabase)
    songToEditTags?.let { song ->
        var editTitle by remember(song.id) { mutableStateOf(song.title) }
        var editArtist by remember(song.id) { mutableStateOf(song.artist) }
        var editAlbum by remember(song.id) { mutableStateOf(song.album) }
        AlertDialog(
            onDismissRequest = onClearSongToEditTags,
            title = { Text("Editar etiquetas", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text("Título") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editArtist,
                        onValueChange = { editArtist = it },
                        label = { Text("Artista") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editAlbum,
                        onValueChange = { editAlbum = it },
                        label = { Text("Álbum") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = song
                        onClearSongToEditTags()
                        libraryViewModel.updateSongMetadata(
                            context = context,
                            song = target,
                            newTitle = editTitle,
                            newArtist = editArtist,
                            newAlbum = editAlbum
                        ) { _, msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Guardar")
                }
            },
            dismissButton = {
                TextButton(onClick = onClearSongToEditTags) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Diálogo Detalles del archivo
    songForDetails?.let { song ->
        val fmtLabel = resolveAudioFormatLabel(song)
        val durLabel = if (song.duration > 0L) com.amurayada.music.ui.screens.nowplaying.formatDuration(song.duration) else "Desconocida"
        val sizeLabel = if (song.fileSize > 0L) com.omnivault.media.utils.StorageHelper.formatFileSize(song.fileSize) else "No disponible"
        AlertDialog(
            onDismissRequest = onClearSongForDetails,
            title = { Text("Detalles del archivo", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Título: ${song.title}", style = MaterialTheme.typography.bodySmall)
                    Text("Artista: ${song.artist}", style = MaterialTheme.typography.bodySmall)
                    Text("Álbum: ${song.album}", style = MaterialTheme.typography.bodySmall)
                    Text("Formato: $fmtLabel${song.mimeType?.let { " ($it)" } ?: ""}", style = MaterialTheme.typography.bodySmall)
                    Text("Duración: $durLabel", style = MaterialTheme.typography.bodySmall)
                    Text("Tamaño: $sizeLabel", style = MaterialTheme.typography.bodySmall)
                    Text("Ubicación: ${song.path}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            },
            confirmButton = {
                TextButton(onClick = onClearSongForDetails) {
                    Text("Cerrar")
                }
            }
        )
    }

    // Gestor ligero de canciones ocultadas
    if (showHiddenSongsDialog) {
        val hiddenList = libraryViewModel.hiddenSongs
        AlertDialog(
            onDismissRequest = onDismissHiddenSongsDialog,
            title = { Text("Canciones ocultas (${hiddenList.size})", fontWeight = FontWeight.Bold) },
            text = {
                if (hiddenList.isEmpty()) {
                    Text("No hay canciones ocultas.")
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(hiddenList, key = { it.id }) { hSong ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(hSong.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(hSong.artist, style = MaterialTheme.typography.labelSmall, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                TextButton(onClick = { libraryViewModel.unhideSong(hSong.id) }) {
                                    Text("Restaurar")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (hiddenList.isNotEmpty()) {
                    Button(onClick = {
                        libraryViewModel.unhideAllSongs()
                        onDismissHiddenSongsDialog()
                        Toast.makeText(context, "Todas las canciones restauradas", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Restaurar todas")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissHiddenSongsDialog) {
                    Text("Cerrar")
                }
            }
        )
    }
}
