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
                    isInitialLoading = libraryViewModel.isLoading && displayedSongs.isEmpty() && searchQuery.isBlank(),
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
