package com.amurayada.music.ui.screens.playlist

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import kotlinx.coroutines.launch
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlaylistDetailScreen(
    playlist: Playlist?,
    songs: List<Song>,
    allSongs: List<Song> = emptyList(),
    currentSong: Song? = null,
    isPlaying: Boolean = false,
    isReadOnly: Boolean = false,
    onBackClick: () -> Unit,
    onSongClick: (Song) -> Unit,
    onRemoveSong: (Long) -> Unit,
    onRemoveSongs: ((List<Long>) -> Unit)? = null,
    onPlayNext: ((Song) -> Unit)? = null,
    onAddToQueue: ((Song) -> Unit)? = null,
    onMoveToBoundary: ((Long, Boolean) -> Unit)? = null,
    onAddSong: (Song) -> Unit = {},
    onAddSongs: ((List<Song>) -> Unit)? = null,
    onMoveSong: ((Int, Int) -> Unit)? = null,
    onPlayOrderedList: ((Song, List<Song>) -> Unit)? = null,
    onRenamePlaylist: ((String) -> Unit)? = null,
    onDeletePlaylist: (() -> Unit)? = null,
    onDuplicatePlaylist: (() -> Unit)? = null,
    onChangeArtwork: ((android.net.Uri?) -> Unit)? = null,
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
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val prefs = remember { context.getSharedPreferences("omnivault_prefs", Context.MODE_PRIVATE) }
    var currentSort by remember(playlist.id, isReadOnly) {
        mutableStateOf(
            if (isReadOnly) PlaylistSort.MANUAL
            else PlaylistSort.fromName(prefs.getString("playlist_sort_${playlist.id}", PlaylistSort.RECENTLY_ADDED.name))
        )
    }

    var showAddSongDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember(playlist.name) { mutableStateOf(playlist.name) }
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var contextMenuSong by remember { mutableStateOf<Song?>(null) }
    val selectedIds = remember { mutableStateListOf<Long>() }
    val inSelectionMode = selectedIds.isNotEmpty()

    val artworkPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onChangeArtwork?.invoke(uri)
        }
    }

    BackHandler(enabled = inSelectionMode) {
        selectedIds.clear()
    }
    BackHandler(enabled = isSearchActive && !inSelectionMode) {
        isSearchActive = false
        searchQuery = ""
    }

    val baseOrderedSongs = remember(songs, currentSort, isReadOnly) {
        if (isReadOnly) songs
        else when (currentSort) {
            PlaylistSort.RECENTLY_ADDED -> songs.reversed()
            PlaylistSort.NAME_ASC -> songs.sortedBy { it.title.lowercase() }
            PlaylistSort.NAME_DESC -> songs.sortedByDescending { it.title.lowercase() }
            PlaylistSort.MANUAL -> songs
        }
    }

    val displayedSongs = remember(baseOrderedSongs, searchQuery) {
        if (searchQuery.isBlank()) baseOrderedSongs
        else {
            val q = searchQuery.trim().lowercase()
            baseOrderedSongs.filter {
                it.title.lowercase().contains(q) || it.artist.lowercase().contains(q)
            }
        }
    }

    val totalSeconds = remember(songs) { songs.sumOf { it.duration } / 1000L }
    val totalDurationText = remember(totalSeconds) {
        val hours = totalSeconds / 3600
        val mins = (totalSeconds % 3600) / 60
        if (hours > 0) "${hours}h ${mins}m" else "${mins} min"
    }

    val coverArtUri = remember(songs, playlist.artworkUri) {
        playlist.artworkUri?.let { android.net.Uri.parse(it) }
            ?: songs.firstOrNull { it.albumArtUri != null }?.albumArtUri
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

        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
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
                    color = com.amurayada.music.ui.theme.LocalAuraPalette.current.cardBg.copy(alpha = 0.9f),
                    border = BorderStroke(1.dp, com.amurayada.music.ui.theme.LocalAuraPalette.current.border),
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Atrás",
                            tint = com.amurayada.music.ui.theme.LocalAuraPalette.current.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(50),
                    color = com.amurayada.music.ui.theme.LocalAuraPalette.current.cardBg.copy(alpha = 0.9f),
                    border = BorderStroke(1.dp, com.amurayada.music.ui.theme.LocalAuraPalette.current.border)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        IconButton(
                            onClick = {
                                isSearchActive = !isSearchActive
                                if (!isSearchActive) searchQuery = ""
                            },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                if (isSearchActive) Icons.Rounded.Close else Icons.Rounded.Search,
                                contentDescription = "Buscar en playlist",
                                tint = if (isSearchActive) com.amurayada.music.ui.theme.LocalAuraPalette.current.accent else com.amurayada.music.ui.theme.LocalAuraPalette.current.textPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        if (!isReadOnly) {
                        Box {
                            IconButton(
                                onClick = { showSortMenu = true },
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.Sort,
                                    contentDescription = "Ordenar playlist",
                                    tint = com.amurayada.music.ui.theme.LocalAuraPalette.current.textPrimary,
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
                                                Icon(Icons.Rounded.Check, contentDescription = null, tint = com.amurayada.music.ui.theme.LocalAuraPalette.current.accent)
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
                        }

                        Box {
                            IconButton(
                                onClick = { showMoreMenu = true },
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.MoreVert,
                                    contentDescription = "Más opciones",
                                    tint = com.amurayada.music.ui.theme.LocalAuraPalette.current.textPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false }
                            ) {
                                if (!isReadOnly) {
                                    DropdownMenuItem(
                                        text = { Text("Agregar canciones") },
                                        leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                                        onClick = {
                                            showMoreMenu = false
                                            showAddSongDialog = true
                                        }
                                    )
                                }
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
                                if (!isReadOnly && onChangeArtwork != null) {
                                    DropdownMenuItem(
                                        text = { Text("Cambiar portada") },
                                        leadingIcon = { Icon(Icons.Rounded.Image, contentDescription = null) },
                                        onClick = {
                                            showMoreMenu = false
                                            artworkPickerLauncher.launch(arrayOf("image/*"))
                                        }
                                    )
                                    if (!playlist.artworkUri.isNullOrBlank()) {
                                        DropdownMenuItem(
                                            text = { Text("Restablecer portada") },
                                            leadingIcon = { Icon(Icons.Rounded.HideImage, contentDescription = null) },
                                            onClick = {
                                                showMoreMenu = false
                                                onChangeArtwork(null)
                                            }
                                        )
                                    }
                                }
                                if (!isReadOnly && onDuplicatePlaylist != null) {
                                    DropdownMenuItem(
                                        text = { Text("Duplicar playlist") },
                                        leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                                        onClick = {
                                            showMoreMenu = false
                                            onDuplicatePlaylist()
                                            Toast.makeText(context, "Playlist duplicada", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }
                                if (!isReadOnly && onRenamePlaylist != null) {
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
                                if (!isReadOnly && onDeletePlaylist != null) {
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

            if (isSearchActive) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Buscar en ${playlist.name}...", style = MaterialTheme.typography.bodySmall) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = com.amurayada.music.ui.theme.LocalAuraPalette.current.accent) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Rounded.Clear, contentDescription = "Limpiar")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            if (inSelectionMode) {
                Surface(
                    color = com.amurayada.music.ui.theme.LocalAuraPalette.current.cardBg,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, com.amurayada.music.ui.theme.LocalAuraPalette.current.accent.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { selectedIds.clear() }, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = "Cancelar", tint = com.amurayada.music.ui.theme.LocalAuraPalette.current.textPrimary, modifier = Modifier.size(18.dp))
                        }
                        Text(
                            text = "${selectedIds.size}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = com.amurayada.music.ui.theme.LocalAuraPalette.current.accent
                        )
                        Spacer(Modifier.weight(1f))
                        if (onAddToQueue != null) {
                            IconButton(
                                onClick = {
                                    val batch = displayedSongs.filter { it.id in selectedIds }
                                    batch.forEach { onAddToQueue(it) }
                                    selectedIds.clear()
                                    Toast.makeText(context, "${batch.size} canciones añadidas a la cola", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Rounded.QueueMusic, contentDescription = "Añadir a la cola", tint = com.amurayada.music.ui.theme.LocalAuraPalette.current.textPrimary, modifier = Modifier.size(20.dp))
                            }
                        }
                        if (!isReadOnly) {
                            IconButton(
                                onClick = {
                                    val idsToRemove = selectedIds.toList()
                                    if (onRemoveSongs != null) {
                                        onRemoveSongs(idsToRemove)
                                    } else {
                                        idsToRemove.forEach { onRemoveSong(it) }
                                    }
                                    selectedIds.clear()
                                    Toast.makeText(context, "${idsToRemove.size} canciones quitadas", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Quitar de playlist", tint = Color(0xFFFF5252), modifier = Modifier.size(20.dp))
                            }
                        }
                        IconButton(
                            onClick = {
                                if (selectedIds.size == displayedSongs.size) selectedIds.clear()
                                else {
                                    selectedIds.clear()
                                    selectedIds.addAll(displayedSongs.map { it.id })
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Rounded.SelectAll, contentDescription = "Seleccionar todo", tint = com.amurayada.music.ui.theme.LocalAuraPalette.current.accent, modifier = Modifier.size(20.dp))
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
                            color = com.amurayada.music.ui.theme.LocalAuraPalette.current.cardBg,
                            border = BorderStroke(1.dp, com.amurayada.music.ui.theme.LocalAuraPalette.current.border),
                            modifier = Modifier
                                .size(164.dp)
                                .then(
                                    if (!isReadOnly && onChangeArtwork != null) {
                                        Modifier.clickable { artworkPickerLauncher.launch(arrayOf("image/*")) }
                                    } else Modifier
                                )
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
                                        tint = com.amurayada.music.ui.theme.LocalAuraPalette.current.accent,
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
                                color = com.amurayada.music.ui.theme.LocalAuraPalette.current.textPrimary,
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
                                        tint = com.amurayada.music.ui.theme.LocalAuraPalette.current.textPrimary.copy(alpha = 0.8f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(4.dp))

                        Text(
                            text = if (songs.isNotEmpty()) "${songs.size} Canciones • $totalDurationText" else "0 Canciones",
                            style = MaterialTheme.typography.bodyMedium,
                            color = com.amurayada.music.ui.theme.LocalAuraPalette.current.textPrimary.copy(alpha = 0.65f)
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
                                color = com.amurayada.music.ui.theme.LocalAuraPalette.current.cardBg,
                                border = BorderStroke(1.dp, com.amurayada.music.ui.theme.LocalAuraPalette.current.border),
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
                                        color = com.amurayada.music.ui.theme.LocalAuraPalette.current.textPrimary,
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
                                color = com.amurayada.music.ui.theme.LocalAuraPalette.current.cardBg,
                                border = BorderStroke(1.dp, com.amurayada.music.ui.theme.LocalAuraPalette.current.border),
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
                                        color = com.amurayada.music.ui.theme.LocalAuraPalette.current.textPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }

                        if (!isReadOnly && currentSort == PlaylistSort.MANUAL && searchQuery.isBlank() && !inSelectionMode && displayedSongs.size > 1) {
                            Spacer(Modifier.height(12.dp))
                            Surface(
                                color = com.amurayada.music.ui.theme.LocalAuraPalette.current.accent.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, com.amurayada.music.ui.theme.LocalAuraPalette.current.accent.copy(alpha = 0.35f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Rounded.DragHandle,
                                        contentDescription = null,
                                        tint = com.amurayada.music.ui.theme.LocalAuraPalette.current.accent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "Arrastra desde ☰ para ordenar • Mantén en una fila para opciones",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = com.amurayada.music.ui.theme.LocalAuraPalette.current.accent
                                    )
                                }
                            }
                        } else if (!isReadOnly && searchQuery.isBlank() && !inSelectionMode && displayedSongs.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "Mantén presionada una canción para ver opciones",
                                style = MaterialTheme.typography.labelSmall,
                                color = com.amurayada.music.ui.theme.LocalAuraPalette.current.textPrimary.copy(alpha = 0.45f)
                            )
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
                                text = if (searchQuery.isNotBlank()) "No se encontraron canciones" else "Esta playlist está vacía",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.Gray
                            )
                            if (!isReadOnly && searchQuery.isBlank()) {
                                Spacer(Modifier.height(14.dp))
                                Button(onClick = { showAddSongDialog = true }) {
                                    Icon(Icons.Rounded.Add, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Agregar canciones")
                                }
                            }
                        }
                    }
                } else {
                    itemsIndexed(displayedSongs, key = { index, song -> "${song.id}_$index" }) { index, song ->
                        val currentIndex by rememberUpdatedState(index)
                        val currentLastIndex by rememberUpdatedState(displayedSongs.lastIndex)
                        val isDragging = index == draggingIndex
                        val isCurrent = currentSong?.id == song.id
                        val isSelected = song.id in selectedIds
                        val canDrag = !isReadOnly && currentSort == PlaylistSort.MANUAL && searchQuery.isBlank() && !inSelectionMode && onMoveSong != null
                        val dragHandleModifier = if (canDrag) {
                            Modifier.pointerInput(song.id) {
                                detectDragGestures(
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
                                            if (draggingOffsetY > itemHeightPx * 0.65f && curIdx < currentLastIndex) {
                                                onMoveSong?.invoke(curIdx, curIdx + 1)
                                                draggingIndex = curIdx + 1
                                                draggingOffsetY -= itemHeightPx
                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            } else if (draggingOffsetY < -itemHeightPx * 0.65f && curIdx > 0) {
                                                onMoveSong?.invoke(curIdx, curIdx - 1)
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
                                .background(
                                    when {
                                        isDragging -> com.amurayada.music.ui.theme.LocalAuraPalette.current.cardBgActive
                                        isSelected -> com.amurayada.music.ui.theme.LocalAuraPalette.current.accent.copy(alpha = 0.20f)
                                        isCurrent -> com.amurayada.music.ui.theme.LocalAuraPalette.current.accent.copy(alpha = 0.12f)
                                        else -> Color.Transparent
                                    }
                                )
                                .combinedClickable(
                                    onClick = {
                                        if (inSelectionMode) {
                                            if (isSelected) selectedIds.remove(song.id) else selectedIds.add(song.id)
                                        } else if (onPlayOrderedList != null) {
                                            onPlayOrderedList(song, displayedSongs)
                                        } else {
                                            onSongClick(song)
                                        }
                                    },
                                    onLongClick = {
                                        if (!inSelectionMode) {
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                            contextMenuSong = song
                                        }
                                    }
                                )
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (inSelectionMode) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = {
                                        if (isSelected) selectedIds.remove(song.id) else selectedIds.add(song.id)
                                    }
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(com.amurayada.music.ui.theme.LocalAuraPalette.current.cardBg),
                                contentAlignment = Alignment.Center
                            ) {
                                val songArt = com.amurayada.music.ui.utils.toArtModel(song)
                                if (songArt != null) {
                                    AsyncImage(
                                        model = songArt,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(
                                        Icons.Rounded.MusicNote,
                                        contentDescription = null,
                                        tint = com.amurayada.music.ui.theme.LocalAuraPalette.current.accent,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                if (isCurrent && isPlaying) {
                                    Box(
                                        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Rounded.GraphicEq,
                                            contentDescription = "Reproduciendo",
                                            tint = com.amurayada.music.ui.theme.LocalAuraPalette.current.accent,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = song.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isCurrent) com.amurayada.music.ui.theme.LocalAuraPalette.current.accent else com.amurayada.music.ui.theme.LocalAuraPalette.current.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = song.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = com.amurayada.music.ui.theme.LocalAuraPalette.current.textPrimary.copy(alpha = 0.6f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            if (canDrag) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .then(dragHandleModifier),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Rounded.DragHandle,
                                        contentDescription = "Arrastrar para ordenar",
                                        tint = com.amurayada.music.ui.theme.LocalAuraPalette.current.textPrimary.copy(alpha = 0.65f)
                                    )
                                }
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

        // Menú contextual al mantener presionada una canción dentro de la playlist
        contextMenuSong?.let { targetSong ->
            ModalBottomSheet(
                onDismissRequest = { contextMenuSong = null }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .padding(bottom = 24.dp)
                ) {
                    Text(
                        text = targetSong.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = targetSong.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()

                    ListItem(
                        headlineContent = { Text("Reproducir ahora") },
                        leadingContent = { Icon(Icons.Rounded.PlayArrow, contentDescription = null) },
                        modifier = Modifier.clickable {
                            contextMenuSong = null
                            if (onPlayOrderedList != null) onPlayOrderedList(targetSong, displayedSongs)
                            else onSongClick(targetSong)
                        }
                    )
                    if (onPlayNext != null) {
                        ListItem(
                            headlineContent = { Text("Reproducir a continuación") },
                            leadingContent = { Icon(Icons.Rounded.QueueMusic, contentDescription = null) },
                            modifier = Modifier.clickable {
                                contextMenuSong = null
                                onPlayNext(targetSong)
                                Toast.makeText(context, "Se reproducirá a continuación", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                    if (onAddToQueue != null) {
                        ListItem(
                            headlineContent = { Text("Añadir a la cola") },
                            leadingContent = { Icon(Icons.Rounded.AddToQueue, contentDescription = null) },
                            modifier = Modifier.clickable {
                                contextMenuSong = null
                                onAddToQueue(targetSong)
                                Toast.makeText(context, "Añadido a la cola", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                    if (!isReadOnly && currentSort == PlaylistSort.MANUAL && searchQuery.isBlank() && onMoveToBoundary != null) {
                        ListItem(
                            headlineContent = { Text("Mover al principio") },
                            leadingContent = { Icon(Icons.Rounded.VerticalAlignTop, contentDescription = null) },
                            modifier = Modifier.clickable {
                                contextMenuSong = null
                                onMoveToBoundary(targetSong.id, true)
                            }
                        )
                        ListItem(
                            headlineContent = { Text("Mover al final") },
                            leadingContent = { Icon(Icons.Rounded.VerticalAlignBottom, contentDescription = null) },
                            modifier = Modifier.clickable {
                                contextMenuSong = null
                                onMoveToBoundary(targetSong.id, false)
                            }
                        )
                    }
                    ListItem(
                        headlineContent = { Text("Seleccionar varias") },
                        leadingContent = { Icon(Icons.Rounded.Checklist, contentDescription = null) },
                        modifier = Modifier.clickable {
                            contextMenuSong = null
                            if (targetSong.id !in selectedIds) selectedIds.add(targetSong.id)
                        }
                    )
                    if (!isReadOnly) {
                        ListItem(
                            headlineContent = { Text("Quitar de la playlist", color = Color(0xFFFF5252)) },
                            leadingContent = { Icon(Icons.Rounded.PlaylistRemove, contentDescription = null, tint = Color(0xFFFF5252)) },
                            modifier = Modifier.clickable {
                                contextMenuSong = null
                                onRemoveSong(targetSong.id)
                                scope.launch {
                                    val res = snackbarHostState.showSnackbar(
                                        message = "Quitada: ${targetSong.title}",
                                        actionLabel = "Deshacer",
                                        duration = SnackbarDuration.Short
                                    )
                                    if (res == SnackbarResult.ActionPerformed) {
                                        onAddSong(targetSong)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp)
        )
    }
}
