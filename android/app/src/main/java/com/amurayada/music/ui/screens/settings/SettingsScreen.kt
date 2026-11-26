package com.amurayada.music.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.amurayada.music.data.model.Song
import com.amurayada.music.ui.viewmodel.LibraryViewModel
import com.amurayada.music.ui.viewmodel.PlaylistViewModel
import com.amurayada.music.ui.viewmodel.SettingsViewModel
import com.omnivault.media.data.LocalMediaScanner
import com.omnivault.media.model.DownloadItem
import com.omnivault.media.utils.BulkMediaStandardizer
import com.omnivault.media.utils.StandardizeCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// Predefined colors for the picker
private val PredefinedColors = listOf(
    Color(0xFF6200EE), // Purple
    Color(0xFF3700B3), // Dark Purple
    Color(0xFF03DAC5), // Teal
    Color(0xFFBB86FC), // Light Purple
    Color(0xFFCF6679), // Red
    Color(0xFFE91E63), // Pink
    Color(0xFFFF5722), // Orange
    Color(0xFFFFC107), // Amber
    Color(0xFF4CAF50), // Green
    Color(0xFF2196F3), // Blue
    Color(0xFF3F51B5), // Indigo
    Color(0xFF000000)  // Black
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsViewModel: SettingsViewModel,
    onBackClick: () -> Unit,
    onRescanLibrary: () -> Unit,
    onLoginClick: () -> Unit = {},
    onPersonalizationClick: () -> Unit = {},
    onEqualizerClick: () -> Unit = {},
    onChangeDownloadLocation: () -> Unit = {},
    playlistViewModel: PlaylistViewModel? = null,
    libraryViewModel: LibraryViewModel? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showCrashLogDialog by remember { mutableStateOf(false) }
    var crashLogText by remember { mutableStateOf("") }
    var crashLogSizeKb by remember { mutableStateOf(0L) }
    

    
    val themeMode by settingsViewModel.themeMode.collectAsState()
    val dynamicColorsEnabled by settingsViewModel.useDynamicColors.collectAsState()
    val sleepTimerMinutes by settingsViewModel.sleepTimerMinutes.collectAsState()
    
    var showThemeDialog by remember { mutableStateOf(false) }
    var showRescanDialog by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var dismissedUpdateDialogVersion by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    var isScanningStandardize by remember { mutableStateOf(false) }
    var standardizeCandidates by remember { mutableStateOf<List<StandardizeCandidate>?>(null) }
    var pendingWriteChunks by remember { mutableStateOf<List<List<StandardizeCandidate>>>(emptyList()) }
    var isScanningDuplicates by remember { mutableStateOf(false) }
    var duplicateVideosToDelete by remember { mutableStateOf<List<DownloadItem>?>(null) }
    var isScanningAudioDuplicates by remember { mutableStateOf(false) }
    var duplicateSongsToDelete by remember { mutableStateOf<List<Pair<Song, Long>>?>(null) }
    var lastStandardizedUndoBatch by remember { mutableStateOf<List<StandardizeCandidate>>(emptyList()) }

    val exportPlaylistsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null && playlistViewModel != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        val playlistsArr = org.json.JSONArray(playlistViewModel.exportPlaylistsJson())
                        val hiddenArr = org.json.JSONArray()
                        libraryViewModel?.hiddenSongIds?.forEach { hiddenArr.put(it) }
                        val rootObj = org.json.JSONObject().apply {
                            put("playlists", playlistsArr)
                            put("hiddenSongIds", hiddenArr)
                        }
                        val json = rootObj.toString(2)
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(json.toByteArray(Charsets.UTF_8))
                        }
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
                Toast.makeText(
                    context,
                    if (ok) "Respaldo de playlists y ocultos exportado" else "No se pudo exportar el respaldo",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    val importPlaylistsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && playlistViewModel != null) {
            scope.launch {
                val jsonText = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    } catch (_: Exception) {
                        null
                    }
                }
                if (jsonText != null) {
                    val trimmed = jsonText.trimStart()
                    var playlistsJsonToImport = jsonText
                    var restoredHidden = 0
                    if (trimmed.startsWith("{")) {
                        try {
                            val rootObj = org.json.JSONObject(trimmed)
                            val plArr = rootObj.optJSONArray("playlists")
                            if (plArr != null) playlistsJsonToImport = plArr.toString()
                            val hiddenArr = rootObj.optJSONArray("hiddenSongIds")
                            if (hiddenArr != null && libraryViewModel != null) {
                                for (i in 0 until hiddenArr.length()) {
                                    val sid = hiddenArr.optLong(i, -1L)
                                    if (sid > 0L && !libraryViewModel.hiddenSongIds.contains(sid)) {
                                        libraryViewModel.hideSong(sid)
                                        restoredHidden++
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    playlistViewModel.importPlaylistsJson(playlistsJsonToImport) { count ->
                        val msg = when {
                            count < 0 -> "Archivo de respaldo inválido"
                            count == 0 && restoredHidden == 0 -> "No había datos nuevos para importar"
                            else -> "$count playlists y $restoredHidden ocultos restaurados"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    val deleteDuplicatesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            onRescanLibrary()
            Toast.makeText(context, "Duplicados eliminados", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(settingsViewModel) {
        settingsViewModel.duplicateDeletionEvents.collect { outcome ->
            when (outcome) {
                is com.amurayada.music.data.repository.DuplicateDeletionResult.RequiresPermission -> {
                    deleteDuplicatesLauncher.launch(
                        IntentSenderRequest.Builder(outcome.intentSender).build()
                    )
                }
                is com.amurayada.music.data.repository.DuplicateDeletionResult.DeletedDirectly -> {
                    onRescanLibrary()
                    val label = if (outcome.isVideo) "duplicados eliminados" else "canciones duplicadas eliminadas"
                    Toast.makeText(context, "${outcome.count} $label", Toast.LENGTH_SHORT).show()
                }
                is com.amurayada.music.data.repository.DuplicateDeletionResult.Error -> {
                    Toast.makeText(context, "No se pudo solicitar eliminación: ${outcome.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    var totalRenamedSoFar by remember { mutableIntStateOf(0) }

    var launchNextPermissionChunk: ((List<List<StandardizeCandidate>>) -> Unit)? = null
    val writePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val currentChunk = pendingWriteChunks.firstOrNull()
        val remaining = if (pendingWriteChunks.size > 1) pendingWriteChunks.drop(1) else emptyList()
        if (result.resultCode == Activity.RESULT_OK && currentChunk != null) {
            scope.launch {
                val res = BulkMediaStandardizer.applyBatch(context, currentChunk, collectPermissionChunks = false)
                totalRenamedSoFar += res.renamedCount
                if (remaining.isNotEmpty()) {
                    launchNextPermissionChunk?.invoke(remaining)
                } else {
                    pendingWriteChunks = emptyList()
                    onRescanLibrary()
                    Toast.makeText(
                        context,
                        "$totalRenamedSoFar archivos estandarizados correctamente",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        } else {
            pendingWriteChunks = emptyList()
            if (totalRenamedSoFar > 0) {
                onRescanLibrary()
                Toast.makeText(context, "$totalRenamedSoFar archivos estandarizados", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Permiso cancelado para archivos externos", Toast.LENGTH_SHORT).show()
            }
        }
    }

    launchNextPermissionChunk = { chunks ->
        val first = chunks.firstOrNull()
        val sender = if (first != null) BulkMediaStandardizer.createWriteRequestIntentSender(context, first) else null
        if (sender != null) {
            pendingWriteChunks = chunks
            writePermissionLauncher.launch(IntentSenderRequest.Builder(sender).build())
        } else {
            pendingWriteChunks = emptyList()
            if (totalRenamedSoFar > 0) {
                onRescanLibrary()
                Toast.makeText(context, "$totalRenamedSoFar archivos estandarizados", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var isIgnoringBatteryOptimizations by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
            } else true
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                isIgnoringBatteryOptimizations = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    
    // Equalizer launcher
    val equalizerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* Result handled */ }


    
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Ajustes", fontWeight = FontWeight.Bold) },
                modifier = Modifier.statusBarsPadding(),
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Appearance Section
            item {
                SettingsSection(title = "Apariencia", icon = Icons.Rounded.Palette) {
                    SettingsItem(
                        title = "Tema",
                        subtitle = settingsViewModel.getThemeModeDisplay(),
                        icon = Icons.Rounded.DarkMode,
                        onClick = { showThemeDialog = true }
                    )
                    
                    SettingsToggleItem(
                        title = "Colores dinámicos",
                        subtitle = "Adaptar colores según Material You",
                        icon = Icons.Rounded.ColorLens,
                        checked = dynamicColorsEnabled,
                        onCheckedChange = { settingsViewModel.setDynamicColors(it) }
                    )
                    
                    val isAmoledMode by settingsViewModel.isAmoledMode.collectAsState()
                    SettingsToggleItem(
                        title = "Modo AMOLED",
                        subtitle = "Usar fondo negro puro",
                        icon = Icons.Rounded.Brightness2,
                        checked = isAmoledMode,
                        onCheckedChange = { settingsViewModel.setAmoledMode(it) }
                    )
                }
            }
            // Personalization Section - Link to dedicated screen
            item {
                SettingsSection(title = "Personalización", icon = Icons.Rounded.Brush) {
                    SettingsItem(
                        title = "Personalizar apariencia",
                        subtitle = "Colores, fondos y reproductor",
                        icon = Icons.Rounded.Palette,
                        onClick = onPersonalizationClick
                    )
                }
            }

            // Descargas y Cola
            item {
                val autoResume by settingsViewModel.autoResumeDownloads.collectAsState()
                SettingsSection(title = "Descargas y Cola", icon = Icons.Rounded.CloudDownload) {
                    SettingsToggleItem(
                        title = "Reanudar descargas automáticamente",
                        subtitle = if (autoResume) "Al abrir la app se reanuda la cola pendiente" else "Pausa pendientes al reiniciar (requiere tocar Reanudar)",
                        icon = Icons.Rounded.PlayCircleOutline,
                        checked = autoResume,
                        onCheckedChange = { settingsViewModel.setAutoResumeDownloads(it) }
                    )
                }
            }
            
            // Audio Section
            item {
                SettingsSection(title = "Audio & Super Volumen", icon = Icons.Rounded.Equalizer) {
                    SettingsItem(
                        title = "Ecualizador & Super Volumen 300%",
                        subtitle = "Ajustar bandas, graves y amplificador de volumen a 300%",
                        icon = Icons.Rounded.Tune,
                        onClick = onEqualizerClick
                    )
                }
            }


            
            // Content Section
            item {
                SettingsSection(title = "Contenido", icon = Icons.Rounded.Web) {
                    val onlineMode by settingsViewModel.onlineMode.collectAsState()
                    SettingsToggleItem(
                        title = "Modo Online (Beta)",
                        subtitle = "Mostrar recomendaciones de YouTube Music en Inicio",
                        icon = Icons.Rounded.Public,
                        checked = onlineMode,
                        onCheckedChange = { settingsViewModel.setOnlineMode(it) }
                    )
                    
                    val isCanvasEnabled by settingsViewModel.isCanvasEnabled.collectAsState()
                    SettingsToggleItem(
                        title = "Canvas",
                        subtitle = "Videos en bucle como fondo (Beta)",
                        icon = Icons.Rounded.Movie,
                        checked = isCanvasEnabled,
                        onCheckedChange = { settingsViewModel.setCanvasEnabled(it) }
                    )

               }
            }
            
            // Storage Section
            item {
                SettingsSection(title = "Almacenamiento", icon = Icons.Rounded.Storage) {
                    SettingsItem(
                        title = "Ubicación de descarga",
                        subtitle = "Cambiar carpeta de guardado",
                        icon = Icons.Rounded.FolderOpen,
                        onClick = onChangeDownloadLocation
                    )
                }
            }
            
            // Account Section
            item {
                val authManager = remember { com.amurayada.music.data.auth.YouTubeAuthManager.getInstance(context) }
                var isLoggedIn by remember { mutableStateOf(false) }
                
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val loggedIn = authManager.isLoggedIn()
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            isLoggedIn = loggedIn
                        }
                    }
                }
                
                SettingsSection(title = "Bóveda & Captura", icon = Icons.Rounded.CloudDownload) {
                    val clipboardEnabled by settingsViewModel.clipboardMonitorEnabled.collectAsState()

                    SettingsToggleItem(
                        title = "Auto-Captura de Portapapeles",
                        subtitle = "Detectar enlaces de YouTube, TikTok, IG, FB al copiarlos",
                        icon = Icons.Rounded.ContentPaste,
                        checked = clipboardEnabled,
                        onCheckedChange = {
                            settingsViewModel.setClipboardMonitorEnabled(it)
                            if (it) {
                                com.omnivault.media.service.ClipboardMonitorService.startService(context)
                            } else {
                                com.omnivault.media.service.ClipboardMonitorService.stopService(context)
                            }
                        }
                    )
                }
            }
            
            // Spotify Section REMOVED (Consolidated into Content)

            // Library Section
            item {
                val filterShortAudio by settingsViewModel.filterShortAudio.collectAsState()
                SettingsSection(title = "Biblioteca", icon = Icons.Rounded.LibraryMusic) {
                    SettingsToggleItem(
                        title = "Ocultar audios cortos (< 30s)",
                        subtitle = "Filtra notas de voz de WhatsApp y efectos de sonido",
                        icon = Icons.Rounded.FilterAlt,
                        checked = filterShortAudio,
                        onCheckedChange = { enabled ->
                            settingsViewModel.setFilterShortAudio(enabled)
                            onRescanLibrary()
                        }
                    )
                    SettingsItem(
                        title = "Reescanear biblioteca",
                        subtitle = "Buscar nuevas canciones",
                        icon = Icons.Rounded.Refresh,
                        onClick = { showRescanDialog = true }
                    )
                    SettingsItem(
                        title = if (isScanningStandardize) "Analizando archivos..." else "Estandarizar nombres",
                        subtitle = "Limpia etiquetas y ordena audios y videos existentes",
                        icon = Icons.Rounded.AutoFixHigh,
                        onClick = {
                            if (!isScanningStandardize) {
                                isScanningStandardize = true
                                scope.launch {
                                    val found = BulkMediaStandardizer.scanCandidates(context)
                                    isScanningStandardize = false
                                    if (found.isEmpty()) {
                                        Toast.makeText(
                                            context,
                                            "¡Tus audios y videos ya están estandarizados!",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        standardizeCandidates = found
                                    }
                                }
                            }
                        }
                    )
                    if (lastStandardizedUndoBatch.isNotEmpty()) {
                        SettingsItem(
                            title = "Deshacer último estandarizado",
                            subtitle = "Revertir ${lastStandardizedUndoBatch.size} archivos renombrados",
                            icon = Icons.Rounded.Undo,
                            onClick = {
                                val undoBatch = lastStandardizedUndoBatch
                                lastStandardizedUndoBatch = emptyList()
                                scope.launch {
                                    val result = BulkMediaStandardizer.applyBatch(context, undoBatch)
                                    if (result.pendingPermissionChunks.isNotEmpty()) {
                                        launchNextPermissionChunk?.invoke(result.pendingPermissionChunks)
                                    } else {
                                        onRescanLibrary()
                                        Toast.makeText(
                                            context,
                                            "${result.renamedCount} nombres restaurados",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        )
                    }
                    if (libraryViewModel != null) {
                        SettingsItem(
                            title = if (isScanningAudioDuplicates) "Buscando audios duplicados..." else "Buscar canciones duplicadas",
                            subtitle = "Detecta audios idénticos en tu biblioteca",
                            icon = Icons.Rounded.LibraryMusic,
                            onClick = {
                                if (!isScanningAudioDuplicates) {
                                    isScanningAudioDuplicates = true
                                    settingsViewModel.findDuplicateAudios(libraryViewModel.songs) { extras ->
                                        isScanningAudioDuplicates = false
                                        if (extras.isEmpty()) {
                                            Toast.makeText(context, "No se encontraron canciones duplicadas", Toast.LENGTH_SHORT).show()
                                        } else {
                                            duplicateSongsToDelete = extras
                                        }
                                    }
                                }
                            }
                        )
                    }
                    SettingsItem(
                        title = if (isScanningDuplicates) "Buscando duplicados..." else "Buscar videos duplicados",
                        subtitle = "Detecta copias idénticas para liberar espacio",
                        icon = Icons.Rounded.ContentCopy,
                        onClick = {
                            if (!isScanningDuplicates) {
                                isScanningDuplicates = true
                                settingsViewModel.findDuplicateVideos { extras ->
                                    isScanningDuplicates = false
                                    if (extras.isEmpty()) {
                                        Toast.makeText(context, "No se encontraron videos duplicados", Toast.LENGTH_SHORT).show()
                                    } else {
                                        duplicateVideosToDelete = extras
                                    }
                                }
                            }
                        }
                    )
                    if (playlistViewModel != null) {
                        SettingsItem(
                            title = "Exportar playlists (.json)",
                            subtitle = "Guarda un respaldo local de tus listas",
                            icon = Icons.Rounded.UploadFile,
                            onClick = { exportPlaylistsLauncher.launch("auraplayer_playlists_backup.json") }
                        )
                        SettingsItem(
                            title = "Importar playlists (.json)",
                            subtitle = "Restaura listas desde un archivo de respaldo",
                            icon = Icons.Rounded.DownloadForOffline,
                            onClick = { importPlaylistsLauncher.launch(arrayOf("application/json", "*/*")) }
                        )
                    }
                }
            }
            settingsBatteryUpdatesAndAboutSections(
                context = context,
                settingsViewModel = settingsViewModel,
                isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations,
                dismissedUpdateDialogVersion = dismissedUpdateDialogVersion,
                onDismissUpdateDialog = { dismissedUpdateDialogVersion = it },
                onResetDismissedUpdateDialog = { dismissedUpdateDialogVersion = null },
                showCrashLogDialog = showCrashLogDialog,
                onOpenCrashLogDialog = { showCrashLogDialog = true },
                crashLogSizeKb = crashLogSizeKb,
                onCrashLogLoaded = { sizeKb, logText ->
                    crashLogSizeKb = sizeKb
                    if (logText != null) crashLogText = logText
                }
            )
        }
    }

    SettingsDialogsHost(
        settingsViewModel = settingsViewModel,
        scope = scope,
        themeMode = themeMode,
        sleepTimerMinutes = sleepTimerMinutes,
        showCrashLogDialog = showCrashLogDialog,
        onDismissCrashLog = { showCrashLogDialog = false },
        crashLogText = crashLogText,
        onClearCrashLog = {
            crashLogText = ""
            crashLogSizeKb = 0L
        },
        showThemeDialog = showThemeDialog,
        onDismissThemeDialog = { showThemeDialog = false },
        showSleepTimerDialog = showSleepTimerDialog,
        onDismissSleepTimerDialog = { showSleepTimerDialog = false },
        showRescanDialog = showRescanDialog,
        onDismissRescanDialog = { showRescanDialog = false },
        onRescanLibrary = onRescanLibrary,
        standardizeCandidates = standardizeCandidates,
        onDismissStandardize = { standardizeCandidates = null },
        onStandardizeSuccess = { renamedCount, undoBatch, pendingChunks ->
            totalRenamedSoFar = renamedCount
            if (undoBatch.isNotEmpty()) {
                lastStandardizedUndoBatch = undoBatch
            }
            if (pendingChunks.isNotEmpty()) {
                launchNextPermissionChunk?.invoke(pendingChunks)
            } else {
                onRescanLibrary()
                Toast.makeText(
                    context,
                    "$totalRenamedSoFar archivos estandarizados correctamente",
                    Toast.LENGTH_LONG
                ).show()
            }
        },
        duplicateVideosToDelete = duplicateVideosToDelete,
        onDismissDuplicateVideos = { duplicateVideosToDelete = null },
        duplicateSongsToDelete = duplicateSongsToDelete,
        onDismissDuplicateSongs = { duplicateSongsToDelete = null }
    )
}
