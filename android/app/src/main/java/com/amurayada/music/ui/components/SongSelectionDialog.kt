package com.amurayada.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.amurayada.music.data.model.Song

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongSelectionSheet(
    allSongs: List<Song>,
    currentPlaylistSongIds: List<Long>,
    onDismiss: () -> Unit,
    onSongSelected: (Song) -> Unit,
    onSongsSelected: ((List<Song>) -> Unit)? = null
) {
    var searchQuery by remember { mutableStateOf("") }
    var sortByRecent by remember { mutableStateOf(true) }
    val selectedSongIds = remember { mutableStateListOf<Long>() }

    val filteredSongs = remember(searchQuery, allSongs, sortByRecent) {
        val sorted = if (sortByRecent) {
            allSongs.sortedByDescending { it.dateAdded }
        } else {
            allSongs.sortedBy { it.title.lowercase() }
        }
        if (searchQuery.isBlank()) {
            sorted
        } else {
            sorted.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                    it.artist.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Agregar canciones",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                if (selectedSongIds.isNotEmpty()) {
                    TextButton(onClick = { selectedSongIds.clear() }) {
                        Text("Limpiar (${selectedSongIds.size})")
                    }
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Buscar canción...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = sortByRecent,
                    onClick = { sortByRecent = true },
                    label = { Text("Más recientes") },
                    leadingIcon = if (sortByRecent) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null
                )
                FilterChip(
                    selected = !sortByRecent,
                    onClick = { sortByRecent = false },
                    label = { Text("A - Z") },
                    leadingIcon = if (!sortByRecent) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            LazyColumn(
                contentPadding = PaddingValues(bottom = 16.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(filteredSongs, key = { it.id }) { song ->
                    val isAlreadyInPlaylist = currentPlaylistSongIds.contains(song.id)
                    val isSelected = selectedSongIds.contains(song.id)

                    ListItem(
                        leadingContent = {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MusicNote,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp)
                                )
                                if (song.albumArtUri != null) {
                                    AsyncImage(
                                        model = song.albumArtUri,
                                        contentDescription = "Portada",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        },
                        headlineContent = {
                            Text(
                                text = song.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        supportingContent = {
                            Text(
                                text = song.artist,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        trailingContent = {
                            if (isAlreadyInPlaylist) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Agregada",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                val orderIndex = selectedSongIds.indexOf(song.id)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (orderIndex >= 0) {
                                        Surface(
                                            shape = androidx.compose.foundation.shape.CircleShape,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(22.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = "${orderIndex + 1}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onPrimary
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            if (checked) {
                                                if (!selectedSongIds.contains(song.id)) selectedSongIds.add(song.id)
                                            } else {
                                                selectedSongIds.remove(song.id)
                                            }
                                        }
                                    )
                                }
                            }
                        },
                        modifier = Modifier
                            .clickable(enabled = !isAlreadyInPlaylist) {
                                if (!isAlreadyInPlaylist) {
                                    if (isSelected) {
                                        selectedSongIds.remove(song.id)
                                    } else {
                                        selectedSongIds.add(song.id)
                                    }
                                }
                            }
                            .padding(horizontal = 8.dp)
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))
                }
            }

            if (selectedSongIds.isNotEmpty()) {
                Surface(
                    tonalElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = {
                            val songMap = allSongs.associateBy { it.id }
                            val chosen = selectedSongIds.mapNotNull { songMap[it] }
                            if (onSongsSelected != null) {
                                onSongsSelected(chosen)
                            } else {
                                chosen.forEach { onSongSelected(it) }
                            }
                            selectedSongIds.clear()
                            onDismiss()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "Agregar ${selectedSongIds.size} ${if (selectedSongIds.size == 1) "canción" else "canciones"}",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
