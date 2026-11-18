package com.amurayada.music.ui.screens.music

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.amurayada.music.data.model.Song
import com.amurayada.music.ui.components.SongListItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderDetailScreen(
    folderPath: String,
    songs: List<Song>,
    currentSong: Song?,
    isPlaying: Boolean,
    onBackClick: () -> Unit,
    onSongClick: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val folderTitle = remember(folderPath) {
        folderPath.substringAfterLast('/').ifBlank { folderPath }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Volver"
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = folderTitle,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${songs.size} canciones • $folderPath",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        if (songs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No hay canciones en esta carpeta",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { onSongClick(songs.first()) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Reproducir")
                        }
                        FilledTonalButton(
                            onClick = {
                                val shuffled = songs.shuffled()
                                onSongClick(shuffled.first())
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Rounded.Shuffle, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Aleatorio")
                        }
                    }
                }

                itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                    var showMenu by remember { mutableStateOf(false) }
                    SongListItem(
                        song = song,
                        onClick = { onSongClick(song) },
                        index = index + 1,
                        isCurrentSong = currentSong?.id == song.id,
                        isPlaying = isPlaying && currentSong?.id == song.id,
                        trailingContent = {
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(Icons.Rounded.MoreVert, contentDescription = "Opciones")
                                }
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Reproducir a continuación") },
                                        leadingIcon = { Icon(Icons.Rounded.QueueMusic, contentDescription = null) },
                                        onClick = {
                                            showMenu = false
                                            onPlayNext(song)
                                            Toast.makeText(context, "Se reproducirá a continuación", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Añadir a la cola") },
                                        leadingIcon = { Icon(Icons.Rounded.AddToQueue, contentDescription = null) },
                                        onClick = {
                                            showMenu = false
                                            onAddToQueue(song)
                                            Toast.makeText(context, "Añadido a la cola", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}
