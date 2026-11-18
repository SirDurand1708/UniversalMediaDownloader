package com.amurayada.music.ui.screens.music

import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.amurayada.music.data.model.Album
import com.amurayada.music.data.model.Artist
import com.amurayada.music.data.model.PlaybackState
import com.amurayada.music.data.model.Playlist
import com.amurayada.music.data.model.Song
import com.amurayada.music.data.model.SongSort
import com.amurayada.music.ui.components.AddToPlaylistSheet
import com.amurayada.music.ui.components.EqualizerIndicator
import com.amurayada.music.ui.viewmodel.LibraryViewModel
import com.amurayada.music.ui.viewmodel.PlaybackViewModel
import com.amurayada.music.ui.viewmodel.PlaylistViewModel
import com.omnivault.media.utils.FilenameStandardizer
import java.io.File

// Cache thread-safe de detección de letras en disco para evitar jank en LazyColumn de 2000+ canciones
private val lyricsCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

enum class LarkCategory(val label: String) {
    CANCIONES("Canciones"),
    PLAYLISTS("Playlists"),
    CARPETAS("Carpetas"),
    ALBUMES("Álbumes"),
    ARTISTAS("Artistas")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LarkStyleMusicScreen(
    libraryViewModel: LibraryViewModel,
    playbackViewModel: PlaybackViewModel,
    playlistViewModel: PlaylistViewModel,
    onEqualizerClick: () -> Unit,
    onPlaylistClick: (Playlist) -> Unit = {},
    onAlbumClick: (com.amurayada.music.data.model.Album) -> Unit = {},
    onArtistClick: (com.amurayada.music.data.model.Artist) -> Unit = {},
    onFolderClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentSong = playbackViewModel.currentSong
    val isPlaying = playbackViewModel.isPlaying

    var selectedCategory by rememberSaveable { mutableStateOf(LarkCategory.CANCIONES) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    val currentSort = libraryViewModel.songSortOrder
    var showSortDialog by remember { mutableStateOf(false) }
    var songForPlaylist by remember { mutableStateOf<Song?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var playlistToRename by remember { mutableStateOf<Playlist?>(null) }
    var renamePlaylistText by remember { mutableStateOf("") }
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }
    var songToDelete by remember { mutableStateOf<Song?>(null) }

    val rawSongs = remember(
        libraryViewModel.songs,
        libraryViewModel.hiddenSongIds,
        libraryViewModel.deletedSongPaths
    ) {
        libraryViewModel.songs.filter {
            val lower = it.path.lowercase()
            val isVideo = it.album.equals("Video", ignoreCase = true) ||
                it.path.contains("/video/", ignoreCase = true) ||
                lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
            !isVideo &&
                !libraryViewModel.hiddenSongIds.contains(it.id) &&
                (it.path.isBlank() || !libraryViewModel.deletedSongPaths.contains(it.path))
        }
    }
    val playlists by playlistViewModel.playlists.collectAsState()
    var songsForPlaylist by remember { mutableStateOf<List<Song>>(emptyList()) }

    BackHandler(enabled = selectedCategory != LarkCategory.CANCIONES) {
        selectedCategory = LarkCategory.CANCIONES
    }
    BackHandler(enabled = isSearchActive) {
        isSearchActive = false
        searchQuery = ""
    }

    // Filtrar y ordenar canciones locales
    val displayedSongs = remember(rawSongs, searchQuery, currentSort) {
        val filtered = if (searchQuery.isBlank()) {
            rawSongs
        } else {
            val q = searchQuery.lowercase().trim()
            rawSongs.filter {
                it.title.lowercase().contains(q) ||
                it.artist.lowercase().contains(q) ||
                it.album.lowercase().contains(q)
            }
        }
        when (currentSort) {
            SongSort.RECENT -> filtered.sortedByDescending { it.dateAdded }
            SongSort.NAME_ASC -> filtered.sortedBy { it.title.lowercase() }
            SongSort.NAME_DESC -> filtered.sortedByDescending { it.title.lowercase() }
            SongSort.ARTIST_ASC -> filtered.sortedBy { it.artist.lowercase() }
            SongSort.DURATION_DESC -> filtered.sortedByDescending { it.duration }
        }
    }

    // Agrupación optimizada por carpetas mediante manipulación directa de cadenas (sin instanciar miles de Files)
    val folderGroups = remember(rawSongs) {
        rawSongs.groupBy { song ->
            val p = song.path
            if (p.startsWith("/")) {
                val lastSlash = p.lastIndexOf('/')
                if (lastSlash > 0) {
                    val prevSlash = p.lastIndexOf('/', lastSlash - 1)
                    if (prevSlash >= 0) p.substring(prevSlash + 1, lastSlash) else "Almacenamiento Interno"
                } else "Almacenamiento Interno"
            } else "Música"
        }
    }

    val neonCyan = Color(0xFF00E5FF)
    val neonGreen = Color(0xFF00E676)
    val titaniumCard = Color(0xFF141923)

    val snackbarHostState = remember { SnackbarHostState() }
    val rootScope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("omnivault_prefs", android.content.Context.MODE_PRIVATE) }
    var searchHistory by remember {
        mutableStateOf(
            prefs.getString("music_search_history", "")
                ?.split("|")
                ?.filter { it.isNotBlank() }
                ?: emptyList()
        )
    }
    val saveSearchQuery: (String) -> Unit = { q ->
        val clean = q.trim()
        if (clean.length >= 2) {
            val updated = (listOf(clean) + searchHistory.filterNot { it.equals(clean, ignoreCase = true) }).take(5)
            searchHistory = updated
            prefs.edit().putString("music_search_history", updated.joinToString("|")).apply()
        }
    }
    var songsToDeleteBatch by remember { mutableStateOf<List<Song>>(emptyList()) }

    Box(modifier = modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // 1. Barra Superior Estilo Lark Player con Nombre Aura Player
        if (!isSearchActive) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(neonGreen, neonCyan, Color(0xFF7C4DFF))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Aura Player",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        letterSpacing = (-0.5).sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { isSearchActive = true }) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = "Buscar",
                            tint = Color.White
                        )
                    }
                    IconButton(onClick = { showSortDialog = true }) {
                        Icon(
                            imageVector = Icons.Rounded.Sort,
                            contentDescription = "Ordenar",
                            tint = Color.White
                        )
                    }
                    IconButton(onClick = onEqualizerClick) {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = "Ecualizador & 300%",
                            tint = neonCyan
                        )
                    }
                }
            }
        } else {
            // Buscador activo en vivo
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Buscar en tu música local...", color = Color.Gray) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = titaniumCard,
                        unfocusedContainerColor = titaniumCard,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = neonCyan,
                        focusedIndicatorColor = neonCyan,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(12.dp),
                    leadingIcon = {
                        Icon(Icons.Rounded.Search, contentDescription = null, tint = neonCyan)
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Limpiar", tint = Color.Gray)
                            }
                        }
                    }
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    isSearchActive = false
                    searchQuery = ""
                }) {
                    Text("Cancelar", color = Color.White)
                }
            }
            if (searchQuery.isBlank() && searchHistory.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(searchHistory) { recent ->
                        AssistChip(
                            onClick = { searchQuery = recent },
                            label = { Text(recent, fontSize = 12.sp, maxLines = 1) },
                            leadingIcon = {
                                Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(14.dp))
                            }
                        )
                    }
                }
            }
        }

        // 2. Chips de Categorías (Canciones, Playlists, Carpetas, Álbumes, Artistas)
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(LarkCategory.entries) { category ->
                val selected = selectedCategory == category
                Surface(
                    onClick = { selectedCategory = category },
                    shape = RoundedCornerShape(20.dp),
                    color = if (selected) neonCyan else titaniumCard,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (selected) neonCyan else Color(0xFF263248)
                    )
                ) {
                    Text(
                        text = category.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) Color.Black else Color.White.copy(alpha = 0.85f),
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // 3. Contenido según categoría seleccionada
        when (selectedCategory) {
            LarkCategory.CANCIONES -> {
                SongsListContent(
                    songs = displayedSongs,
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                    onSongClick = { song ->
                        if (isSearchActive && searchQuery.isNotBlank()) saveSearchQuery(searchQuery)
                        playbackViewModel.playSong(song, displayedSongs)
                    },
                    onShuffle = {
                        if (displayedSongs.isNotEmpty()) {
                            val shuffled = displayedSongs.shuffled()
                            playbackViewModel.playSong(shuffled.first(), shuffled)
                        }
                    },
                    onPlayNext = { song ->
                        playbackViewModel.playNext(song)
                        Toast.makeText(context, "Se reproducirá a continuación", Toast.LENGTH_SHORT).show()
                    },
                    onAddToQueue = { song ->
                        playbackViewModel.addToQueue(song)
                        Toast.makeText(context, "Añadido a la cola", Toast.LENGTH_SHORT).show()
                    },
                    onAddToPlaylist = { song ->
                        songForPlaylist = song
                    },
                    onAddMultipleToQueue = { songsBatch ->
                        songsBatch.forEach { playbackViewModel.addToQueue(it) }
                        Toast.makeText(context, "${songsBatch.size} canciones añadidas a la cola", Toast.LENGTH_SHORT).show()
                    },
                    onAddMultipleToPlaylist = { songsBatch ->
                        songsForPlaylist = songsBatch
                    },
                    onHideMultiple = { songsBatch ->
                        songsBatch.forEach { libraryViewModel.hideSong(it.id) }
                        rootScope.launch {
                            val res = snackbarHostState.showSnackbar(
                                message = "${songsBatch.size} canciones ocultadas",
                                actionLabel = "Deshacer",
                                duration = SnackbarDuration.Short
                            )
                            if (res == SnackbarResult.ActionPerformed) {
                                songsBatch.forEach { libraryViewModel.unhideSong(it.id) }
                            }
                        }
                    },
                    onDeleteMultiple = { songsBatch ->
                        songsToDeleteBatch = songsBatch
                    },
                    onStandardize = { song ->
                        val f = File(song.path)
                        if (f.exists()) {
                            val clean = FilenameStandardizer.standardizeTitle(f.nameWithoutExtension)
                            if (clean != f.nameWithoutExtension) {
                                val target = File(f.parentFile, "$clean.${f.extension}")
                                if (f.renameTo(target)) {
                                    MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath, target.absolutePath), null, null)
                                    libraryViewModel.loadLibrary()
                                    Toast.makeText(context, "Nombre estandarizado: $clean", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, "El nombre ya está estandarizado", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onShare = { song ->
                        val file = File(song.path)
                        if (file.exists()) {
                            try {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "audio/*"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Compartir canción"))
                            } catch (e: Exception) {
                                Toast.makeText(context, "Error al compartir: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onHide = { song ->
                        libraryViewModel.hideSong(song.id)
                        rootScope.launch {
                            val res = snackbarHostState.showSnackbar(
                                message = "Canción ocultada",
                                actionLabel = "Deshacer",
                                duration = SnackbarDuration.Short
                            )
                            if (res == SnackbarResult.ActionPerformed) {
                                libraryViewModel.unhideSong(song.id)
                            }
                        }
                    },
                    onDelete = { song ->
                        val isPrivateFile = song.path.contains(context.packageName)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && !isPrivateFile) {
                            val title = song.title
                            libraryViewModel.deleteSong(song, context) {
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
                                Toast.makeText(context, "Canción eliminada: $title", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            songToDelete = song
                        }
                    }
                )
            }

            LarkCategory.PLAYLISTS -> {
                PlaylistsContent(
                    playlists = playlists,
                    allSongs = rawSongs,
                    onCreatePlaylist = { showCreatePlaylistDialog = true },
                    onPlaylistClick = onPlaylistClick,
                    onPlayPlaylist = { playlist ->
                        val pSongs = rawSongs.filter { playlist.songIds.contains(it.id) }
                        if (pSongs.isNotEmpty()) {
                            playbackViewModel.playSong(pSongs.first(), pSongs)
                            Toast.makeText(context, "Reproduciendo ${playlist.name}", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "La playlist está vacía", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onRenamePlaylist = { playlist ->
                        playlistToRename = playlist
                        renamePlaylistText = playlist.name
                    },
                    onDeletePlaylist = { playlist ->
                        playlistToDelete = playlist
                    }
                )
            }

            LarkCategory.CARPETAS -> {
                FoldersContent(
                    folderGroups = folderGroups,
                    searchQuery = searchQuery,
                    onFolderClick = { folderName, songs ->
                        val folderPath = songs.firstOrNull()?.path?.let { p ->
                            val idx = p.lastIndexOf('/')
                            if (idx > 0) p.substring(0, idx) else folderName
                        } ?: folderName
                        onFolderClick(folderPath)
                    }
                )
            }

            LarkCategory.ALBUMES -> {
                AlbumsContent(
                    albums = libraryViewModel.albums,
                    onAlbumClick = onAlbumClick
                )
            }

            LarkCategory.ARTISTAS -> {
                ArtistsContent(
                    artists = libraryViewModel.artists,
                    onArtistClick = onArtistClick
                )
            }
        }
    }
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 16.dp)
    )
    }

    if (songsToDeleteBatch.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { songsToDeleteBatch = emptyList() },
            title = { Text("¿Eliminar ${songsToDeleteBatch.size} canciones?", fontWeight = FontWeight.Bold) },
            text = { Text("Se eliminarán permanentemente los archivos seleccionados de tu dispositivo.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3333)),
                    onClick = {
                        val batch = songsToDeleteBatch
                        songsToDeleteBatch = emptyList()
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
                TextButton(onClick = { songsToDeleteBatch = emptyList() }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Diálogo de ordenamiento
    if (showSortDialog) {
        AlertDialog(
            onDismissRequest = { showSortDialog = false },
            title = { Text("Ordenar música por", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    SongSort.entries.forEach { sort ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    libraryViewModel.setSongSort(sort)
                                    showSortDialog = false
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentSort == sort,
                                onClick = {
                                    libraryViewModel.setSongSort(sort)
                                    showSortDialog = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(sort.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSortDialog = false }) {
                    Text("Cerrar")
                }
            }
        )
    }

    // Diálogo para añadir canción a playlist
    songForPlaylist?.let { song ->
        AddToPlaylistSheet(
            playlists = playlists,
            onDismiss = { songForPlaylist = null },
            onPlaylistSelected = { playlist ->
                playlistViewModel.addSongToPlaylist(playlist.id, song.id)
                songForPlaylist = null
                Toast.makeText(context, "Añadido a ${playlist.name}", Toast.LENGTH_SHORT).show()
            },
            onCreateNewPlaylist = {
                songForPlaylist = null
                showCreatePlaylistDialog = true
            }
        )
    }

    // Diálogo para añadir múltiples canciones a playlist
    if (songsForPlaylist.isNotEmpty()) {
        AddToPlaylistSheet(
            playlists = playlists,
            onDismiss = { songsForPlaylist = emptyList() },
            onPlaylistSelected = { playlist ->
                val count = songsForPlaylist.size
                songsForPlaylist.forEach { playlistViewModel.addSongToPlaylist(playlist.id, it.id) }
                songsForPlaylist = emptyList()
                Toast.makeText(context, "$count canciones añadidas a ${playlist.name}", Toast.LENGTH_SHORT).show()
            },
            onCreateNewPlaylist = {
                songsForPlaylist = emptyList()
                showCreatePlaylistDialog = true
            }
        )
    }

    // Diálogo para crear nueva playlist
    if (showCreatePlaylistDialog) {
        AlertDialog(
            onDismissRequest = {
                showCreatePlaylistDialog = false
                newPlaylistName = ""
            },
            title = { Text("Nueva Playlist") },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text("Nombre de la lista") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newPlaylistName.isNotBlank()) {
                            playlistViewModel.createPlaylist(newPlaylistName.trim())
                            libraryViewModel.loadLibrary()
                            showCreatePlaylistDialog = false
                            Toast.makeText(context, "Playlist \"$newPlaylistName\" creada", Toast.LENGTH_SHORT).show()
                            newPlaylistName = ""
                        }
                    }
                ) {
                    Text("Crear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePlaylistDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Diálogo para renombrar playlist
    playlistToRename?.let { pl ->
        AlertDialog(
            onDismissRequest = { playlistToRename = null },
            title = { Text("Renombrar Playlist", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renamePlaylistText,
                    onValueChange = { renamePlaylistText = it },
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
                            playlistToRename = null
                            Toast.makeText(context, "Playlist renombrada", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Guardar")
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToRename = null }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Diálogo para eliminar playlist
    playlistToDelete?.let { pl ->
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
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
                        playlistToDelete = null
                        Toast.makeText(context, "Playlist \"$name\" eliminada", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Eliminar", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToDelete = null }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Diálogo para eliminar canción del almacenamiento local
    songToDelete?.let { song ->
        AlertDialog(
            onDismissRequest = { songToDelete = null },
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
                        songToDelete = null
                    }
                ) {
                    Text("Eliminar", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { songToDelete = null }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

/**
 * Vista de lista de canciones idéntica a Lark Player:
 * Fila compacta con carátula, título en negrita, insignia [LETRAS] y 3 puntos de opciones.
 */
@Composable
private fun SongsListContent(
    songs: List<Song>,
    currentSong: Song?,
    isPlaying: Boolean,
    onSongClick: (Song) -> Unit,
    onShuffle: () -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit = {},
    onAddToPlaylist: (Song) -> Unit,
    onAddMultipleToQueue: (List<Song>) -> Unit = {},
    onAddMultipleToPlaylist: (List<Song>) -> Unit = {},
    onHideMultiple: (List<Song>) -> Unit = {},
    onDeleteMultiple: (List<Song>) -> Unit = {},
    onStandardize: (Song) -> Unit,
    onShare: (Song) -> Unit,
    onHide: (Song) -> Unit,
    onDelete: (Song) -> Unit
) {
    if (songs.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = Color.Gray.copy(alpha = 0.5f)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "No se encontraron canciones locales",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.Gray
                )
            }
        }
        return
    }

    val selectedIds = remember { mutableStateListOf<Long>() }
    val inSelectionMode = selectedIds.isNotEmpty()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val showScrollToTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 12 } }

    BackHandler(enabled = inSelectionMode) {
        selectedIds.clear()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            if (inSelectionMode) {
                item {
                    Surface(
                        color = Color(0xFF141923),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { selectedIds.clear() },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = "Cancelar selección", tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                            Text(
                                text = "${selectedIds.size}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00E5FF)
                            )
                            Spacer(Modifier.weight(1f))
                            IconButton(
                                onClick = {
                                    val batch = songs.filter { selectedIds.contains(it.id) }
                                    onAddMultipleToPlaylist(batch)
                                    selectedIds.clear()
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Rounded.PlaylistAdd, contentDescription = "Añadir a playlist", tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                            IconButton(
                                onClick = {
                                    val batch = songs.filter { selectedIds.contains(it.id) }
                                    onAddMultipleToQueue(batch)
                                    selectedIds.clear()
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Rounded.QueueMusic, contentDescription = "Añadir a la cola", tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                            IconButton(
                                onClick = {
                                    val batch = songs.filter { selectedIds.contains(it.id) }
                                    onHideMultiple(batch)
                                    selectedIds.clear()
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Rounded.VisibilityOff, contentDescription = "Ocultar", tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                            IconButton(
                                onClick = {
                                    val batch = songs.filter { selectedIds.contains(it.id) }
                                    onDeleteMultiple(batch)
                                    selectedIds.clear()
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Eliminar", tint = Color(0xFFFF5252), modifier = Modifier.size(20.dp))
                            }
                            IconButton(
                                onClick = {
                                    if (selectedIds.size == songs.size) {
                                        selectedIds.clear()
                                    } else {
                                        selectedIds.clear()
                                        selectedIds.addAll(songs.map { it.id })
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Rounded.SelectAll, contentDescription = "Seleccionar todo", tint = Color(0xFF00E5FF), modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            } else {
                // Cabecera rápida: Total de pistas y botón Aleatorio
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${songs.size} canciones",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White.copy(alpha = 0.7f)
                        )

                        Surface(
                            onClick = onShuffle,
                            color = Color(0xFF141923),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF263248))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Shuffle,
                                    contentDescription = null,
                                    tint = Color(0xFF00E5FF),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "Aleatorio",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }

            items(songs, key = { it.id }) { song ->
                val isCurrent = currentSong?.id == song.id
                val isSelected = selectedIds.contains(song.id)
                LarkSongRow(
                    song = song,
                    isCurrent = isCurrent,
                    isPlaying = isCurrent && isPlaying,
                    inSelectionMode = inSelectionMode,
                    isSelected = isSelected,
                    onClick = {
                        if (inSelectionMode) {
                            if (isSelected) selectedIds.remove(song.id) else selectedIds.add(song.id)
                        } else {
                            onSongClick(song)
                        }
                    },
                    onLongClick = {
                        if (isSelected) selectedIds.remove(song.id) else selectedIds.add(song.id)
                    },
                    onPlayNext = { onPlayNext(song) },
                    onAddToQueue = { onAddToQueue(song) },
                    onAddToPlaylist = { onAddToPlaylist(song) },
                    onStandardize = { onStandardize(song) },
                    onShare = { onShare(song) },
                    onHide = { onHide(song) },
                    onDelete = { onDelete(song) }
                )
            }
        }

        // Índice alfabético A-Z arrastrable cuando hay más de 30 canciones
        if (songs.size > 30) {
            val alphabet = remember { ('A'..'Z').map { it.toString() } }
            var activeLetter by remember { mutableStateOf<String?>(null) }
            var barHeightPx by remember { mutableIntStateOf(1) }

            val jumpToLetter: (Float) -> Unit = { y ->
                val idx = ((y / barHeightPx.toFloat()) * alphabet.size).toInt().coerceIn(0, alphabet.lastIndex)
                val letter = alphabet[idx]
                activeLetter = letter
                val songIdx = songs.indexOfFirst { it.title.trimStart().startsWith(letter, ignoreCase = true) }
                if (songIdx >= 0) {
                    scope.launch { listState.scrollToItem(songIdx + 1) }
                }
            }

            if (activeLetter != null) {
                Surface(
                    color = Color(0xFF00E5FF),
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = activeLetter ?: "",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.Black
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 2.dp, top = 48.dp, bottom = 56.dp)
                    .onSizeChanged { barHeightPx = it.height.coerceAtLeast(1) }
                    .pointerInput(songs) {
                        detectVerticalDragGestures(
                            onDragStart = { offset -> jumpToLetter(offset.y) },
                            onVerticalDrag = { change, _ -> jumpToLetter(change.position.y) },
                            onDragEnd = { activeLetter = null },
                            onDragCancel = { activeLetter = null }
                        )
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                alphabet.forEach { letter ->
                    Text(
                        text = letter,
                        fontSize = 9.sp,
                        fontWeight = if (activeLetter == letter) FontWeight.ExtraBold else FontWeight.Medium,
                        color = if (activeLetter == letter) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.5f),
                        modifier = Modifier
                            .clickable {
                                val songIdx = songs.indexOfFirst { it.title.trimStart().startsWith(letter, ignoreCase = true) }
                                if (songIdx >= 0) scope.launch { listState.scrollToItem(songIdx + 1) }
                            }
                            .padding(horizontal = 4.dp)
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showScrollToTop,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            SmallFloatingActionButton(
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                containerColor = Color(0xFF141923),
                contentColor = Color(0xFF00E5FF)
            ) {
                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Ir arriba")
            }
        }
    }
}

/**
 * Fila individual con el diseño visual exacto de Lark Player.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LarkSongRow(
    song: Song,
    isCurrent: Boolean,
    isPlaying: Boolean,
    inSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit = {},
    onAddToPlaylist: () -> Unit,
    onStandardize: () -> Unit,
    onShare: () -> Unit,
    onHide: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }

    // Detección en memoria con caché O(1) de archivo de letra (.lrc) para scrolling fluido a 120 FPS
    val hasLyrics = remember(song.path) {
        if (!song.path.startsWith("/")) return@remember false
        lyricsCache.getOrPut(song.path) {
            try {
                val lrcFile = File(song.path.substringBeforeLast(".") + ".lrc")
                lrcFile.exists() && lrcFile.length() > 0L
            } catch (_: Exception) {
                false
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(
                when {
                    isSelected -> Color(0xFF00E5FF).copy(alpha = 0.20f)
                    isCurrent -> Color(0xFF00E5FF).copy(alpha = 0.12f)
                    else -> Color.Transparent
                }
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (inSelectionMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onClick() },
                colors = CheckboxDefaults.colors(checkedColor = Color(0xFF00E5FF))
            )
            Spacer(Modifier.width(4.dp))
        }
        // Carátula cuadrada redondeada de 48dp
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF141923)),
            contentAlignment = Alignment.Center
        ) {
            if (song.albumArtUri != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(song.albumArtUri)
                        .crossfade(true)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(24.dp)
                )
            }

            if (isCurrent && isPlaying) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    EqualizerIndicator(
                        isAnimating = true,
                        color = Color(0xFF00E5FF),
                        maxHeight = 22.dp,
                        barCount = 3,
                        barWidth = 3.dp
                    )
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        // Info de la Canción (Título y Subtítulo con insignia LETRAS)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (isCurrent) Color(0xFF00E5FF) else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(3.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Insignia LETRAS exactamente como en Lark Player
                if (hasLyrics) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .border(0.8.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "LETRAS",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 8.sp,
                            letterSpacing = 0.5.sp
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }

                val cleanArtist = song.artist.replace(" - Topic", "", ignoreCase = true).trim()
                val cleanAlbum = song.album.replace(" - Topic", "", ignoreCase = true).trim()
                val subtitleText = if (cleanAlbum.isBlank() || cleanAlbum.equals(cleanArtist, ignoreCase = true)) {
                    cleanArtist
                } else {
                    "$cleanArtist • $cleanAlbum"
                }

                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Menú de 3 puntos
        Box {
            IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "Opciones",
                    tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp)
                )
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Reproducir") },
                    leadingIcon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onClick()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Reproducir a continuación") },
                    leadingIcon = { Icon(Icons.Rounded.QueueMusic, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onPlayNext()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Añadir a la cola") },
                    leadingIcon = { Icon(Icons.Rounded.AddToQueue, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onAddToQueue()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Añadir a playlist") },
                    leadingIcon = { Icon(Icons.Rounded.PlaylistAdd, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onAddToPlaylist()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Estandarizar nombre") },
                    leadingIcon = { Icon(Icons.Rounded.AutoFixHigh, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onStandardize()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Compartir") },
                    leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onShare()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Ocultar") },
                    leadingIcon = { Icon(Icons.Rounded.VisibilityOff, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onHide()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Eliminar", color = Color(0xFFFF5252)) },
                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = Color(0xFFFF5252)) },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    }
                )
            }
        }
    }
}

/**
 * Pestaña de Playlists locales.
 */
@Composable
private fun PlaylistsContent(
    playlists: List<Playlist>,
    allSongs: List<Song>,
    onCreatePlaylist: () -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    onPlayPlaylist: (Playlist) -> Unit,
    onRenamePlaylist: (Playlist) -> Unit,
    onDeletePlaylist: (Playlist) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Surface(
                onClick = onCreatePlaylist,
                color = Color(0xFF141923),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF00E5FF).copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, tint = Color(0xFF00E5FF))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Crear nueva playlist", fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Organiza tus canciones locales", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
            }
        }

        if (playlists.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.QueueMusic,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = Color.Gray.copy(alpha = 0.4f)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "No tienes playlists todavía",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray
                        )
                    }
                }
            }
        }

        items(playlists, key = { it.id }) { playlist ->
            var itemMenuExpanded by remember { mutableStateOf(false) }
            val count = if (playlist.songIds.isNotEmpty()) playlist.songIds.size else playlist.songCount
            val firstArt = remember(playlist.songIds, allSongs) {
                allSongs.firstOrNull { playlist.songIds.contains(it.id) && it.albumArtUri != null }?.albumArtUri
            }

            Surface(
                onClick = { onPlaylistClick(playlist) },
                color = Color(0xFF141923).copy(alpha = 0.7f),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF263248)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (firstArt != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(firstArt)
                                .crossfade(true)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1E2838)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.QueueMusic, contentDescription = null, tint = Color(0xFF00E5FF))
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(playlist.name, fontWeight = FontWeight.SemiBold, color = Color.White)
                        Text("$count canciones", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }

                    Box {
                        IconButton(onClick = { itemMenuExpanded = true }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Rounded.MoreVert,
                                contentDescription = "Opciones",
                                tint = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = itemMenuExpanded,
                            onDismissRequest = { itemMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Reproducir") },
                                leadingIcon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null) },
                                onClick = {
                                    itemMenuExpanded = false
                                    onPlayPlaylist(playlist)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Renombrar") },
                                leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                onClick = {
                                    itemMenuExpanded = false
                                    onRenamePlaylist(playlist)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Eliminar playlist", color = Color(0xFFFF5252)) },
                                leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = Color(0xFFFF5252)) },
                                onClick = {
                                    itemMenuExpanded = false
                                    onDeletePlaylist(playlist)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
