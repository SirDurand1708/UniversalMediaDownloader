package com.amurayada.music.ui.navigation

import android.content.IntentSender
import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.amurayada.music.data.model.Song
import com.amurayada.music.ui.screens.album.AlbumDetailScreen
import com.amurayada.music.ui.screens.artist.ArtistDetailScreen
import com.amurayada.music.ui.screens.downloads.OmniVaultDownloaderScreen
import com.amurayada.music.ui.screens.favorites.FavoritesScreen
import com.amurayada.music.ui.screens.history.HistoryScreen
import com.amurayada.music.ui.screens.home.HomeScreen
import com.amurayada.music.ui.screens.library.LibraryScreen
import com.amurayada.music.ui.screens.search.SearchScreen
import com.amurayada.music.ui.screens.settings.SettingsScreen
import com.amurayada.music.ui.screens.videos.VideoPlayerScreen
import com.amurayada.music.ui.screens.videos.VideosScreen
import com.amurayada.music.ui.viewmodel.DownloadViewModel
import com.amurayada.music.ui.viewmodel.LibraryViewModel
import com.amurayada.music.ui.viewmodel.PlaybackViewModel
import com.amurayada.music.ui.viewmodel.PlaylistViewModel
import com.amurayada.music.ui.viewmodel.SearchViewModel
import com.amurayada.music.ui.viewmodel.SettingsViewModel
import com.amurayada.music.ui.viewmodel.VideosViewModel

@Composable
internal fun MainNavGraph(
    navController: NavHostController,
    paddingValues: PaddingValues,
    libraryViewModel: LibraryViewModel,
    playbackViewModel: PlaybackViewModel,
    settingsViewModel: SettingsViewModel,
    playlistViewModel: PlaylistViewModel,
    searchViewModel: SearchViewModel,
    downloadViewModel: DownloadViewModel,
    videosViewModel: VideosViewModel,
    handleDeleteSong: (Song) -> Unit,
    onRequestPermission: (Long, String, String, Uri?, IntentSender) -> Unit
) {
    val context = LocalContext.current
    val songs = libraryViewModel.songs
    val albums = libraryViewModel.albums
    val artists = libraryViewModel.artists
    val playlists by playlistViewModel.playlists.collectAsState()
    val currentSong = playbackViewModel.currentSong
    val playbackState = playbackViewModel.playbackState
            val bottomTabRoutes = remember {
                setOf(
                    Screen.Downloads.route,
                    Screen.Home.route,
                    Screen.Videos.route,
                    Screen.Settings.route
                )
            }
            NavHost(
                navController = navController,
                startDestination = Screen.Home.route,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                enterTransition = {
                    val fromTab = initialState.destination.route in bottomTabRoutes
                    val toTab = targetState.destination.route in bottomTabRoutes
                    if (fromTab && toTab) {
                        fadeIn(animationSpec = tween(150))
                    } else {
                        slideInHorizontally(
                            initialOffsetX = { it },
                            animationSpec = tween(200)
                        ) + fadeIn(animationSpec = tween(200))
                    }
                },
                exitTransition = {
                    val fromTab = initialState.destination.route in bottomTabRoutes
                    val toTab = targetState.destination.route in bottomTabRoutes
                    if (fromTab && toTab) {
                        fadeOut(animationSpec = tween(150))
                    } else {
                        slideOutHorizontally(
                            targetOffsetX = { -it / 4 },
                            animationSpec = tween(200)
                        ) + fadeOut(animationSpec = tween(200))
                    }
                },
                popEnterTransition = {
                    val fromTab = initialState.destination.route in bottomTabRoutes
                    val toTab = targetState.destination.route in bottomTabRoutes
                    if (fromTab && toTab) {
                        fadeIn(animationSpec = tween(150))
                    } else {
                        slideInHorizontally(
                            initialOffsetX = { -it / 4 },
                            animationSpec = tween(200)
                        ) + fadeIn(animationSpec = tween(200))
                    }
                },
                popExitTransition = {
                    val fromTab = initialState.destination.route in bottomTabRoutes
                    val toTab = targetState.destination.route in bottomTabRoutes
                    if (fromTab && toTab) {
                        fadeOut(animationSpec = tween(150))
                    } else {
                        slideOutHorizontally(
                            targetOffsetX = { it },
                            animationSpec = tween(200)
                        ) + fadeOut(animationSpec = tween(200))
                    }
                }
            ) {
                composable(Screen.Home.route) {
                    com.amurayada.music.ui.screens.music.LarkStyleMusicScreen(
                        libraryViewModel = libraryViewModel,
                        playbackViewModel = playbackViewModel,
                        playlistViewModel = playlistViewModel,
                        onEqualizerClick = { navController.navigate(Screen.Equalizer.route) },
                        onPlaylistClick = { playlist ->
                            navController.navigate(Screen.PlaylistDetail.createRoute(playlist.id, from = Screen.Home.route))
                        },
                        onAlbumClick = { album ->
                            navController.navigate(Screen.AlbumDetail.createRoute(album.id, from = Screen.Home.route))
                        },
                        onArtistClick = { artist ->
                            navController.navigate(Screen.ArtistDetail.createRoute(artist.id, from = Screen.Home.route))
                        },
                        onFolderClick = { folderPath ->
                            navController.navigate(Screen.FolderDetail.createRoute(folderPath))
                        }
                    )
                }
                
                composable(Screen.Library.route) {
                    val selectedTab = com.amurayada.music.ui.screens.library.LibraryTab.entries.getOrElse(libraryViewModel.selectedLibraryTab) { com.amurayada.music.ui.screens.library.LibraryTab.SONGS }
                    val scope = rememberCoroutineScope()
                    val context = LocalContext.current
                    val downloadedSongs by downloadViewModel.downloadedAudioSongs.collectAsState()
                    
                    LibraryScreen(
                        songs = songs,
                        albums = albums,
                        artists = artists,
                        genres = emptyList(),
                        downloadedSongs = downloadedSongs,
                        selectedTab = selectedTab,
                        onTabSelected = { tab -> libraryViewModel.selectedLibraryTab = tab.ordinal },
                        onSongClick = { song, songList ->
                            playbackViewModel.playSong(song, songList)
                        },
                        onAlbumClick = { album ->
                            navController.navigate(Screen.AlbumDetail.createRoute(album.id, from = Screen.Library.route))
                        },
                        onArtistClick = { artist ->
                            navController.navigate(Screen.ArtistDetail.createRoute(artist.id, from = Screen.Library.route))
                        },
                        onSearchClick = { navController.navigate(Screen.Search.route) },
                        onSettingsClick = { navController.navigate(Screen.Settings.route) },
                        currentSong = currentSong,
                        isPlaying = playbackState is com.amurayada.music.data.model.PlaybackState.Playing,
                        playlists = playlistViewModel.playlists.collectAsState().value,
                        onAddToPlaylist = { playlist, song ->
                            playlistViewModel.addSongToPlaylist(playlist.id, song.id)
                        },
                        onCreatePlaylist = {
                            navController.navigate(Screen.Playlists.route)
                        },
                        onDeleteSong = handleDeleteSong,
                        onPlayNext = { song -> playbackViewModel.playNext(song) },
                        onAddToQueue = { song -> playbackViewModel.addToQueue(song) },
                        onDeleteDownload = { song ->
                            scope.launch {
                                downloadViewModel.deleteDownload(song.id)
                                android.widget.Toast.makeText(context, "Descarga eliminada", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },

                    )
                }
                
                composable(Screen.Favorites.route) {
                    FavoritesScreen(
                        favoriteSongs = playbackViewModel.favorites,
                        onSongClick = { song ->
                            playbackViewModel.playSong(song, playbackViewModel.favorites)
                        },
                        onRemoveFavorite = { song ->
                            playbackViewModel.toggleFavorite(song)
                        }
                    )
                }
                
                composable(Screen.History.route) {
                    HistoryScreen(
                        historyItems = playbackViewModel.recentlyPlayed,
                        onSongClick = { song ->
                            playbackViewModel.playSong(song, playbackViewModel.recentlyPlayed)
                        },
                        onClearHistory = {
                            playbackViewModel.clearHistory()
                        },
                        onBackClick = { navController.popBackStack() }
                    )
                }

                composable(Screen.Playlists.route) {
                    val playlists by playlistViewModel.playlists.collectAsState()
                    com.amurayada.music.ui.screens.playlist.PlaylistScreen(
                        playlists = playlists,
                        userPlaylists = libraryViewModel.userPlaylists, // Pass Online Playlists
                        onCreatePlaylist = playlistViewModel::createPlaylist,
                        onOnlinePlaylistClick = { playlist ->
                            if (playlist.remoteId != null) {
                                navController.navigate(Screen.AlbumDetailOnline.createRoute(playlist.remoteId, from = Screen.Playlists.route))
                            }
                        },
                        onPlaylistClick = { playlist ->
                            navController.navigate(Screen.PlaylistDetail.createRoute(playlist.id, from = Screen.Playlists.route))
                        },
                        onDeletePlaylist = playlistViewModel::deletePlaylist,
                        onRenamePlaylist = playlistViewModel::renamePlaylist
                    )
                }

                composable(
                    route = Screen.PlaylistDetail.route,
                    arguments = listOf(
                        androidx.navigation.navArgument("playlistId") {
                            type = androidx.navigation.NavType.LongType
                        },
                        androidx.navigation.navArgument("from") {
                            type = androidx.navigation.NavType.StringType
                            nullable = true
                            defaultValue = null
                        }
                    )
                ) { backStackEntry ->
                    val playlistId = backStackEntry.arguments?.getLong("playlistId") ?: 0L
                    val playlists by playlistViewModel.playlists.collectAsState()
                    val cleanAudioSongs = remember(songs, libraryViewModel.hiddenSongIds, libraryViewModel.deletedSongPaths) {
                        songs.filter { s ->
                            val lower = s.path.lowercase()
                            val isVideo = s.mimeType?.startsWith("video/", ignoreCase = true) == true ||
                                s.album.equals("Video", ignoreCase = true) ||
                                s.path.contains("/video/", ignoreCase = true) ||
                                lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
                            !isVideo &&
                                !libraryViewModel.hiddenSongIds.contains(s.id) &&
                                (s.path.isBlank() || !libraryViewModel.deletedSongPaths.contains(s.path))
                        }
                    }
                    val songMap = remember(cleanAudioSongs) { cleanAudioSongs.associateBy { it.id } }
                    val playlist = remember(playlistId, playlists, cleanAudioSongs, playbackViewModel.mostPlayed, playbackViewModel.favorites) {
                        when (playlistId) {
                            -1L -> com.amurayada.music.data.model.Playlist(
                                id = -1L,
                                name = "Añadidas recientemente",
                                songIds = cleanAudioSongs.sortedByDescending { it.dateAdded }.take(50).map { it.id },
                                dateCreated = 0L
                            )
                            -2L -> com.amurayada.music.data.model.Playlist(
                                id = -2L,
                                name = "Más reproducidas",
                                songIds = playbackViewModel.mostPlayed.mapNotNull { songMap[it.id]?.id }.take(50),
                                dateCreated = 0L
                            )
                            -3L -> com.amurayada.music.data.model.Playlist(
                                id = -3L,
                                name = "Favoritas",
                                songIds = playbackViewModel.favorites.mapNotNull { songMap[it.id]?.id },
                                dateCreated = 0L
                            )
                            else -> playlists.find { it.id == playlistId }
                        }
                    }
                    val playlistSongs = remember(playlist, songMap) {
                        playlist?.songIds?.mapNotNull { songMap[it] } ?: emptyList()
                    }

                    com.amurayada.music.ui.screens.playlist.PlaylistDetailScreen(
                        playlist = playlist,
                        songs = playlistSongs,
                        allSongs = cleanAudioSongs,
                        currentSong = currentSong,
                        isPlaying = playbackState is com.amurayada.music.data.model.PlaybackState.Playing,
                        isReadOnly = playlistId < 0L,
                        onBackClick = { navController.popBackStack() },
                        onSongClick = { song ->
                            playbackViewModel.playSong(song, playlistSongs)
                        },
                        onPlayNext = { song -> playbackViewModel.playNext(song) },
                        onAddToQueue = { song -> playbackViewModel.addToQueue(song) },
                        onMoveToBoundary = { songId, toTop ->
                            playlistViewModel.moveSongToBoundary(playlistId, songId, toTop)
                        },
                        onRemoveSong = { songId ->
                            playlistViewModel.removeSongFromPlaylist(playlistId, songId)
                        },
                        onRemoveSongs = { batchIds ->
                            playlistViewModel.removeSongsFromPlaylist(playlistId, batchIds)
                        },
                        onAddSong = { song ->
                            playlistViewModel.addSongToPlaylist(playlistId, song.id)
                        },
                        onAddSongs = { batch ->
                            playlistViewModel.addSongsToPlaylist(playlistId, batch.map { it.id })
                        },
                        onMoveSong = { from, to ->
                            playlistViewModel.moveSongInPlaylist(playlistId, from, to)
                        },
                        onPlayOrderedList = { song, ordered ->
                            playbackViewModel.playSong(song, ordered)
                        },
                        onRenamePlaylist = { newName ->
                            playlistViewModel.renamePlaylist(playlistId, newName)
                        },
                        onDeletePlaylist = {
                            playlistViewModel.deletePlaylist(playlistId)
                            navController.popBackStack()
                        },
                        onDuplicatePlaylist = {
                            playlistViewModel.duplicatePlaylist(playlistId)
                        },
                        onChangeArtwork = { uri ->
                            playlistViewModel.updatePlaylistArtwork(playlistId, uri)
                        },
                        onAddPlaylistToQueue = { ordered ->
                            ordered.forEach { playbackViewModel.addToQueue(it) }
                            android.widget.Toast.makeText(context, "${ordered.size} canciones añadidas a la cola", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        onPlayAll = {
                            if (playlistSongs.isNotEmpty()) {
                                playbackViewModel.playSong(playlistSongs.first(), playlistSongs)
                            }
                        },
                        onShuffle = {
                            if (playlistSongs.isNotEmpty()) {
                                val shuffled = playlistSongs.shuffled()
                                playbackViewModel.playSong(shuffled.first(), shuffled)
                            }
                        }
                    )
                }

                composable(
                    route = Screen.FolderDetail.route,
                    arguments = listOf(
                        androidx.navigation.navArgument("path") {
                            type = androidx.navigation.NavType.StringType
                            defaultValue = ""
                        }
                    )
                ) { backStackEntry ->
                    val encodedPath = backStackEntry.arguments?.getString("path") ?: ""
                    val folderPath = android.net.Uri.decode(encodedPath)
                    val folderSongs = remember(folderPath, songs, libraryViewModel.hiddenSongIds, libraryViewModel.deletedSongPaths) {
                        songs.filter { song ->
                            val lower = song.path.lowercase()
                            val isVideo = song.album.equals("Video", ignoreCase = true) ||
                                song.path.contains("/video/", ignoreCase = true) ||
                                lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
                            val matchesFolder = if (folderPath.startsWith("/")) {
                                val lastSlash = song.path.lastIndexOf('/')
                                lastSlash > 0 && song.path.substring(0, lastSlash) == folderPath
                            } else if (folderPath.equals("OmniVault", ignoreCase = true)) {
                                song.path.startsWith("content://") ||
                                    (song.path.startsWith("/") && song.path.substringBeforeLast('/').substringAfterLast('/').equals("OmniVault", ignoreCase = true))
                            } else {
                                song.path.substringBeforeLast('/').substringAfterLast('/').ifBlank { "Almacenamiento Interno" } == folderPath ||
                                    (!song.path.startsWith("/") && folderPath == "Almacenamiento Interno")
                            }
                            matchesFolder && !isVideo &&
                                !libraryViewModel.hiddenSongIds.contains(song.id) &&
                                (song.path.isBlank() || !libraryViewModel.deletedSongPaths.contains(song.path))
                        }
                    }

                    com.amurayada.music.ui.screens.music.FolderDetailScreen(
                        folderPath = folderPath,
                        songs = folderSongs,
                        currentSong = currentSong,
                        isPlaying = playbackState is com.amurayada.music.data.model.PlaybackState.Playing,
                        onBackClick = { navController.popBackStack() },
                        onSongClick = { song -> playbackViewModel.playSong(song, folderSongs) },
                        onPlayNext = { song -> playbackViewModel.playNext(song) },
                        onAddToQueue = { song -> playbackViewModel.addToQueue(song) }
                    )
                }
                
                composable(Screen.Search.route) {
                    val searchResults = searchViewModel.searchResults
                    val searchQuery = searchViewModel.searchQuery
                    
                    SearchScreen(
                        searchQuery = searchQuery,
                        onSearchQueryChange = { query -> 
                            searchViewModel.onSearchQueryChange(query)
                            // Escritura: Debounce (Retraso intencional para evitar spam de API)
                            if (query.length > 2) {
                                searchViewModel.search(query, debounce = true)
                            }
                        },
                        onSearch = { query ->
                            searchViewModel.onSearchQueryChange(query)
                            searchViewModel.clearSuggestions()
                            // Tecla Enter: Búsqueda inmediata
                            if (query.length > 2) {
                                searchViewModel.search(query, debounce = false)
                            }
                        },
                        songs = searchResults,
                        albums = searchViewModel.albumResults,
                        artists = searchViewModel.artistResults,
                        playlists = searchViewModel.playlistResults,
                        isLoading = searchViewModel.isLoading,
                        onDownloadClick = { song ->
                            downloadViewModel.downloadSong(song)
                        },
                        historyItems = libraryViewModel.searchHistory,
                        onHistoryItemClick = { term -> 
                            searchViewModel.onSearchQueryChange(term)
                            // Click en historial: Búsqueda inmediata
                            searchViewModel.search(term, debounce = false)
                        },
                        onRemoveHistoryItem = libraryViewModel::removeFromSearchHistory,
                        onClearHistory = libraryViewModel::clearSearchHistory,
                        onSongClick = { song, _ ->
                            libraryViewModel.addToSearchHistory(searchQuery)
                            // Reproducir canción seleccionada y usar resultados como cola
                            playbackViewModel.playSong(song, searchResults)
                        },
                        onAlbumClick = { album ->
                            libraryViewModel.addToSearchHistory(searchQuery)
                            if (album.path.isNotEmpty()) {
                                navController.navigate(Screen.AlbumDetailOnline.createRoute(album.path, from = "search"))
                            } else {
                                navController.navigate(Screen.AlbumDetail.createRoute(album.id, from = "search"))
                            }
                        },
                        onArtistClick = { artist ->
                            libraryViewModel.addToSearchHistory(searchQuery)
                            if (artist.path.isNotEmpty()) {
                                navController.navigate(Screen.ArtistDetailOnline.createRoute(artist.path, from = "search"))
                            }
                        },
                        onPlaylistClick = { playlist ->
                            libraryViewModel.addToSearchHistory(searchQuery)
                            if (!playlist.remoteId.isNullOrEmpty()) {
                                navController.navigate(Screen.AlbumDetailOnline.createRoute("https://music.youtube.com/playlist?list=${playlist.remoteId}", from = "search"))
                            }
                        },
                        onBackClick = { navController.popBackStack() }
                    )
                }
                
                composable(
                    route = Screen.ArtistDetailOnline.route,
                    arguments = listOf(
                        navArgument("url") { type = NavType.StringType },
                        navArgument("from") { type = NavType.StringType; nullable = true; defaultValue = null }
                    )
                ) { backStackEntry ->
                    val url = backStackEntry.arguments?.getString("url") ?: ""
                    val fromSource = backStackEntry.arguments?.getString("from")
                    
                    LaunchedEffect(url) {
                        if (url.isNotEmpty()) {
                            searchViewModel.getArtistDetails(url)
                        }
                    }
                    
                    val artistDetails = searchViewModel.selectedArtistDetails
                    
                    if (artistDetails != null) {
                        com.amurayada.music.ui.screens.artist.ArtistDetailScreen(
                            artist = artistDetails.artist,
                            topSongs = artistDetails.topSongs,
                            albums = artistDetails.albums,
                            singles = artistDetails.singles,
                            onBackClick = { 
                                navController.popBackStack()
                                searchViewModel.clearSelectedArtist()
                            },
                            onSongClick = { song, list ->
                                playbackViewModel.playSong(song, list)
                            },
                            onAlbumClick = { album ->
                                navController.navigate(Screen.AlbumDetailOnline.createRoute(album.path, from = fromSource))
                            }
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
                
                composable(
                    route = Screen.AlbumDetailOnline.route,
                    arguments = listOf(
                        navArgument("url") { type = NavType.StringType },
                        navArgument("from") { type = NavType.StringType; nullable = true; defaultValue = null }
                    )
                ) { backStackEntry ->
                    val url = backStackEntry.arguments?.getString("url") ?: ""
                    
                    LaunchedEffect(url) {
                        if (url.isNotEmpty()) {
                            searchViewModel.getAlbumDetails(url)
                        }
                    }
                    
                    val songs = searchViewModel.selectedAlbumSongs
                    
                    val album = remember(url) {
                        com.amurayada.music.data.model.Album(
                            id = url.hashCode().toLong(),
                            name = "Loading...",
                            artist = "",
                            artworkUri = null,
                            path = url
                        )
                    }
                    
                    val displayAlbum = if (songs.isNotEmpty()) {
                        album.copy(
                            name = songs.first().album,
                            artist = songs.first().artist,
                            artworkUri = songs.first().albumArtUri,
                            songCount = songs.size
                        )
                    } else album
                    
                    val context = androidx.compose.ui.platform.LocalContext.current

                    com.amurayada.music.ui.screens.album.AlbumDetailScreen(
                        album = displayAlbum,
                        songs = songs,
                        allAlbums = emptyList(),
                        onSongClick = { song -> playbackViewModel.playSong(song, songs) },
                        onBackClick = { 
                            navController.popBackStack()
                            searchViewModel.clearSelectedAlbum()
                        },
                        onPlayAll = { if (songs.isNotEmpty()) playbackViewModel.playSong(songs.first(), songs) },
                        onShuffle = { 
                            if (songs.isNotEmpty()) {
                                val shuffled = songs.shuffled()
                                playbackViewModel.playSong(shuffled.first(), shuffled)
                            }
                        },
                        onAlbumClick = { /* No-op */ },
                        onDownloadSong = { song -> downloadViewModel.downloadSong(song) },
                        onDownloadAlbum = { albumSongs -> downloadViewModel.downloadAlbum(albumSongs) }
                    )
                }
                
                composable(Screen.Settings.route) {
                    SettingsScreen(
                        settingsViewModel = settingsViewModel,
                        playlistViewModel = playlistViewModel,
                        libraryViewModel = libraryViewModel,
                        onBackClick = { navController.popBackStack() },
                        onRescanLibrary = { libraryViewModel.loadLibrary() },
                        onLoginClick = { navController.navigate(Screen.YouTubeLogin.route) },
                        onPersonalizationClick = { navController.navigate(Screen.Personalization.route) },
                        onEqualizerClick = { navController.navigate(Screen.Equalizer.route) },
                        onChangeDownloadLocation = { downloadViewModel.requestChangeLocation() }
                    )
                }
                

                
                composable(Screen.YouTubeLogin.route) {
                    val context = LocalContext.current
                    val authManager = remember { com.amurayada.music.data.auth.YouTubeAuthManager.getInstance(context) }
                    
                    com.amurayada.music.ui.screens.login.LoginScreen(
                        onLoginSuccess = {
                            navController.popBackStack()
                            navController.popBackStack()
                            // Forzar actualización de inicio para obtener contenido personalizado
                            searchViewModel.refreshHome()
                        },
                        onLoginFailed = { message ->
                            navController.popBackStack()
                        },
                        onNavigateBack = { navController.popBackStack() },
                        authManager = authManager
                    )
                }
                
                composable(Screen.Personalization.route) {
                    com.amurayada.music.ui.screens.settings.PersonalizationScreen(
                        settingsViewModel = settingsViewModel,
                        onBackClick = { navController.popBackStack() }
                    )
                }
                
                composable(Screen.Equalizer.route) {
                    val equalizerViewModel: com.amurayada.music.ui.viewmodel.EqualizerViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                    com.amurayada.music.ui.screens.equalizer.EqualizerScreen(
                        viewModel = equalizerViewModel,
                        onBackClick = { navController.popBackStack() }
                    )
                }

                composable(Screen.Downloads.route) {
                    val downloadedSongs by downloadViewModel.downloadedSongs.collectAsState()
                    OmniVaultDownloaderScreen(
                        downloadViewModel = downloadViewModel,
                        onOpenYouTubeLogin = { navController.navigate(Screen.YouTubeLogin.route) },
                        onPlayMedia = { item: com.omnivault.media.model.DownloadItem ->
                            if (!item.format.isAudio) {
                                navController.navigate(Screen.VideoPlayer.createRoute(item.localFilePath ?: item.url, item.title))
                            } else {
                                val songId = item.id.toLongOrNull() ?: item.id.hashCode().toLong()
                                val s = downloadedSongs.firstOrNull { it.id == songId } ?: Song(
                                    id = songId,
                                    title = item.title,
                                    artist = item.artist ?: "Desconocido",
                                    album = "OmniVault",
                                    duration = 0L,
                                    albumArtUri = item.thumbnailUrl?.let { android.net.Uri.parse(it) },
                                    path = item.localFilePath ?: item.url,
                                    dateAdded = System.currentTimeMillis()
                                )
                                val audioQueue = downloadedSongs.filter {
                                    !it.album.contains("Video", ignoreCase = true) &&
                                        !it.path.contains("/video/", ignoreCase = true) &&
                                        !it.path.endsWith(".mp4", ignoreCase = true)
                                }.ifEmpty { listOf(s) }
                                playbackViewModel.playSong(s, audioQueue)
                            }
                        }
                    )
                }

                composable(Screen.Videos.route) {
                    VideosScreen(
                        videosViewModel = videosViewModel,
                        onVideoClick = { video ->
                            navController.navigate(Screen.VideoPlayer.createRoute(video.localFilePath ?: video.url, video.title))
                        }
                    )
                }

                composable(
                    route = Screen.VideoPlayer.route,
                    arguments = listOf(
                        navArgument("path") { type = NavType.StringType },
                        navArgument("title") { type = NavType.StringType }
                    )
                ) { backStackEntry ->
                    val path = backStackEntry.arguments?.getString("path") ?: ""
                    val title = backStackEntry.arguments?.getString("title") ?: ""
                    val videoQueue by videosViewModel.playbackQueue.collectAsState()
                    VideoPlayerScreen(
                        videoPath = path,
                        videoTitle = title,
                        queue = videoQueue,
                        onBackClick = { navController.popBackStack() },
                        onPlayInBackground = { videoPath, videoTitleArg, posMs ->
                            val bgSong = Song(
                                id = kotlin.math.abs(videoPath.hashCode().toLong()),
                                title = videoTitleArg.ifBlank { "Audio en segundo plano" },
                                artist = "Aura Player",
                                album = "Video a Audio",
                                duration = 0L,
                                albumArtUri = null,
                                path = videoPath,
                                dateAdded = System.currentTimeMillis()
                            )
                            playbackViewModel.playSong(bgSong, listOf(bgSong), startPositionMs = posMs)
                            android.widget.Toast.makeText(
                                context,
                                "Reproduciendo audio en segundo plano",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }

                // Detalle de Álbum - muestra canciones de un álbum local
                composable(
                    route = Screen.AlbumDetail.route,
                    arguments = listOf(
                        androidx.navigation.navArgument("albumId") { 
                            type = androidx.navigation.NavType.LongType 
                        },
                        androidx.navigation.navArgument("from") {
                            type = androidx.navigation.NavType.StringType
                            nullable = true
                            defaultValue = null
                        }
                    )
                ) { backStackEntry ->
                    val albumId = backStackEntry.arguments?.getLong("albumId") ?: 0L
                    val album = albums.find { it.id == albumId }
                    val albumSongs = songs.filter { it.album == album?.name }
                    val scope = rememberCoroutineScope()
                    val context = LocalContext.current
                    
                    AlbumDetailScreen(
                        album = album,
                        songs = albumSongs,
                        allAlbums = albums,
                        libraryVersion = libraryViewModel.libraryVersion,
                        onSongClick = { song -> playbackViewModel.playSong(song, albumSongs) },
                        onBackClick = { navController.popBackStack() },
                        onPlayAll = {
                            if (albumSongs.isNotEmpty()) {
                                playbackViewModel.playSong(albumSongs.first(), albumSongs)
                            }
                        },
                        onShuffle = {
                            if (albumSongs.isNotEmpty()) {
                                val shuffled = albumSongs.shuffled()
                                playbackViewModel.playSong(shuffled.first(), shuffled)
                            }
                        },
                        onAlbumClick = { selectedAlbum ->
                            navController.navigate("album/${selectedAlbum.id}")
                        },
                        onUpdateAlbum = { title, artist, genre, imageUri ->
                            scope.launch {
                                try {
                                    val newId = libraryViewModel.performUpdateAlbum(albumId, title, artist, genre, imageUri)
                                    if (newId != null) {
                                        android.widget.Toast.makeText(context, "Álbum actualizado", android.widget.Toast.LENGTH_SHORT).show()
                                        navController.popBackStack() // Volver a la biblioteca para refrescar
                                    } else {
                                        android.widget.Toast.makeText(context, "No se pudo actualizar", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    if (e is com.amurayada.music.data.repository.MediaRepository.RequiresPermissionException) {
                                        onRequestPermission(albumId, title, artist, imageUri, e.intentSender)
                                    } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
                                        e is android.app.RecoverableSecurityException
                                    ) {
                                        try {
                                            onRequestPermission(albumId, title, artist, imageUri, e.userAction.actionIntent.intentSender)
                                        } catch (ex: Exception) {
                                            ex.printStackTrace()
                                        }
                                    } else {
                                        val msg = e.message ?: "Error al actualizar"
                                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                                        e.printStackTrace()
                                    }
                                }
                            }
                        },
                        onDeleteSong = handleDeleteSong,
                        onDownloadSong = { song -> downloadViewModel.downloadSong(song) },
                        onDownloadAlbum = { albumSongs -> downloadViewModel.downloadAlbum(albumSongs) }
                    )
                }
                
                // Detalle de Artista - muestra canciones de un artista y sus álbumes
                composable(
                    route = Screen.ArtistDetail.route,
                    arguments = listOf(
                        androidx.navigation.navArgument("artistId") { 
                            type = androidx.navigation.NavType.LongType 
                        },
                        androidx.navigation.navArgument("from") {
                            type = androidx.navigation.NavType.StringType
                            nullable = true
                            defaultValue = null
                        }
                    )
                ) { backStackEntry ->
                    val artistId = backStackEntry.arguments?.getLong("artistId") ?: 0L
                    val fromSource = backStackEntry.arguments?.getString("from")
                    
                    val artist = artists.find { it.id == artistId }
                    val artistSongs = songs.filter { it.artist == artist?.name }
                    val artistAlbums = albums.filter { it.artist == artist?.name }
                    
                    if (artist != null) {
                        ArtistDetailScreen(
                            artist = artist,
                            topSongs = artistSongs,
                            albums = artistAlbums,
                            onSongClick = { song, list -> playbackViewModel.playSong(song, list) },
                            onAlbumClick = { album -> 
                                navController.navigate(Screen.AlbumDetail.createRoute(album.id, from = fromSource))
                            },
                            onBackClick = { navController.popBackStack() }
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Artist not found")
                        }
                    }
                }
                
                // Detalle de Género
                composable(
                    route = Screen.GenreDetail.route,
                    arguments = listOf(
                        androidx.navigation.navArgument("genreId") { 
                            type = androidx.navigation.NavType.LongType 
                        }
                    )
                ) { backStackEntry ->
                    val genreId = backStackEntry.arguments?.getLong("genreId") ?: 0L
                    val genre = libraryViewModel.genres.find { it.id == genreId }
                    
                    // Obtener canciones para este género
                    var genreSongs by remember { mutableStateOf<List<com.amurayada.music.data.model.Song>>(emptyList()) }
                    
                    LaunchedEffect(genreId) {
                        genreSongs = libraryViewModel.getSongsByGenre(genreId)
                    }
                    
                    com.amurayada.music.ui.screens.genre.GenreDetailScreen(
                        genre = genre,
                        songs = genreSongs,
                        onSongClick = { song -> playbackViewModel.playSong(song, genreSongs) },
                        onBackClick = { navController.popBackStack() },
                        onPlayAll = {
                            if (genreSongs.isNotEmpty()) {
                                playbackViewModel.playSong(genreSongs.first(), genreSongs)
                            }
                        },
                        onShuffle = {
                            if (genreSongs.isNotEmpty()) {
                                val shuffled = genreSongs.shuffled()
                                playbackViewModel.playSong(shuffled.first(), shuffled)
                            }
                        }
                    )
                }
                
                composable(Screen.Recap.route) {
                    // Fusionar canciones locales con reproducidas recientemente (incluye online)
                    // Esto asegura que las canciones online aparezcan en estadísticas si están en el historial
                    val allHistorySongs = (songs + playbackViewModel.recentlyPlayed)
                        .distinctBy { it.id }

                    com.amurayada.music.ui.screens.recap.RecapScreen(
                        allSongs = allHistorySongs,
                        playCounts = playbackViewModel.playCounts,
                        onBackClick = { navController.popBackStack() },
                        onSongClick = { song, songList ->
                            playbackViewModel.playSong(song, songList)
                        }
                    )
                }
                
                composable(Screen.TimeCapsule.route) {
                     // Fusión igual que en Recap: locales + historial online
                    val allHistorySongs = (songs + playbackViewModel.recentlyPlayed)
                        .distinctBy { it.id }

                    com.amurayada.music.ui.screens.timecapsule.TimeCapsuleScreen(
                        allSongs = allHistorySongs,
                        playCounts = playbackViewModel.playCounts,
                        onBackClick = { navController.popBackStack() },
                        onSongClick = { song, songList ->
                            playbackViewModel.playSong(song, songList)
                        }
                    )
                }
            }
}

