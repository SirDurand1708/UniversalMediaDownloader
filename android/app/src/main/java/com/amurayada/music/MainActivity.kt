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

class MainActivity : ComponentActivity() {
    
    private val libraryViewModel: LibraryViewModel by viewModels {
        (application as MusicApplication).appContainer.libraryViewModelFactory
    }
    private val playbackViewModel: PlaybackViewModel by viewModels {
        (application as MusicApplication).appContainer.playbackViewModelFactory
    }
    private val settingsViewModel: SettingsViewModel by viewModels {
        (application as MusicApplication).appContainer.settingsViewModelFactory
    }
    private val playlistViewModel: com.amurayada.music.ui.viewmodel.PlaylistViewModel by viewModels {
        (application as MusicApplication).appContainer.playlistViewModelFactory
    }
    private val handsFreeViewModel: HandsFreeViewModel by viewModels()
    private val searchViewModel: SearchViewModel by viewModels {
        (application as MusicApplication).appContainer.searchViewModelFactory
    }
    val downloadViewModel: DownloadViewModel by viewModels {
        (application as MusicApplication).appContainer.downloadViewModelFactory
    }
    val videosViewModel: VideosViewModel by viewModels {
        (application as MusicApplication).appContainer.videosViewModelFactory
    }
    private var pendingSharedDownloadsNav by mutableStateOf(false)
    
    private var pendingAlbumUpdate: Triple<Long, String, String>? = null
    private var pendingAlbumArt: android.net.Uri? = null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        android.util.Log.d("MainActivity", "onActivityResult: requestCode=$requestCode, resultCode=$resultCode")
        if (requestCode == 1001 && resultCode == android.app.Activity.RESULT_OK) {
            android.util.Log.d("MainActivity", "Permission granted! pendingAlbumUpdate=$pendingAlbumUpdate")
            pendingAlbumUpdate?.let { (albumId, title, artist) ->
                android.util.Log.d("MainActivity", "Retrying update for album $albumId with title='$title', artist='$artist'")
                lifecycleScope.launch {
                    try {
                        val result = libraryViewModel.performUpdateAlbum(albumId, title, artist, "", pendingAlbumArt)
                        android.util.Log.d("MainActivity", "Update result: $result")
                        if (result.isSuccess) {
                            android.widget.Toast.makeText(this@MainActivity, "Álbum actualizado", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                             val exception = result.exceptionOrNull()
                             android.widget.Toast.makeText(this@MainActivity, "No se pudo actualizar: ${exception?.message}", android.widget.Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Error retrying update", e)
                        android.widget.Toast.makeText(this@MainActivity, "Error al reintentar: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                    } finally {
                        pendingAlbumUpdate = null
                        pendingAlbumArt = null
                    }
                }
            }
        } else if ((requestCode == 1003 || requestCode == 1002) && resultCode == android.app.Activity.RESULT_OK) {
            if (requestCode == 1003) {
                libraryViewModel.retryPendingTagEdit(this)
            }
            libraryViewModel.loadLibrary()
        }
    }
    
    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Aplicar modo inmersivo de pantalla completa
        try {
            com.omnivault.media.utils.NotificationHelper.createNotificationChannels(this)
        } catch (_: Exception) {}
        setupFullScreen()
        if (savedInstanceState != null) {
            val hasAlbumId = savedInstanceState.containsKey("pending_album_id")
            if (hasAlbumId) {
                val aid = savedInstanceState.getLong("pending_album_id")
                val atitle = savedInstanceState.getString("pending_album_title") ?: ""
                val aartist = savedInstanceState.getString("pending_album_artist") ?: ""
                pendingAlbumUpdate = Triple(aid, atitle, aartist)
                pendingAlbumArt = savedInstanceState.getString("pending_album_art")?.let { android.net.Uri.parse(it) }
            }
        } else {
            handleSharedIntent(intent)
        }

        libraryViewModel.permissionEvent.observe(this) { request ->
            if (request != null) {
                try {
                    startIntentSenderForResult(request.intentSender, 1003, null, 0, 0, 0, null)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Error launching permission IntentSender", e)
                } finally {
                    libraryViewModel.clearPermissionEvent()
                }
            }
        }
        
        setContent {
            val themeOverride by settingsViewModel.isDarkThemeOverride.collectAsState()
            val useDynamicColors by settingsViewModel.useDynamicColors.collectAsState()
            val isAmoledMode by settingsViewModel.isAmoledMode.collectAsState()
            val customPrimaryColor by settingsViewModel.customPrimaryColor.collectAsState()
            val customBgUri by settingsViewModel.customBackgroundImageUri.collectAsState()
            val context = androidx.compose.ui.platform.LocalContext.current
            
            // Si themeOverride es null, usar el tema del sistema
            val isDarkTheme = themeOverride ?: isSystemInDarkTheme()

            // Observar Eventos de UI del ViewModel (Toasts, etc.)
            LaunchedEffect(playbackViewModel) {
                playbackViewModel.uiEvents.collect { event ->
                    when (event) {
                        is PlaybackViewModel.UiEvent.ShowToast -> {
                            android.widget.Toast.makeText(context, event.message, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            
            MusicTheme(
                darkTheme = isDarkTheme,
                dynamicColor = useDynamicColors,
                amoledMode = isAmoledMode,
                customPrimaryColor = customPrimaryColor,
                isCustomBackground = customBgUri != null
            ) {
                val coreAudioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.READ_MEDIA_AUDIO
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }
                val permissionsToRequest = remember {
                    buildList {
                        add(coreAudioPermission)
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            add(Manifest.permission.READ_MEDIA_VIDEO)
                            add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            add(Manifest.permission.BLUETOOTH_CONNECT)
                        }
                    }
                }
                
                val permissionsState = rememberMultiplePermissionsState(permissionsToRequest)
                val coreAudioGranted = permissionsState.permissions
                    .firstOrNull { it.permission == coreAudioPermission }
                    ?.status?.isGranted == true
                
                if (coreAudioGranted) {
                    LaunchedEffect(Unit) {
                        val intent = Intent(this@MainActivity, MusicPlaybackService::class.java)

                        try {
                            startService(intent)
                        } catch (_: Exception) {
                            // En Android 12+ si la Activity aún está transicionando a background, MediaController vinculará el servicio al reproducir
                        }
                        libraryViewModel.loadLibrary()
                    }
                    
                    MusicPlayerApp(
                        libraryViewModel = libraryViewModel,
                        playbackViewModel = playbackViewModel,
                        settingsViewModel = settingsViewModel,
                        playlistViewModel = playlistViewModel,
                        handsFreeViewModel = handsFreeViewModel,
                        searchViewModel = searchViewModel,
                        downloadViewModel = downloadViewModel,
                        videosViewModel = videosViewModel,
                        pendingNavigateToDownloads = pendingSharedDownloadsNav,
                        onConsumedNavigateToDownloads = { pendingSharedDownloadsNav = false },
                        onRequestPermission = { albumId, title, artist, imageUri, intentSender ->
                            android.util.Log.d("MainActivity", "onRequestPermission: saving pending update for album $albumId")
                            pendingAlbumUpdate = Triple(albumId, title, artist)
                            pendingAlbumArt = imageUri
                            android.util.Log.d("MainActivity", "Launching IntentSender for permission request")
                            try {
                                startIntentSenderForResult(intentSender, 1001, null, 0, 0, 0, null)
                            } catch (e: Exception) {
                                android.util.Log.e("MainActivity", "Error launching IntentSender for album permission", e)
                            }
                        }
                    )
                } else {
                    PermissionRequestScreen(
                        onRequestPermissions = { permissionsState.launchMultiplePermissionRequest() },
                        onOpenSettings = {
                            try {
                                val settingsIntent = Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.fromParts("package", packageName, null)
                                )
                                startActivity(settingsIntent)
                            } catch (_: Exception) {}
                        }
                    )
                }
            }
        }
    }
    
    private fun setupFullScreen() {
        // Habilitar Edge-to-Edge (Bordes Inmersivos)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
    
    private var hasResumedOnce = false

    override fun onResume() {
        super.onResume()
        restoreFullScreenUI()
        if (hasResumedOnce) {
            libraryViewModel.loadLibrary()
            videosViewModel.loadVideos()
        } else {
            hasResumedOnce = true
        }
    }
    
    private fun restoreFullScreenUI() {
        // No-op: Edge-to-edge es persistente
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingAlbumUpdate?.let { (aid, atitle, aartist) ->
            outState.putLong("pending_album_id", aid)
            outState.putString("pending_album_title", atitle)
            outState.putString("pending_album_artist", aartist)
        }
        pendingAlbumArt?.let { outState.putString("pending_album_art", it.toString()) }
    }

    private fun handleSharedIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            val extracted = com.omnivault.media.utils.UrlUtils.extractUrlFromText(text)?.trim()
            if (!extracted.isNullOrBlank() &&
                (extracted.startsWith("http://", ignoreCase = true) || extracted.startsWith("https://", ignoreCase = true))
            ) {
                downloadViewModel.urlInput.value = extracted
                downloadViewModel.analyzeUrl(extracted)
                pendingSharedDownloadsNav = true
                intent.action = null
            }
        }
    }
}

