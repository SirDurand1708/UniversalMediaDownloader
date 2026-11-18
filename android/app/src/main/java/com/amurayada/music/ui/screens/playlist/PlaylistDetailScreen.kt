package com.amurayada.music.ui.screens.playlist

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.amurayada.music.data.model.Playlist
import com.amurayada.music.data.model.Song

enum class PlaylistSort(val label: String) {
    RECENTLY_ADDED("Más recientes añadidos"),
    NAME_ASC("A - Z"),
    NAME_DESC("Z - A"),
    MANUAL("Organizar manualmente");

    companion object {
        fun fromName(raw: String?): PlaylistSort =
            entries.firstOrNull { it.name == raw } ?: RECENTLY_ADDED
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlist: Playlist?,
    songs: List<Song>,
    allSongs: List<Song> = emptyList(),
    onBackClick: () -> Unit,
    onSongClick: (Song) -> Unit,
    onRemoveSong: (Long) -> Unit,
    onAddSong: (Song) -> Unit = {},
    onAddSongs: ((List<Song>) -> Unit)? = null,
    onMoveSong: ((Int, Int) -> Unit)? = null,
    onPlayOrderedList: ((Song, List<Song>) -> Unit)? = null,
    onRenamePlaylist: ((String) -> Unit)? = null,
    onDeletePlaylist: (() -> Unit)? = null,
    onAddPlaylistToQueue: ((List<Song>) -> Unit)? = null,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit
) {
    if (playlist == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Playlist no encontrada")
        }
        return
    }

    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("omnivault_prefs", Context.MODE_PRIVATE) }
    var currentSort by remember(playlist.id) {
        mutableStateOf(PlaylistSort.fromName(prefs.getString("playlist_sort_${playlist.id}", PlaylistSort.RECENTLY_ADDED.name)))
    }

    var showAddSongDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember(playlist.name) { mutableStateOf(playlist.name) }

    val displayedSongs = remember(songs, currentSort) {
        when (currentSort) {
            PlaylistSort.RECENTLY_ADDED -> songs.reversed()
            PlaylistSort.NAME_ASC -> songs.sortedBy { it.title.lowercase() }
            PlaylistSort.NAME_DESC -> songs.sortedByDescending { it.title.lowercase() }
            PlaylistSort.MANUAL -> songs
        }
    }

    val totalSeconds = remember(songs) { songs.sumOf { it.duration } / 1000L }
    val totalDurationText = remember(totalSeconds) {
        val hours = totalSeconds / 3600
        val mins = (totalSeconds % 3600) / 60
        if (hours > 0) "${hours}h ${mins}m" else "${mins} min"
    }

    val coverArtUri = remember(songs, playlist.artworkUri) {
        songs.firstOrNull { it.albumArtUri != null }?.albumArtUri ?: playlist.artworkUri
    }

    val listState = rememberLazyListState()
    val hapticFeedback = LocalHapticFeedback.current
    val density = LocalDensity.current
    val itemHeightPx = with(density) { 68.dp.toPx() }
    val headerItemCount = 1

    var draggingIndex by remember { mutableIntStateOf(-1) }
    var draggingOffsetY by remember { mutableFloatStateOf(0f) }
    var pointerViewportY by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        val containerHeightPx = with(density) { maxHeight.toPx() }

        LaunchedEffect(draggingIndex, pointerViewportY) {
            if (draggingIndex >= 0 && currentSort == PlaylistSort.MANUAL) {
                val threshold = 160f
                val maxSpeed = 24f
                while (draggingIndex >= 0) {
                    var scrollDelta = 0f
                    if (pointerViewportY < threshold) {
                        scrollDelta = -maxSpeed * ((threshold - pointerViewportY) / threshold)
                    } else if (pointerViewportY > containerHeightPx - threshold) {
                        scrollDelta = maxSpeed * ((pointerViewportY - (containerHeightPx - threshold)) / threshold)
                    }
                    if (scrollDelta != 0f) {
                        listState.scrollBy(scrollDelta)
                    }
                    withFrameNanos { }
                }
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Barra superior flotante estilo captura: < a la izquierda, cápsula (Ordenar | ⋮) a la derecha
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = onBackClick,
                    shape = CircleShape,
                    color = Color(0xFF1A202C).copy(alpha = 0.85f),
                    border = BorderStroke(1.dp, Color(0xFF2D3748)),
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Atrás",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color(0xFF1A202C).copy(alpha = 0.85f),
                    border = BorderStroke(1.dp, Color(0xFF2D3748))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Box {
                            IconButton(
                                onClick = { showSortMenu = true },
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.Sort,
                                    contentDescription = "Ordenar playlist",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                PlaylistSort.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = option.label,
                                                fontWeight = if (currentSort == option) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        leadingIcon = {
                                            if (currentSort == option) {
                                                Icon(Icons.Rounded.Check, contentDescription = null, tint = Color(0xFF00E5FF))
                                            }
                                        },
                                        onClick = {
                                            currentSort = option
                                            prefs.edit().putString("playlist_sort_${playlist.id}", option.name).apply()
                                            showSortMenu = false
                                        }
                                    )
                                }
                            }
                        }

                        Box {
                            IconButton(
                                onClick = { showMoreMenu = true },
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.MoreVert,
                                    contentDescription = "Más opciones",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Agregar canciones") },
                                    leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                                    onClick = {
                                        showMoreMenu = false
                                        showAddSongDialog = true
                                    }
                                )
                                if (onAddPlaylistToQueue != null && displayedSongs.isNotEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("Añadir playlist a la cola") },
                                        leadingIcon = { Icon(Icons.Rounded.QueueMusic, contentDescription = null) },
                                        onClick = {
                                            showMoreMenu = false
                                            onAddPlaylistToQueue(displayedSongs)
                                        }
                                    )
                                }
                                if (onRenamePlaylist != null) {
                                    DropdownMenuItem(
                                        text = { Text("Renombrar playlist") },
                                        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                        onClick = {
                                            showMoreMenu = false
                                            renameText = playlist.name
                                            showRenameDialog = true
                                        }
                                    )
                                }
                                if (onDeletePlaylist != null) {
                                    DropdownMenuItem(
                                        text = { Text("Eliminar playlist", color = Color(0xFFFF5252)) },
                                        leadingIcon = {
                                            Icon(Icons.Rounded.Delete, contentDescription = null, tint = Color(0xFFFF5252))
                                        },
                                        onClick = {
                                            showMoreMenu = false
                                            onDeletePlaylist()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                // Cabecera Hero centrada
                item(key = "playlist_hero_header") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Color(0xFF141923),
                            border = BorderStroke(1.dp, Color(0xFF263248)),
                            modifier = Modifier.size(164.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (coverArtUri != null) {
                                    AsyncImage(
                                        model = coverArtUri,
                                        contentDescription = playlist.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(
                                        Icons.Rounded.MusicNote,
                                        contentDescription = null,
                                        tint = Color(0xFF00E5FF),
                                        modifier = Modifier.size(64.dp)
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        ) {
                            Text(
                                text = playlist.name,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                            if (onRenamePlaylist != null) {
                                Spacer(Modifier.width(6.dp))
                                IconButton(
                                    onClick = {
                                        renameText = playlist.name
                                        showRenameDialog = true
                                    },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.Edit,
                                        contentDescription = "Renombrar playlist",
                                        tint = Color.White.copy(alpha = 0.8f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(4.dp))

                        Text(
                            text = if (songs.isNotEmpty()) "${songs.size} Canciones • $totalDurationText" else "0 Canciones",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.65f)
                        )

                        Spacer(Modifier.height(18.dp))

                        // Botones cápsula Reproducir / Aleatorio
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                onClick = {
                                    if (displayedSongs.isNotEmpty()) {
                                        if (onPlayOrderedList != null) {
                                            onPlayOrderedList(displayedSongs.first(), displayedSongs)
                                        } else {
                                            onPlayAll()
                                        }
                                    }
                                },
                                enabled = displayedSongs.isNotEmpty(),
                                shape = RoundedCornerShape(50),
                                color = Color(0xFF1E2430),
                                border = BorderStroke(1.dp, Color(0xFF2D3748)),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.PlayArrow,
                                        contentDescription = null,
                                        tint = Color(0xFF00B0FF),
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "Reproducir",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }

                            Surface(
                                onClick = onShuffle,
                                enabled = displayedSongs.isNotEmpty(),
                                shape = RoundedCornerShape(50),
                                color = Color(0xFF1E2430),
                                border = BorderStroke(1.dp, Color(0xFF2D3748)),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.Shuffle,
                                        contentDescription = null,
                                        tint = Color(0xFF00B0FF),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "Aleatorio",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }

                        if (currentSort == PlaylistSort.MANUAL && displayedSongs.size > 1) {
                            Spacer(Modifier.height(12.dp))
                            Surface(
                                color = Color(0xFF00E5FF).copy(alpha = 0.12f),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.35f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Rounded.DragHandle,
                                        contentDescription = null,
                                        tint = Color(0xFF00E5FF),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "Mantén presionada una canción y arrástrala para ordenar",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF00E5FF)
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                    }
                }

                if (displayedSongs.isEmpty()) {
                    item(key = "playlist_empty") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Esta playlist está vacía",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.Gray
                            )
                            Spacer(Modifier.height(14.dp))
                            Button(onClick = { showAddSongDialog = true }) {
                                Icon(Icons.Rounded.Add, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Agregar canciones")
                            }
                        }
                    }
                } else {
                    itemsIndexed(displayedSongs, key = { _, song -> song.id }) { index, song ->
                        val currentIndex by rememberUpdatedState(index)
                        val isDragging = index == draggingIndex
                        val dragModifier = if (currentSort == PlaylistSort.MANUAL && onMoveSong != null) {
                            Modifier.pointerInput(song.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { startOffset ->
                                        val startIdx = currentIndex
                                        draggingIndex = startIdx
                                        draggingOffsetY = 0f
                                        val itemInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                                            it.index == startIdx + headerItemCount
                                        }
                                        pointerViewportY = (itemInfo?.offset?.toFloat() ?: 0f) + startOffset.y
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        draggingOffsetY += dragAmount.y
                                        pointerViewportY += dragAmount.y
                                        val curIdx = draggingIndex
                                        if (curIdx >= 0) {
                                            if (draggingOffsetY > itemHeightPx * 0.65f && curIdx < displayedSongs.lastIndex) {
                                                onMoveSong(curIdx, curIdx + 1)
                                                draggingIndex = curIdx + 1
                                                draggingOffsetY -= itemHeightPx
                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            } else if (draggingOffsetY < -itemHeightPx * 0.65f && curIdx > 0) {
                                                onMoveSong(curIdx, curIdx - 1)
                                                draggingIndex = curIdx - 1
                                                draggingOffsetY += itemHeightPx
                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        draggingIndex = -1
                                        draggingOffsetY = 0f
                                    },
                                    onDragCancel = {
                                        draggingIndex = -1
                                        draggingOffsetY = 0f
                                    }
                                )
                            }
                        } else Modifier

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .zIndex(if (isDragging) 1f else 0f)
                                .graphicsLayer {
                                    translationY = if (isDragging) draggingOffsetY else 0f
                                    scaleX = if (isDragging) 1.02f else 1f
                                    scaleY = if (isDragging) 1.02f else 1f
                                }
                                .background(if (isDragging) Color(0xFF1E293B) else Color.Transparent)
                                .then(dragModifier)
                                .clickable {
                                    if (onPlayOrderedList != null) {
                                        onPlayOrderedList(song, displayedSongs)
                                    } else {
                                        onSongClick(song)
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF141923)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (song.albumArtUri != null) {
                                    AsyncImage(
                                        model = song.albumArtUri,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(
                                        Icons.Rounded.MusicNote,
                                        contentDescription = null,
                                        tint = Color(0xFF00E5FF),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }

                            Spacer(Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = song.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = song.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.6f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            if (currentSort == PlaylistSort.MANUAL) {
                                Icon(
                                    Icons.Rounded.DragHandle,
                                    contentDescription = "Arrastrar para ordenar",
                                    tint = Color.White.copy(alpha = 0.55f),
                                    modifier = Modifier.padding(horizontal = 6.dp)
                                )
                            }

                            IconButton(
                                onClick = { onRemoveSong(song.id) },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = "Quitar de la playlist",
                                    tint = Color.White.copy(alpha = 0.5f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showAddSongDialog) {
            com.amurayada.music.ui.components.SongSelectionSheet(
                allSongs = allSongs,
                currentPlaylistSongIds = playlist.songIds,
                onDismiss = { showAddSongDialog = false },
                onSongSelected = { song -> onAddSong(song) },
                onSongsSelected = onAddSongs
            )
        }

        if (showRenameDialog && onRenamePlaylist != null) {
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title = { Text("Renombrar Playlist", fontWeight = FontWeight.Bold) },
                text = {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text("Nuevo nombre") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val trimmed = renameText.trim()
                            if (trimmed.isNotEmpty()) {
                                onRenamePlaylist(trimmed)
                                showRenameDialog = false
                                Toast.makeText(context, "Playlist renombrada", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text("Guardar")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameDialog = false }) {
                        Text("Cancelar")
                    }
                }
            )
        }
    }
}
