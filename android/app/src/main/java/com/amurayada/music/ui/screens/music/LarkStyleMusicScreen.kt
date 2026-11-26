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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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

internal val MusicAccent @Composable get() = com.amurayada.music.ui.theme.LocalAuraPalette.current.accent
internal val MusicOnAccent @Composable get() = com.amurayada.music.ui.theme.LocalAuraPalette.current.onAccent
internal val MusicCardBg @Composable get() = com.amurayada.music.ui.theme.LocalAuraPalette.current.cardBg
internal val MusicCardActive @Composable get() = com.amurayada.music.ui.theme.LocalAuraPalette.current.cardBgActive
internal val MusicBorder @Composable get() = com.amurayada.music.ui.theme.LocalAuraPalette.current.border
internal val MusicTextPrimary @Composable get() = com.amurayada.music.ui.theme.LocalAuraPalette.current.textPrimary

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
    var songToEditTags by remember { mutableStateOf<Song?>(null) }
    var songForDetails by remember { mutableStateOf<Song?>(null) }
    var showHiddenSongsDialog by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(libraryViewModel.isLoading) {
        if (!libraryViewModel.isLoading) {
            isRefreshing = false
        }
    }

    val rawSongs = remember(
        libraryViewModel.songs,
        libraryViewModel.hiddenSongIds,
        libraryViewModel.deletedSongPaths,
        libraryViewModel.filterShortAudio
    ) {
        val normDeletedPaths = libraryViewModel.deletedSongPaths.mapTo(HashSet()) { it.lowercase() }
        libraryViewModel.songs.filter {
            val lower = it.path.lowercase()
            val isVideo = it.mimeType?.startsWith("video/", ignoreCase = true) == true ||
                it.album.equals("Video", ignoreCase = true) ||
                it.path.contains("/video/", ignoreCase = true) ||
                lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
            val passesDuration = !libraryViewModel.filterShortAudio || it.duration <= 0L || it.duration >= 15_000L
            !isVideo &&
                passesDuration &&
                !libraryViewModel.hiddenSongIds.contains(it.id) &&
                (it.path.isBlank() || !normDeletedPaths.contains(lower))
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

    // Agrupación optimizada por carpetas locales sin depender de reproductores externos
    val folderGroups = remember(rawSongs) {
        rawSongs.groupBy { song ->
            val p = song.path
            when {
                p.startsWith("/") -> {
                    val lastSlash = p.lastIndexOf('/')
                    if (lastSlash > 0) {
                        val prevSlash = p.lastIndexOf('/', lastSlash - 1)
                        if (prevSlash >= 0) p.substring(prevSlash + 1, lastSlash) else "Almacenamiento Interno"
                    } else "Almacenamiento Interno"
                }
                p.startsWith("content://") -> "OmniVault"
                else -> "Almacenamiento Interno"
            }
        }.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, List<Song>>> { it.key.equals("OmniVault", ignoreCase = true) }
                    .thenByDescending { it.value.size }
                    .thenBy { it.key.lowercase() }
            )
            .associate { it.key to it.value }
    }

    val neonCyan = MusicAccent
    val neonGreen = com.amurayada.music.ui.theme.LocalAuraPalette.current.accentGreen
    val titaniumCard = MusicCardBg

    val snackbarHostState = remember { SnackbarHostState() }
    val rootScope = rememberCoroutineScope()
    val prefsRepo = remember {
        (context.applicationContext as? com.amurayada.music.MusicApplication)?.appContainer?.preferencesRepository
            ?: com.amurayada.music.data.repository.SharedPreferencesRepositoryImpl(context)
    }
    var searchHistory by remember { mutableStateOf(prefsRepo.getMusicSearchHistory()) }
    val saveSearchQuery: (String) -> Unit = { q ->
        searchHistory = prefsRepo.saveMusicSearchQuery(q)
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
                        color = MusicTextPrimary,
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
                            tint = MusicTextPrimary
                        )
                    }
                    IconButton(onClick = { showSortDialog = true }) {
                        Icon(
                            imageVector = Icons.Rounded.Sort,
                            contentDescription = "Ordenar",
                            tint = MusicTextPrimary
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
                        focusedTextColor = MusicTextPrimary,
                        unfocusedTextColor = MusicTextPrimary,
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
                    Text("Cancelar", color = MusicTextPrimary)
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
                        if (selected) neonCyan else MusicBorder
                    )
                ) {
                    Text(
                        text = category.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) MusicOnAccent else MusicTextPrimary.copy(alpha = 0.85f),
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
                    currentSort = currentSort,
                    lrcKeys = libraryViewModel.lrcKeys,
                    hiddenSongsCount = libraryViewModel.hiddenSongs.size,
                    isRefreshing = isRefreshing,
                    onRefresh = {
                        isRefreshing = true
                        libraryViewModel.loadLibrary()
                    },
                    onShowHiddenSongs = { showHiddenSongsDialog = true },
                    onSongClick = { song ->
                        if (isSearchActive && searchQuery.isNotBlank()) saveSearchQuery(searchQuery)
                        playbackViewModel.playSong(song, displayedSongs)
                    },
                    onPlayAll = {
                        if (displayedSongs.isNotEmpty()) {
                            playbackViewModel.playSong(displayedSongs.first(), displayedSongs)
                        }
                    },
                    onShuffle = {
                        if (displayedSongs.isNotEmpty()) {
                            val shuffled = displayedSongs.shuffled()
                            playbackViewModel.playSong(shuffled.first(), shuffled)
                        }
                    },
                    onEditTags = { song -> songToEditTags = song },
                    onShowDetails = { song -> songForDetails = song },
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
                        if (song.path.startsWith("content://")) {
                            Toast.makeText(context, "Usa 'Estandarizar nombres' en Ajustes para archivos MediaStore", Toast.LENGTH_SHORT).show()
                        } else {
                            val f = File(song.path)
                            if (f.exists()) {
                                val clean = FilenameStandardizer.standardizeTitle(f.nameWithoutExtension)
                                if (clean != f.nameWithoutExtension) {
                                    val target = File(f.parentFile, "$clean.${f.extension}")
                                    if (f.renameTo(target)) {
                                        MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath, target.absolutePath), null, null)
                                        libraryViewModel.loadLibrary()
                                        Toast.makeText(context, "Nombre estandarizado: $clean", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "No se pudo renombrar el archivo", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(context, "El nombre ya está estandarizado", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, "Archivo no encontrado", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onShare = { song ->
                        try {
                            val uri = if (song.path.startsWith("content://")) {
                                android.net.Uri.parse(song.path)
                            } else {
                                val file = File(song.path)
                                if (!file.exists()) {
                                    Toast.makeText(context, "Archivo no encontrado", Toast.LENGTH_SHORT).show()
                                    return@SongsListContent
                                }
                                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            }
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "audio/*"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Compartir canción"))
                        } catch (e: Exception) {
                            Toast.makeText(context, "Error al compartir: ${e.message}", Toast.LENGTH_SHORT).show()
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
                val smartPlaylists = remember(rawSongs, playbackViewModel.mostPlayed, playbackViewModel.favorites) {
                    val rawMap = rawSongs.associateBy { it.id }
                    val recentIds = rawSongs.sortedByDescending { it.dateAdded }.take(50).map { it.id }
                    val mostPlayedIds = playbackViewModel.mostPlayed.mapNotNull { rawMap[it.id]?.id }.take(50)
                    val favIds = playbackViewModel.favorites.mapNotNull { rawMap[it.id]?.id }
                    listOf(
                        Playlist(id = -1L, name = "Añadidas recientemente", songIds = recentIds, dateCreated = 0L),
                        Playlist(id = -2L, name = "Más reproducidas", songIds = mostPlayedIds, dateCreated = 0L),
                        Playlist(id = -3L, name = "Favoritas", songIds = favIds, dateCreated = 0L)
                    ).filter { it.songIds.isNotEmpty() }
                }
                PlaylistsContent(
                    playlists = playlists,
                    smartPlaylists = smartPlaylists,
                    allSongs = rawSongs,
                    searchQuery = searchQuery,
                    currentSongId = currentSong?.id,
                    isPlaying = isPlaying,
                    onCreatePlaylist = { showCreatePlaylistDialog = true },
                    onPlaylistClick = onPlaylistClick,
                    onPlayPlaylist = { playlist ->
                        val songMap = rawSongs.associateBy { it.id }
                        val pSongs = playlist.songIds.mapNotNull { songMap[it] }
                        if (pSongs.isNotEmpty()) {
                            playbackViewModel.playSong(pSongs.first(), pSongs)
                            Toast.makeText(context, "Reproduciendo ${playlist.name}", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "La playlist está vacía", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onDuplicatePlaylist = { playlist ->
                        playlistViewModel.duplicatePlaylist(playlist.id)
                        Toast.makeText(context, "Playlist duplicada", Toast.LENGTH_SHORT).show()
                    },
                    onRenamePlaylist = { playlist ->
                        playlistToRename = playlist
                        renamePlaylistText = playlist.name
                    },
                    onDeletePlaylist = { playlist ->
                        playlistToDelete = playlist
                    },
                    onExportPlaylistsJson = { playlistViewModel.exportPlaylistsJson() },
                    onImportPlaylistsJson = { jsonText, onDone ->
                        playlistViewModel.importPlaylistsJson(jsonText, onDone)
                    }
                )
            }

            LarkCategory.CARPETAS -> {
                FoldersContent(
                    folderGroups = folderGroups,
                    searchQuery = searchQuery,
                    onFolderClick = { folderName, songs ->
                        val folderPath = if (folderName.equals("OmniVault", ignoreCase = true)) {
                            "OmniVault"
                        } else {
                            songs.firstOrNull { it.path.startsWith("/") }?.path?.let { p ->
                                val idx = p.lastIndexOf('/')
                                if (idx > 0) p.substring(0, idx) else folderName
                            } ?: folderName
                        }
                        onFolderClick(folderPath)
                    }
                )
            }

            LarkCategory.ALBUMES -> {
                AlbumsContent(
                    albums = libraryViewModel.albums,
                    searchQuery = searchQuery,
                    onAlbumClick = onAlbumClick
                )
            }

            LarkCategory.ARTISTAS -> {
                ArtistsContent(
                    artists = libraryViewModel.artists,
                    searchQuery = searchQuery,
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

    LarkMusicDialogsHost(
        libraryViewModel = libraryViewModel,
        playlistViewModel = playlistViewModel,
        playbackViewModel = playbackViewModel,
        playlists = playlists,
        currentSort = currentSort,
        songsToDeleteBatch = songsToDeleteBatch,
        onClearSongsToDeleteBatch = { songsToDeleteBatch = emptyList() },
        showSortDialog = showSortDialog,
        onDismissSortDialog = { showSortDialog = false },
        songForPlaylist = songForPlaylist,
        onClearSongForPlaylist = { songForPlaylist = null },
        songsForPlaylist = songsForPlaylist,
        onClearSongsForPlaylist = { songsForPlaylist = emptyList() },
        showCreatePlaylistDialog = showCreatePlaylistDialog,
        onShowCreatePlaylistDialogChange = { showCreatePlaylistDialog = it },
        newPlaylistName = newPlaylistName,
        onNewPlaylistNameChange = { newPlaylistName = it },
        playlistToRename = playlistToRename,
        onClearPlaylistToRename = { playlistToRename = null },
        renamePlaylistText = renamePlaylistText,
        onRenamePlaylistTextChange = { renamePlaylistText = it },
        playlistToDelete = playlistToDelete,
        onClearPlaylistToDelete = { playlistToDelete = null },
        songToDelete = songToDelete,
        onClearSongToDelete = { songToDelete = null },
        songToEditTags = songToEditTags,
        onClearSongToEditTags = { songToEditTags = null },
        songForDetails = songForDetails,
        onClearSongForDetails = { songForDetails = null },
        showHiddenSongsDialog = showHiddenSongsDialog,
        onDismissHiddenSongsDialog = { showHiddenSongsDialog = false }
    )
}

/**
 * Vista de lista de canciones idéntica a Lark Player:
 * Fila compacta con carátula, título en negrita, insignia [LETRAS] y 3 puntos de opciones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SongsListContent(
    songs: List<Song>,
    currentSong: Song?,
    isPlaying: Boolean,
    currentSort: SongSort = SongSort.RECENT,
    lrcKeys: Set<String> = emptySet(),
    hiddenSongsCount: Int = 0,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onShowHiddenSongs: () -> Unit = {},
    onSongClick: (Song) -> Unit,
    onPlayAll: () -> Unit = {},
    onShuffle: () -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit = {},
    onAddToPlaylist: (Song) -> Unit,
    onAddMultipleToQueue: (List<Song>) -> Unit = {},
    onAddMultipleToPlaylist: (List<Song>) -> Unit = {},
    onHideMultiple: (List<Song>) -> Unit = {},
    onDeleteMultiple: (List<Song>) -> Unit = {},
    onEditTags: (Song) -> Unit = {},
    onShowDetails: (Song) -> Unit = {},
    onStandardize: (Song) -> Unit,
    onShare: (Song) -> Unit,
    onHide: (Song) -> Unit,
    onDelete: (Song) -> Unit
) {
    if (songs.isEmpty()) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
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
                    if (hiddenSongsCount > 0) {
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = onShowHiddenSongs) {
                            Text("Ver canciones ocultas ($hiddenSongsCount)", color = MusicAccent)
                        }
                    }
                }
            }
        }
        return
    }

    val selectedIds = remember { mutableStateListOf<Long>() }
    LaunchedEffect(songs) {
        if (selectedIds.isNotEmpty()) {
            val validIds = songs.mapTo(HashSet()) { it.id }
            selectedIds.removeAll { it !in validIds }
        }
    }
    val inSelectionMode = selectedIds.isNotEmpty()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val showScrollToTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 12 } }
    val showAlphabetIndex = (currentSort == SongSort.NAME_ASC || currentSort == SongSort.NAME_DESC) && songs.size > 30
    val currentSongIndex = remember(songs, currentSong?.id) {
        if (currentSong == null) -1 else songs.indexOfFirst { it.id == currentSong.id }
    }

    BackHandler(enabled = inSelectionMode) {
        selectedIds.clear()
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            if (inSelectionMode) {
                item {
                    Surface(
                        color = MusicCardBg,
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MusicAccent.copy(alpha = 0.5f)),
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
                                Icon(Icons.Rounded.Close, contentDescription = "Cancelar selección", tint = MusicTextPrimary, modifier = Modifier.size(18.dp))
                            }
                            Text(
                                text = "${selectedIds.size}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MusicAccent
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
                                Icon(Icons.Rounded.PlaylistAdd, contentDescription = "Añadir a playlist", tint = MusicTextPrimary, modifier = Modifier.size(20.dp))
                            }
                            IconButton(
                                onClick = {
                                    val batch = songs.filter { selectedIds.contains(it.id) }
                                    onAddMultipleToQueue(batch)
                                    selectedIds.clear()
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Rounded.QueueMusic, contentDescription = "Añadir a la cola", tint = MusicTextPrimary, modifier = Modifier.size(20.dp))
                            }
                            IconButton(
                                onClick = {
                                    val batch = songs.filter { selectedIds.contains(it.id) }
                                    onHideMultiple(batch)
                                    selectedIds.clear()
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Rounded.VisibilityOff, contentDescription = "Ocultar", tint = MusicTextPrimary, modifier = Modifier.size(20.dp))
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
                                Icon(Icons.Rounded.SelectAll, contentDescription = "Seleccionar todo", tint = MusicAccent, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            } else {
                // Cabecera rápida: Total de pistas + acciones rápidas
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${songs.size} canciones",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MusicTextPrimary.copy(alpha = 0.7f),
                                maxLines = 1
                            )
                            if (hiddenSongsCount > 0) {
                                Text(
                                    text = "Ocultas ($hiddenSongsCount)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MusicAccent,
                                    modifier = Modifier.clickable { onShowHiddenSongs() }
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (currentSongIndex >= 0) {
                                Surface(
                                    onClick = { scope.launch { listState.animateScrollToItem(currentSongIndex + 1) } },
                                    color = MusicCardBg,
                                    shape = RoundedCornerShape(12.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, MusicBorder)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.MyLocation,
                                        contentDescription = "Ir a canción actual",
                                        tint = MusicAccent,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp).size(16.dp)
                                    )
                                }
                            }

                            Surface(
                                onClick = onPlayAll,
                                color = MusicCardBg,
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MusicBorder)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = null,
                                        tint = MusicAccent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = "Todo",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MusicTextPrimary,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }

                            Surface(
                                onClick = onShuffle,
                                color = MusicCardBg,
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MusicBorder)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Shuffle,
                                        contentDescription = null,
                                        tint = MusicAccent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = "Aleatorio",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MusicTextPrimary,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
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
                    lrcKeys = lrcKeys,
                    showAlphabetPadding = showAlphabetIndex,
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
                    onEditTags = { onEditTags(song) },
                    onShowDetails = { onShowDetails(song) },
                    onStandardize = { onStandardize(song) },
                    onShare = { onShare(song) },
                    onHide = { onHide(song) },
                    onDelete = { onDelete(song) }
                )
            }
        }

        // Índice alfabético A-Z arrastrable solo en orden alfabético cuando hay > 30 canciones
        if (showAlphabetIndex) {
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
                    color = MusicAccent,
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
                            color = MusicOnAccent
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
                        color = if (activeLetter == letter) MusicAccent else MusicTextPrimary.copy(alpha = 0.5f),
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
                .padding(end = if (showAlphabetIndex) 36.dp else 16.dp, bottom = 16.dp)
        ) {
            SmallFloatingActionButton(
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                containerColor = MusicCardBg,
                contentColor = MusicAccent
            ) {
                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Ir arriba")
            }
        }
        }
    }
}

@Composable
private fun PlaylistsContent(
    playlists: List<Playlist>,
    smartPlaylists: List<Playlist> = emptyList(),
    allSongs: List<Song>,
    searchQuery: String = "",
    currentSongId: Long? = null,
    isPlaying: Boolean = false,
    onCreatePlaylist: () -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    onPlayPlaylist: (Playlist) -> Unit,
    onDuplicatePlaylist: (Playlist) -> Unit = {},
    onRenamePlaylist: (Playlist) -> Unit,
    onDeletePlaylist: (Playlist) -> Unit,
    onExportPlaylistsJson: () -> String = { "[]" },
    onImportPlaylistsJson: (String, (Int) -> Unit) -> Unit = { _, cb -> cb(0) }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val validSongIds = remember(allSongs) { allSongs.map { it.id }.toSet() }
    val songMap = remember(allSongs) { allSongs.associateBy { it.id } }
    var sortMode by remember { mutableIntStateOf(0) } // 0=Recientes, 1=A-Z, 2=Más canciones

    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val json = onExportPlaylistsJson()
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(context, "Playlists exportadas correctamente", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(context, "Error al exportar: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        onImportPlaylistsJson(text) { count ->
                            val msg = when {
                                count > 0 -> "$count playlists importadas"
                                count == 0 -> "No se encontraron playlists nuevas en el archivo"
                                else -> "Archivo JSON inválido"
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(context, "Error al importar: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    val visiblePlaylists = remember(playlists, searchQuery, sortMode, validSongIds) {
        val filtered = if (searchQuery.isBlank()) playlists else {
            val q = searchQuery.trim().lowercase()
            playlists.filter { it.name.lowercase().contains(q) }
        }
        when (sortMode) {
            1 -> filtered.sortedBy { it.name.lowercase() }
            2 -> filtered.sortedByDescending { pl -> pl.songIds.count { it in validSongIds } }
            else -> filtered.sortedByDescending { it.dateCreated }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Surface(
                onClick = onCreatePlaylist,
                color = MusicCardBg,
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MusicAccent.copy(alpha = 0.4f)),
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
                            .background(MusicAccent.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, tint = MusicAccent)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Crear nueva playlist", fontWeight = FontWeight.Bold, color = MusicTextPrimary)
                        Text("Organiza tus canciones locales", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
            }
        }

        // Barra rápida: Exportar / Importar / Ordenar playlists
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { exportLauncher.launch("omnivault_playlists.json") },
                    enabled = playlists.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Rounded.Upload, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Exportar", fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Importar", fontSize = 11.sp)
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { sortMode = (sortMode + 1) % 3 },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Rounded.Sort, contentDescription = null, modifier = Modifier.size(14.dp), tint = MusicAccent)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = when (sortMode) { 1 -> "A-Z"; 2 -> "Más canciones"; else -> "Recientes" },
                        fontSize = 11.sp,
                        color = MusicAccent
                    )
                }
            }
        }

        // Playlists Inteligentes automáticas
        if (smartPlaylists.isNotEmpty() && searchQuery.isBlank()) {
            item {
                Text(
                    text = "PLAYLISTS INTELIGENTES",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MusicAccent,
                    letterSpacing = 1.sp
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(smartPlaylists, key = { it.id }) { smart ->
                        val iconVec = when (smart.id) {
                            -1L -> Icons.Rounded.NewReleases
                            -2L -> Icons.Rounded.TrendingUp
                            else -> Icons.Rounded.Favorite
                        }
                        val hasActiveSong = currentSongId != null && currentSongId in smart.songIds
                        Surface(
                            onClick = { onPlaylistClick(smart) },
                            color = if (hasActiveSong) MusicCardActive else MusicCardBg,
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                if (hasActiveSong) 1.5.dp else 1.dp,
                                if (hasActiveSong) MusicAccent else MusicAccent.copy(alpha = 0.28f)
                            ),
                            modifier = Modifier.width(156.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(iconVec, contentDescription = null, tint = MusicAccent, modifier = Modifier.size(22.dp))
                                    if (hasActiveSong) {
                                        Surface(
                                            color = MusicAccent.copy(alpha = 0.16f),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                                            ) {
                                                Icon(
                                                    if (isPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.Pause,
                                                    contentDescription = null,
                                                    tint = MusicAccent,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                                Text(
                                                    text = if (isPlaying) "Activa" else "Pausa",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MusicAccent
                                                )
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(smart.name, fontWeight = FontWeight.Bold, color = if (hasActiveSong) MusicAccent else MusicTextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${smart.songIds.size} canciones", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            }
                        }
                    }
                }
            }
        }

        if (visiblePlaylists.isEmpty()) {
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
                            text = if (searchQuery.isBlank()) "No tienes playlists todavía" else "Sin resultados para \"$searchQuery\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray
                        )
                    }
                }
            }
        }

        items(visiblePlaylists, key = { it.id }) { playlist ->
            var itemMenuExpanded by remember { mutableStateOf(false) }
            val validCount = remember(playlist.songIds, validSongIds) {
                if (playlist.songIds.isNotEmpty()) playlist.songIds.count { it in validSongIds } else playlist.songCount
            }
            val firstArt = remember(playlist.artworkUri, playlist.songIds, songMap) {
                playlist.artworkUri?.let { android.net.Uri.parse(it) }
                    ?: playlist.songIds.firstNotNullOfOrNull { songMap[it]?.albumArtUri }
            }
            val hasActiveSong = currentSongId != null && currentSongId in playlist.songIds

            Surface(
                onClick = { onPlaylistClick(playlist) },
                color = if (hasActiveSong) MusicCardActive.copy(alpha = 0.9f) else MusicCardBg.copy(alpha = 0.7f),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(
                    if (hasActiveSong) 1.5.dp else 1.dp,
                    if (hasActiveSong) MusicAccent else MusicBorder
                ),
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
                                .background(if (com.amurayada.music.ui.theme.LocalAuraPalette.current.isDark) Color(0xFF1E2838) else Color(0xFFE2E8F0)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.QueueMusic, contentDescription = null, tint = MusicAccent)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            playlist.name,
                            fontWeight = FontWeight.SemiBold,
                            color = if (hasActiveSong) MusicAccent else MusicTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("$validCount canciones", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            if (hasActiveSong) {
                                Surface(
                                    color = MusicAccent.copy(alpha = 0.16f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Icon(
                                            if (isPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.Pause,
                                            contentDescription = null,
                                            tint = MusicAccent,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Text(
                                            text = if (isPlaying) "Reproduciendo" else "En pausa",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MusicAccent
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Box {
                        IconButton(onClick = { itemMenuExpanded = true }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Rounded.MoreVert,
                                contentDescription = "Opciones",
                                tint = MusicTextPrimary.copy(alpha = 0.6f),
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
                                text = { Text("Duplicar") },
                                leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                                onClick = {
                                    itemMenuExpanded = false
                                    onDuplicatePlaylist(playlist)
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
