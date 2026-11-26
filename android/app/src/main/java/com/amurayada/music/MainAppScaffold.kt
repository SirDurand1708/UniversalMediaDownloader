package com.amurayada.music

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.amurayada.music.ui.dialogs.ConfirmDownloadLocationDialog
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import coil.compose.AsyncImage
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.amurayada.music.utils.findMainActivity
import com.amurayada.music.data.model.Song
import com.amurayada.music.service.HeadphoneUsageMonitor
import com.amurayada.music.service.MusicPlaybackService
import com.amurayada.music.ui.components.MiniPlayer
import com.amurayada.music.ui.navigation.Screen
import com.amurayada.music.ui.screens.album.AlbumDetailScreen
import com.amurayada.music.ui.screens.artist.ArtistDetailScreen
import com.amurayada.music.ui.screens.favorites.FavoritesScreen
import com.amurayada.music.ui.screens.history.HistoryScreen
import com.amurayada.music.ui.screens.home.HomeScreen
import com.amurayada.music.ui.screens.library.LibraryScreen
import com.amurayada.music.ui.screens.nowplaying.NowPlayingScreen
import com.amurayada.music.ui.screens.search.SearchScreen
import com.amurayada.music.ui.screens.settings.SettingsScreen
import com.amurayada.music.ui.theme.MusicTheme
import com.amurayada.music.ui.viewmodel.LibraryViewModel
import com.amurayada.music.ui.viewmodel.PlaybackViewModel
import com.amurayada.music.ui.viewmodel.SettingsViewModel
import com.amurayada.music.ui.viewmodel.HandsFreeViewModel
import com.amurayada.music.ui.viewmodel.SearchViewModel
import com.amurayada.music.ui.viewmodel.DownloadViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.amurayada.music.ui.screens.handsfree.HandsFreeScreen
import com.amurayada.music.ui.viewmodel.VideosViewModel
import com.amurayada.music.ui.screens.videos.VideosScreen
import com.amurayada.music.ui.screens.videos.VideoPlayerScreen
import com.amurayada.music.ui.screens.downloads.OmniVaultDownloaderScreen
import androidx.compose.ui.zIndex
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState

data class BottomNavItem(
    val route: String,
    val title: String,
    val selectedIcon: @Composable () -> Unit,
    val unselectedIcon: @Composable () -> Unit,
    val isSelected: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayerApp(
    libraryViewModel: LibraryViewModel,
    playbackViewModel: PlaybackViewModel,
    settingsViewModel: SettingsViewModel,
    playlistViewModel: com.amurayada.music.ui.viewmodel.PlaylistViewModel,
    handsFreeViewModel: HandsFreeViewModel,
    searchViewModel: SearchViewModel,
    downloadViewModel: DownloadViewModel,
    videosViewModel: VideosViewModel,
    pendingNavigateToDownloads: Boolean = false,
    onConsumedNavigateToDownloads: () -> Unit = {},
    onRequestPermission: (Long, String, String, android.net.Uri?, android.content.IntentSender) -> Unit
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val context = LocalContext.current
    val handleDeleteSong: (Song) -> Unit = { song ->
        if (playbackViewModel.currentSong?.id == song.id) {
            if (playbackViewModel.queue.size > 1) {
                playbackViewModel.skipToNext()
            } else {
                playbackViewModel.pause()
            }
        }
        playlistViewModel.removeSongFromAllPlaylists(song.id)
        libraryViewModel.deleteSong(song, context) {
            android.widget.Toast.makeText(context, "Canción eliminada", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    val currentAudioPlaylists by playlistViewModel.playlists.collectAsState()
    LaunchedEffect(libraryViewModel.songs, currentAudioPlaylists.isNotEmpty()) {
        if (libraryViewModel.songs.isNotEmpty() && currentAudioPlaylists.isNotEmpty()) {
            playlistViewModel.pruneMissingSongs(libraryViewModel.songs.mapTo(HashSet()) { it.id })
        }
    }

    // Tracker de Navegación para Depuración
    LaunchedEffect(navController) {
        navController.addOnDestinationChangedListener { _, destination, _ ->
            android.util.Log.d("Navigation", "Cambio de Destino: ${destination.route}")
        }
    }
    
    // Lógica de Ubicación de Descargas
    val showDownloadLocationDialog by downloadViewModel.showDownloadLocationDialog.collectAsState()
    
    val directoryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            downloadViewModel.setDownloadLocation("custom", uri.toString())
        }
    }
    
    if (showDownloadLocationDialog) {
        ConfirmDownloadLocationDialog(
            onDismissRequest = { downloadViewModel.dismissDialog() },
            onUseDefaultClick = {
                downloadViewModel.setDownloadLocation("default", null)
            },
            onSelectCustomClick = {
                directoryPicker.launch(null)
            }
        )
    }
    
    // Estados del Diálogo de Monitor de Auriculares (Salud Auditiva)
    var showWarning60 by remember { mutableStateOf(false) }
    var showWarning75 by remember { mutableStateOf(false) }
    var showWarning90 by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                when (intent.action) {
                    HeadphoneUsageMonitor.ACTION_SHOW_WARNING_60 -> showWarning60 = true
                    HeadphoneUsageMonitor.ACTION_SHOW_WARNING_75 -> showWarning75 = true
                    HeadphoneUsageMonitor.ACTION_SHOW_WARNING_90 -> showWarning90 = true
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(HeadphoneUsageMonitor.ACTION_SHOW_WARNING_60)
            addAction(HeadphoneUsageMonitor.ACTION_SHOW_WARNING_75)
            addAction(HeadphoneUsageMonitor.ACTION_SHOW_WARNING_90)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    // Dialog 1: 60 Minutes
    if (showWarning60) {
        AlertDialog(
            onDismissRequest = { showWarning60 = false },
            title = { Text("⏰ Hora de descansar los oídos") },
            text = { Text("Llevas 1 hora escuchando con auriculares.\n¿Te tomas un descanso de 5-10 minutos?") },
            confirmButton = {
                TextButton(onClick = { 
                    showWarning60 = false
                    if (playbackViewModel.playbackState is com.amurayada.music.data.model.PlaybackState.Playing) {
                        playbackViewModel.togglePlayPause()
                    }
                }) {
                    Text("✅ Sí, descansar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWarning60 = false }) {
                    Text("❌ No, seguir")
                }
            }
        )
    }

    // Dialog 2: 75 Minutes
    if (showWarning75) {
        var showPostpone by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(30000) // 30 seconds delay
            showPostpone = true
        }
        
        AlertDialog(
            onDismissRequest = { /* Prevent dismiss without action */ },
            title = { Text("⚠️ Descanso recomendado (en serio)") },
            text = { Text("Ya son 1 hora y 15 minutos continuos.\nTu salud auditiva necesita un respiro.") },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { 
                        showWarning75 = false
                        if (playbackViewModel.playbackState is com.amurayada.music.data.model.PlaybackState.Playing) {
                            playbackViewModel.togglePlayPause()
                        }
                    }) {
                        Text("🛑 Pausar y descansar")
                    }
                    if (showPostpone) {
                        TextButton(onClick = { showWarning75 = false }) {
                            Text("😮‍💨 Posponer 5 min")
                        }
                    }
                }
            }
        )
    }

    // Diálogo 3: 90 Minutos (Límite Crítico)
    if (showWarning90) {
        var timeLeft by remember { mutableStateOf(10) }
        var dailyCancels by remember { mutableStateOf(0) }
        
        val prefsRepo = remember {
            (context.applicationContext as? MusicApplication)?.appContainer?.preferencesRepository
                ?: com.amurayada.music.data.repository.SharedPreferencesRepositoryImpl(context)
        }
        // Cargar cancelaciones diarias
        LaunchedEffect(Unit) {
            dailyCancels = prefsRepo.getHeadphoneDailyCancels()
        }

        LaunchedEffect(timeLeft) {
            if (timeLeft > 0) {
                kotlinx.coroutines.delay(1000)
                timeLeft--
            } else {
                // Time's up -> Pause and close
                if (playbackViewModel.playbackState is com.amurayada.music.data.model.PlaybackState.Playing) {
                    playbackViewModel.togglePlayPause()
                }
                showWarning90 = false
            }
        }

        AlertDialog(
            onDismissRequest = { /* Prevent dismiss */ },
            title = { Text("🚫 Límite de uso superado") },
            text = { Text("¡90 minutos con auriculares!\nPor tu bien, la música se pausará en $timeLeft segundos.") },
            confirmButton = {
                if (dailyCancels < HeadphoneUsageMonitor.MAX_DAILY_CANCELS) {
                    TextButton(onClick = {
                        prefsRepo.incrementHeadphoneDailyCancels()
                        showWarning90 = false
                    }) {
                        Text("🆘 Cancelar pausa (${dailyCancels}/${HeadphoneUsageMonitor.MAX_DAILY_CANCELS})")
                    }
                } else {
                    TextButton(onClick = {}, enabled = false) {
                        Text("🆘 Sin cancelaciones hoy")
                    }
                }
            }
        )
    }
    
    val bottomNavItems = remember(navBackStackEntry) {
        val currentArgs = navBackStackEntry?.arguments
        val fromSource = currentArgs?.getString("from")
        val currentRoute = navBackStackEntry?.destination?.route
        
        listOf(
            BottomNavItem(
                route = Screen.Downloads.route,
                title = "Descargas",
                selectedIcon = { Icon(Icons.Filled.CloudDownload, contentDescription = null) },
                unselectedIcon = { Icon(Icons.Outlined.CloudDownload, contentDescription = null) },
                isSelected = currentRoute == Screen.Downloads.route || (fromSource == Screen.Downloads.route)
            ),
            BottomNavItem(
                route = Screen.Home.route,
                title = "Música",
                selectedIcon = { Icon(Icons.Filled.MusicNote, contentDescription = null) },
                unselectedIcon = { Icon(Icons.Outlined.MusicNote, contentDescription = null) },
                isSelected = currentRoute in listOf(
                    Screen.Home.route,
                    Screen.Library.route,
                    Screen.Search.route,
                    Screen.Playlists.route,
                    Screen.Favorites.route
                )
            ),
            BottomNavItem(
                route = Screen.Videos.route,
                title = "Videos",
                selectedIcon = { Icon(Icons.Filled.VideoLibrary, contentDescription = null) },
                unselectedIcon = { Icon(Icons.Outlined.VideoLibrary, contentDescription = null) },
                isSelected = currentRoute == Screen.Videos.route || (fromSource == Screen.Videos.route)
            ),
            BottomNavItem(
                route = Screen.Settings.route,
                title = "Ajustes",
                selectedIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                unselectedIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                isSelected = currentRoute == Screen.Settings.route || currentRoute == Screen.Equalizer.route
            )
        )
    }
    
    // States
    val songs = libraryViewModel.songs
    val albums = libraryViewModel.albums
    val artists = libraryViewModel.artists
    val searchQuery = libraryViewModel.searchQuery
    val filteredSongs = libraryViewModel.filteredSongs
    val filteredAlbums = libraryViewModel.filteredAlbums
    val filteredArtists = libraryViewModel.filteredArtists
    
    val currentSong = playbackViewModel.currentSong
    val playbackState = playbackViewModel.playbackState
    val currentPosition = playbackViewModel.currentPosition
    val playbackMode = playbackViewModel.playbackMode
    
    // Ocultar barra de navegación en pantallas de detalle y reproductor
    val showBottomNav = currentDestination?.route in listOf(
        Screen.Downloads.route,
        Screen.Home.route,
        Screen.Library.route,
        Screen.Videos.route,
        Screen.Settings.route,
        Screen.Favorites.route,
        Screen.Playlists.route,
        Screen.Search.route
    )

    // Estado del Overlay "Now Playing"
    val isAmoledMode by settingsViewModel.isAmoledMode.collectAsState()
    val customPrimaryColor by settingsViewModel.customPrimaryColor.collectAsState()
    val customBgUri by settingsViewModel.customBackgroundImageUri.collectAsState()
    val playerBgType by settingsViewModel.playerBackgroundType.collectAsState()
    val playerBgColor by settingsViewModel.playerBackgroundColor.collectAsState()
    val playerArtScale by settingsViewModel.playerAlbumArtScale.collectAsState()
    val applyBackgroundToPlayer by settingsViewModel.applyBackgroundToPlayer.collectAsState()

    
    var isPlayerExpanded by rememberSaveable { mutableStateOf(false) }
    var isMiniPlayerDismissed by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(currentSong?.id) {
        if (currentSong != null) {
            isMiniPlayerDismissed = false
        }
    }
    
    // Expandir reproductor al hacer click en mini-player o recibir evento
    LaunchedEffect(Unit) {
        playbackViewModel.expandPlayerEvent.collect {
            isPlayerExpanded = true
        }
    }

    // Redirigir a Descargas cuando llega un enlace compartido (ACTION_SEND) en frío o caliente
    LaunchedEffect(pendingNavigateToDownloads) {
        if (pendingNavigateToDownloads) {
            isPlayerExpanded = false
            handsFreeViewModel.setManualMode(false)
            navController.navigate(Screen.Downloads.route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
            onConsumedNavigateToDownloads()
        }
    }
    
    // Estado Modo Manos Libres
    val isHandsFreeMode by handsFreeViewModel.isHandsFreeMode.collectAsState()
    
    // Mantener Pantalla Encendida
    val window = (context as? android.app.Activity)?.window
    DisposableEffect(isHandsFreeMode) {
        if (isHandsFreeMode) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        if (customBgUri != null) {
            AsyncImage(
                 model = customBgUri,
                 contentDescription = null,
                 contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                 modifier = Modifier
                    .fillMaxSize()
                    .blur(10.dp) // Reducido de 15dp para Iteración 6 (Nítidez extrema)
            )
            // Capa de Vidrio Esmerilado: Sin tinte blanco para máxima claridad
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Transparent)
            )
            // Overlay de Protección para contraste
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.7f))
            )
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = if (customBgUri != null && !isPlayerExpanded) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                val showMiniPlayer = currentSong != null &&
                    !isMiniPlayerDismissed &&
                    currentDestination?.route != Screen.VideoPlayer.route
                if (showMiniPlayer || showBottomNav) {
                    var showMiniQueueSheet by remember { mutableStateOf(false) }
                    Column(
                        modifier = if (!showBottomNav) Modifier.navigationBarsPadding() else Modifier
                    ) {
                        AnimatedVisibility(
                            visible = showMiniPlayer,
                            enter = slideInVertically { it } + fadeIn(),
                            exit = slideOutVertically { it } + fadeOut()
                        ) {
                            MiniPlayer(
                                currentSong = currentSong,
                                playbackState = playbackState,
                                currentPosition = currentPosition,
                                duration = currentSong?.duration ?: 0L,
                                onPlayPauseClick = playbackViewModel::togglePlayPause,
                                onNextClick = playbackViewModel::skipToNext,
                                onPreviousClick = playbackViewModel::skipToPrevious,
                                onQueueClick = { showMiniQueueSheet = true },
                                onExpandClick = { isPlayerExpanded = true },
                                onCloseClick = {
                                    playbackViewModel.pause()
                                    isMiniPlayerDismissed = true
                                },
                                customBgUri = customBgUri?.toString(),
                                isAmoledMode = isAmoledMode
                            )
                        }
                        if (showMiniQueueSheet) {
                            ModalBottomSheet(
                                onDismissRequest = { showMiniQueueSheet = false }
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .navigationBarsPadding()
                                        .padding(bottom = 16.dp)
                                ) {
                                    Text(
                                        text = "Cola de reproducción (${playbackViewModel.queue.size})",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                                    )
                                    if (playbackViewModel.queue.isEmpty()) {
                                        Text(
                                            text = "La cola está vacía",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)
                                        )
                                    } else {
                                        androidx.compose.foundation.lazy.LazyColumn(
                                            modifier = Modifier.heightIn(max = 420.dp)
                                        ) {
                                            items(playbackViewModel.queue.size) { index ->
                                                val qSong = playbackViewModel.queue[index]
                                                val isPlayingNow = currentSong?.id == qSong.id
                                                ListItem(
                                                    headlineContent = {
                                                        Text(
                                                            text = qSong.title,
                                                            maxLines = 1,
                                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                            color = if (isPlayingNow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                        )
                                                    },
                                                    supportingContent = {
                                                        Text(
                                                            text = qSong.artist,
                                                            maxLines = 1,
                                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                        )
                                                    },
                                                    trailingContent = {
                                                        IconButton(onClick = { playbackViewModel.removeFromQueue(qSong) }) {
                                                            Icon(Icons.Rounded.Close, contentDescription = "Quitar de la cola")
                                                        }
                                                    },
                                                    modifier = Modifier.clickable {
                                                        playbackViewModel.playSong(qSong, playbackViewModel.queue)
                                                        showMiniQueueSheet = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        
                        if (showBottomNav) {
                            Box {

                                
                                NavigationBar(
                                containerColor = when {
                                    customBgUri != null -> androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.8f)
                                    isAmoledMode -> androidx.compose.ui.graphics.Color.Black
                                    else -> MaterialTheme.colorScheme.surface
                                },
                                tonalElevation = 0.dp,
                                modifier = Modifier.then(
                                    if (customBgUri != null) Modifier.drawWithCache {
                                        onDrawWithContent {
                                            drawContent()
                                            drawLine(
                                                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.15f),
                                                start = androidx.compose.ui.geometry.Offset(0f, 0f),
                                                end = androidx.compose.ui.geometry.Offset(this.size.width, 0f),
                                                strokeWidth = 1.dp.toPx()
                                            )
                                        }
                                    } else Modifier
                                )
                            ) {
                                bottomNavItems.forEach { item ->
                                    val selected = item.isSelected
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = {
                                            val currentRouteTemplate = navController.currentBackStackEntry?.destination?.route
                                            
                                            android.util.Log.d("NavigationDebug", "--------------------------------------")
                                            android.util.Log.d("NavigationDebug", "Clicked: ${item.title} (target: ${item.route})")
                                            android.util.Log.d("NavigationDebug", "Current Route: $currentRouteTemplate")
                                            android.util.Log.d("NavigationDebug", "Is Highlighted (selected): $selected")
                                            
                                            if (selected) {
                                                android.util.Log.d("NavigationDebug", "Action: TAP-TO-RESET/POP")
                                                if (currentRouteTemplate != item.route) {
                                                    android.util.Log.d("NavigationDebug", "Popping back to root: ${item.route}")
                                                    navController.popBackStack(item.route, inclusive = false)
                                                } else if (item.route == Screen.Search.route) {
                                                    android.util.Log.d("NavigationDebug", "Clearing search results at root")
                                                    searchViewModel.clearSearch()
                                                }
                                            } else {
                                                android.util.Log.d("NavigationDebug", "Action: CROSS-TAB NAVIGATE to ${item.route}")
                                                
                                                // Manejo específico para Inicio (destino inicial)
                                                if (item.route == Screen.Home.route) {
                                                    navController.navigate(item.route) {
                                                        popUpTo(navController.graph.findStartDestination().id) {
                                                            saveState = true
                                                            inclusive = false
                                                        }
                                                        launchSingleTop = true
                                                        restoreState = true
                                                    }
                                                } else {
                                                    navController.navigate(item.route) {
                                                        popUpTo(navController.graph.findStartDestination().id) {
                                                            saveState = true
                                                        }
                                                        launchSingleTop = true
                                                        restoreState = true
                                                    }
                                                }
                                            }
                                        },
                                        icon = { if (selected) item.selectedIcon() else item.unselectedIcon() },
                                        label = {
                                            Text(
                                                item.title,
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                        },
                                        colors = if (customBgUri != null) {
                                            NavigationBarItemDefaults.colors(
                                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                                unselectedIconColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f),
                                                selectedTextColor = androidx.compose.ui.graphics.Color.White,
                                                unselectedTextColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f)
                                            )
                                        } else {
                                            NavigationBarItemDefaults.colors(
                                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                                            )
                                        }
                                    )
                                }
                            }
                        }
                        }
                    }
                }
            }
        ) { paddingValues ->
            com.amurayada.music.ui.navigation.MainNavGraph(
                navController = navController,
                paddingValues = paddingValues,
                libraryViewModel = libraryViewModel,
                playbackViewModel = playbackViewModel,
                settingsViewModel = settingsViewModel,
                playlistViewModel = playlistViewModel,
                searchViewModel = searchViewModel,
                downloadViewModel = downloadViewModel,
                videosViewModel = videosViewModel,
                handleDeleteSong = handleDeleteSong,
                onRequestPermission = onRequestPermission
            )
        }
        
        // Overlay "Now Playing" (Reproductor)
        AnimatedVisibility(
            visible = isPlayerExpanded,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            NowPlayingScreen(
                currentSong = currentSong,
                playbackState = playbackState,
                currentPosition = currentPosition,
                playbackMode = playbackMode,
                isFavorite = currentSong?.let { playbackViewModel.isFavorite(it) } ?: false,
                queue = playbackViewModel.queue,
                lyrics = playbackViewModel.lyrics,
                lyricsSource = playbackViewModel.lyricsSource,
                lyricsLoadingState = playbackViewModel.lyricsLoadingState,
                sleepTimerDuration = playbackViewModel.sleepTimerDuration,
                isSleepTimerRunning = playbackViewModel.isSleepTimerRunning,
                playbackSpeed = playbackViewModel.playbackSpeed,
                onPlaybackSpeedChange = playbackViewModel::setPlaybackSpeed,
                onPlayPauseClick = playbackViewModel::togglePlayPause,
                onSkipNextClick = playbackViewModel::skipToNext,
                onSkipPreviousClick = playbackViewModel::skipToPrevious,
                onSeek = playbackViewModel::seekTo,
                onShuffleClick = playbackViewModel::toggleShuffle,
                onRepeatClick = playbackViewModel::toggleRepeatMode,
                onFavoriteClick = { currentSong?.let { playbackViewModel.toggleFavorite(it) } },
                onBackClick = { isPlayerExpanded = false },
                onEqualizerClick = { 
                    isPlayerExpanded = false
                    navController.navigate(Screen.Equalizer.route) 
                },
                onQueueItemClick = { song -> playbackViewModel.playSong(song, playbackViewModel.queue) },
                onSaveLyrics = { content -> currentSong?.let { playbackViewModel.saveLyrics(it.id, content) } },
                onRetryLoadLyrics = playbackViewModel::retryLoadLyrics,
                onSearchLyrics = { currentSong?.let { playbackViewModel.openLyricsSearch(context, it) } },
                onImportLrc = { content -> currentSong?.let { playbackViewModel.importLrcFile(it.id, content) } },
                onStartSleepTimer = playbackViewModel::startSleepTimer,
                onCancelSleepTimer = playbackViewModel::cancelSleepTimer,
                onGoToAlbum = { albumId ->
                    isPlayerExpanded = false
                    navController.navigate(Screen.AlbumDetail.createRoute(albumId))
                },
                onRemoveFromQueue = playbackViewModel::removeFromQueue,
                onReorderQueue = playbackViewModel::reorderQueue,
                onOpenHandsFree = { handsFreeViewModel.setManualMode(true) },
                
                // Modo Video
                videoState = playbackViewModel.videoState,
                canShowVideo = playbackViewModel.canShowVideo,
                canManualToggleVideo = playbackViewModel.canManualToggleVideo,
                isVideoSizeValid = playbackViewModel.isVideoSizeValid,
                onToggleVideoMode = playbackViewModel::toggleVideoMode,
                youtubeVideoUrl = playbackViewModel.youtubeVideoUrl,
                playerController = playbackViewModel.mediaController,
                settingsViewModel = settingsViewModel,
                
                onDownloadClick = { song -> 
                    // Solo usamos getSongForDownload si estamos clickeando la canción en reproducción
                    // (para recuperar la URL original de YouTube si fue reemplazada)
                    val currentSongId = playbackViewModel.currentSong?.id
                    val songForDownload = if (currentSongId == song.id) {
                        playbackViewModel.getSongForDownload() ?: song
                    } else {
                        song
                    }
                    context.findMainActivity()?.downloadViewModel?.downloadSong(songForDownload)
                },
                isAmoledMode = isAmoledMode,
                playerBackgroundType = playerBgType,
                playerBackgroundColor = playerBgColor,
                playerAlbumArtScale = playerArtScale,

                customPrimaryColor = customPrimaryColor,
                applyBackgroundToPlayer = applyBackgroundToPlayer,
                customBackgroundImageUri = customBgUri,
            )
        }
        
        // Overlay Modo Manos Libres
        AnimatedVisibility(
            visible = isHandsFreeMode,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.fillMaxSize().zIndex(10f)
        ) {
            HandsFreeScreen(
                currentSong = currentSong,
                isPlaying = playbackState is com.amurayada.music.data.model.PlaybackState.Playing,
                currentPosition = currentPosition,
                isAmoledMode = isAmoledMode,
                onPlayPause = playbackViewModel::togglePlayPause,
                onNext = playbackViewModel::skipToNext,
                onPrevious = playbackViewModel::skipToPrevious,
                onExit = { handsFreeViewModel.setManualMode(false) }
            )
        }

        // BackHandlers de overlays al final para respetar prioridad LIFO sobre pantallas/NavHost
        androidx.activity.compose.BackHandler(enabled = isPlayerExpanded) {
            isPlayerExpanded = false
        }
        androidx.activity.compose.BackHandler(enabled = isHandsFreeMode) {
            handsFreeViewModel.setManualMode(false)
        }
    }
}

@Composable
fun PermissionRequestScreen(
    onRequestPermissions: () -> Unit,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .padding(32.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                Surface(
                    modifier = Modifier.size(80.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Text(
                    text = "Permisos necesarios",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = "Para reproducir tu música, necesitamos acceso a los archivos de audio de tu dispositivo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(32.dp))
                
                Button(
                    onClick = onRequestPermissions,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "Conceder permisos",
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "Abrir ajustes de la app",
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
            }
        }
    }
}
