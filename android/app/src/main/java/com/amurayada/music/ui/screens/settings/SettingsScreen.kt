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
            Toast.makeText(context, "Videos duplicados eliminados", Toast.LENGTH_SHORT).show()
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
                    val prefs = context.getSharedPreferences("omnivault_prefs", android.content.Context.MODE_PRIVATE)
                    var clipboardEnabled by remember { mutableStateOf(prefs.getBoolean("clipboard_monitor_enabled", true)) }

                    SettingsToggleItem(
                        title = "Auto-Captura de Portapapeles",
                        subtitle = "Detectar enlaces de YouTube, TikTok, IG, FB al copiarlos",
                        icon = Icons.Rounded.ContentPaste,
                        checked = clipboardEnabled,
                        onCheckedChange = {
                            clipboardEnabled = it
                            prefs.edit().putBoolean("clipboard_monitor_enabled", it).apply()
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
                                    scope.launch {
                                        val extras = withContext(Dispatchers.IO) {
                                            libraryViewModel.songs
                                                .mapNotNull { song ->
                                                    val f = File(song.path)
                                                    val len = if (f.exists()) f.length() else 0L
                                                    if (len > 0L) Pair(song, len) else null
                                                }
                                                .groupBy { (song, len) ->
                                                    val norm = song.title.lowercase().replace(Regex("[^a-z0-9]"), "").take(24)
                                                    Pair(len, norm)
                                                }
                                                .values
                                                .filter { it.size > 1 }
                                                .flatMap { it.drop(1) }
                                        }
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
                                scope.launch {
                                    val extras = withContext(Dispatchers.IO) {
                                        val allVideos = LocalMediaScanner.scanDeviceVideo(context)
                                        allVideos
                                            .filter { it.fileSizeBytes > 0L }
                                            .groupBy {
                                                val norm = it.title.lowercase().replace(Regex("[^a-z0-9]"), "").take(24)
                                                Pair(it.fileSizeBytes, norm)
                                            }
                                            .values
                                            .filter { it.size > 1 }
                                            .flatMap { group -> group.drop(1) }
                                    }
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
            // Escudo Anti-Doze (Optimización de batería)
            if (!isIgnoringBatteryOptimizations) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.BatteryAlert,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary
                                )
                                Text(
                                    text = "Optimización de batería activa",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = "Para evitar que Android corte descargas largas con la pantalla apagada, permite que OmniVault se ejecute en segundo plano sin restricciones.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(
                                onClick = {
                                    try {
                                        val intent = Intent(
                                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        context.startActivity(intent)
                                    } catch (_: Exception) {
                                        try {
                                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                        } catch (_: Exception) {
                                            Toast.makeText(context, "Abre Ajustes > Batería para desactivar la optimización", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            ) {
                                Text("Permitir ejecución en 2º plano")
                            }
                        }
                    }
                }
            }

            // Sección de Actualizaciones de la App
            item {
                val updateStatus by settingsViewModel.updateStatus.collectAsState()

                LaunchedEffect(updateStatus) {
                    if (updateStatus is SettingsViewModel.UpdateStatus.ReadyToInstall) {
                        val apk = (updateStatus as SettingsViewModel.UpdateStatus.ReadyToInstall).apkFile
                        settingsViewModel.installApk(context, apk)
                    }
                }

                if (updateStatus is SettingsViewModel.UpdateStatus.UpdateAvailable) {
                    val release = (updateStatus as SettingsViewModel.UpdateStatus.UpdateAvailable).release
                    if (dismissedUpdateDialogVersion != release.versionName) {
                        AlertDialog(
                            onDismissRequest = { dismissedUpdateDialogVersion = release.versionName },
                            icon = { Icon(Icons.Rounded.SystemUpdate, contentDescription = null) },
                            title = { Text("Nueva versión disponible: v${release.versionName}") },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "Tu versión actual es v${settingsViewModel.CURRENT_VERSION}.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = release.body.ifBlank { "Mejoras de rendimiento, estabilidad y nuevas funciones." },
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        dismissedUpdateDialogVersion = release.versionName
                                        if (release.downloadUrl.endsWith(".apk", ignoreCase = true)) {
                                            settingsViewModel.downloadAndInstallApk(release.downloadUrl)
                                        } else {
                                            try {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.downloadUrl)))
                                            } catch (_: Exception) {
                                                Toast.makeText(context, "No hay aplicación disponible para abrir el enlace", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                ) {
                                    Text("Descargar e Instalar")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { dismissedUpdateDialogVersion = release.versionName }) {
                                    Text("Más tarde")
                                }
                            }
                        )
                    }
                }

                SettingsSection(title = "Actualizaciones de la App", icon = Icons.Rounded.SystemUpdate) {
                    SettingsItem(
                        title = "Buscar Actualización",
                        subtitle = "Versión instalada: v${settingsViewModel.CURRENT_VERSION}",
                        icon = Icons.Rounded.Refresh,
                        onClick = {
                            dismissedUpdateDialogVersion = null
                            settingsViewModel.checkForUpdates()
                        }
                    )

                    when (val status = updateStatus) {
                        is SettingsViewModel.UpdateStatus.Checking -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(14.dp))
                                Text("Consultando GitHub Releases...", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        is SettingsViewModel.UpdateStatus.UpdateAvailable -> {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                ),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                            ) {
                                Column(Modifier.padding(14.dp)) {
                                    Text("¡Nueva versión v${status.release.versionName} disponible!", fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(6.dp))
                                    Button(
                                        onClick = {
                                            if (status.release.downloadUrl.endsWith(".apk", ignoreCase = true)) {
                                                settingsViewModel.downloadAndInstallApk(status.release.downloadUrl)
                                            } else {
                                                try {
                                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(status.release.downloadUrl)))
                                                } catch (_: Exception) {
                                                    Toast.makeText(context, "No hay aplicación disponible para abrir el enlace", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Descargar e Instalar")
                                    }
                                }
                            }
                        }
                        is SettingsViewModel.UpdateStatus.Downloading -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Descargando actualización… ${(status.progress * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                LinearProgressIndicator(
                                    progress = { status.progress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        is SettingsViewModel.UpdateStatus.ReadyToInstall -> {
                            Button(
                                onClick = { settingsViewModel.installApk(context, status.apkFile) },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                            ) {
                                Text("Instalar APK descargado")
                            }
                        }
                        is SettingsViewModel.UpdateStatus.UpToDate -> {
                            SettingsItem(
                                title = "App actualizada",
                                subtitle = "Ya tienes la última versión oficial",
                                icon = Icons.Rounded.CheckCircle,
                                onClick = { settingsViewModel.checkForUpdates() }
                            )
                        }
                        is SettingsViewModel.UpdateStatus.Error -> {
                            SettingsItem(
                                title = "Error al actualizar",
                                subtitle = status.message,
                                icon = Icons.Rounded.Error,
                                onClick = { settingsViewModel.checkForUpdates() }
                            )
                        }
                        SettingsViewModel.UpdateStatus.Idle -> Unit
                    }
                }
            }

            // Sección de información
            item {
                SettingsSection(title = "Acerca de Aura Player", icon = Icons.Rounded.Info) {
                    SettingsItem(
                        title = "Nombre",
                        subtitle = "Aura Player • Edición Oficial",
                        icon = Icons.Rounded.PlayCircle,
                        showChevron = false,
                        onClick = { }
                    )
                    SettingsItem(
                        title = "Versión",
                        subtitle = "v${settingsViewModel.CURRENT_VERSION} (Compilación Universal)",
                        icon = Icons.Rounded.Numbers,
                        showChevron = false,
                        onClick = { }
                    )
                    SettingsItem(
                        title = "Desarrollador",
                        subtitle = "OmniVault Team / SirDurand",
                        icon = Icons.Rounded.Code,
                        showChevron = true,
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/SirDurand1708/UniversalMediaDownloader"))
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                Toast.makeText(context, "No hay aplicación disponible para abrir el enlace", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                    SettingsItem(
                        title = "Copiar diagnóstico técnico",
                        subtitle = "Versión, dispositivo, Android SDK y espacio libre",
                        icon = Icons.Rounded.BugReport,
                        showChevron = false,
                        onClick = {
                            val stat = android.os.StatFs(context.filesDir.absolutePath)
                            val freeMb = stat.availableBytes / (1024L * 1024L)
                            val diag = buildString {
                                appendLine("App: Aura Player / OmniVault v${settingsViewModel.CURRENT_VERSION}")
                                appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}")
                                appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                                appendLine("Espacio libre interno: $freeMb MB")
                            }
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("AuraPlayerDiag", diag))
                            Toast.makeText(context, "Diagnóstico copiado al portapapeles", Toast.LENGTH_SHORT).show()
                        }
                    )
                    var showCrashLogDialog by remember { mutableStateOf(false) }
                    var crashLogText by remember { mutableStateOf("") }
                    var crashLogSizeKb by remember { mutableStateOf(0L) }
                    LaunchedEffect(showCrashLogDialog) {
                        val sizeBytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            com.omnivault.media.utils.LocalCrashLogger.getLogSizeBytes(context)
                        }
                        crashLogSizeKb = (sizeBytes + 1023L) / 1024L
                        if (showCrashLogDialog) {
                            crashLogText = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                com.omnivault.media.utils.LocalCrashLogger.readLogs(context)
                            }
                        }
                    }
                    SettingsItem(
                        title = "Registro de errores",
                        subtitle = if (crashLogSizeKb > 0L) {
                            "Últimos registros disponibles ($crashLogSizeKb KB)"
                        } else {
                            "Sin errores registrados"
                        },
                        icon = Icons.Rounded.Article,
                        showChevron = true,
                        onClick = { showCrashLogDialog = true }
                    )
                    if (showCrashLogDialog) {
                        AlertDialog(
                            onDismissRequest = { showCrashLogDialog = false },
                            title = { Text("Registro de errores local") },
                            text = {
                                if (crashLogText.isBlank()) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.CheckCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(36.dp)
                                        )
                                        Text(
                                            text = "No se han registrado errores ni cierres inesperados.",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 320.dp)
                                            .verticalScroll(rememberScrollState())
                                    ) {
                                        Text(
                                            text = crashLogText,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                fontSize = 11.sp
                                            )
                                        )
                                    }
                                }
                            },
                            confirmButton = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (crashLogText.isNotBlank()) {
                                        TextButton(
                                            onClick = {
                                                com.omnivault.media.utils.LocalCrashLogger.clearLogs(context)
                                                crashLogText = ""
                                                crashLogSizeKb = 0L
                                                Toast.makeText(context, "Registro limpiado", Toast.LENGTH_SHORT).show()
                                            }
                                        ) {
                                            Text("Limpiar")
                                        }
                                        Button(
                                            onClick = {
                                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                cm.setPrimaryClip(android.content.ClipData.newPlainText("AuraPlayerCrashLog", crashLogText))
                                                Toast.makeText(context, "Log copiado al portapapeles", Toast.LENGTH_SHORT).show()
                                            }
                                        ) {
                                            Text("Copiar")
                                        }
                                    } else {
                                        Button(onClick = { showCrashLogDialog = false }) {
                                            Text("Cerrar")
                                        }
                                    }
                                }
                            },
                            dismissButton = if (crashLogText.isNotBlank()) {
                                {
                                    TextButton(onClick = { showCrashLogDialog = false }) {
                                        Text("Cerrar")
                                    }
                                }
                            } else null
                        )
                    }
                    SettingsItem(
                        title = "Licencia",
                        subtitle = "Código Abierto (GPL-3.0)",
                        icon = Icons.Rounded.VerifiedUser,
                        showChevron = false,
                        onClick = { }
                    )
                }
            }
        }
    }
    
    // Theme selection dialog
    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("Seleccionar tema") },
            text = {
                Column {
                    listOf(
                        "system" to "Sistema",
                        "light" to "Claro",
                        "dark" to "Oscuro"
                    ).forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    settingsViewModel.setThemeMode(value)
                                    showThemeDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = themeMode == value,
                                onClick = {
                                    settingsViewModel.setThemeMode(value)
                                    showThemeDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = { }
        )
    }
    
    // Sleep timer dialog
    if (showSleepTimerDialog) {
        AlertDialog(
            onDismissRequest = { showSleepTimerDialog = false },
            title = { Text("Temporizador de sueño") },
            text = {
                Column {
                    listOf(
                        0 to "Desactivado",
                        15 to "15 minutos",
                        30 to "30 minutos",
                        45 to "45 minutos",
                        60 to "1 hora",
                        90 to "1 hora 30 min",
                        120 to "2 horas"
                    ).forEach { (minutes, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    settingsViewModel.setSleepTimer(minutes)
                                    showSleepTimerDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = sleepTimerMinutes == minutes,
                                onClick = {
                                    settingsViewModel.setSleepTimer(minutes)
                                    showSleepTimerDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = { }
        )
    }
    
    // Rescan confirmation dialog
    if (showRescanDialog) {
        AlertDialog(
            onDismissRequest = { showRescanDialog = false },
            icon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
            title = { Text("Reescanear biblioteca") },
            text = { Text("Se buscarán nuevas canciones en tu dispositivo.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRescanLibrary()
                        showRescanDialog = false
                    }
                ) {
                    Text("Escanear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRescanDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    standardizeCandidates?.let { candidates ->
        val audioCount = candidates.count { !it.isVideo }
        val videoCount = candidates.count { it.isVideo }
        val examples = candidates.take(3)
        AlertDialog(
            onDismissRequest = { standardizeCandidates = null },
            icon = { Icon(Icons.Rounded.AutoFixHigh, contentDescription = null) },
            title = { Text("Estandarizar nombres", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Se encontraron $audioCount audios y $videoCount videos para limpiar y ordenar (se omiten notas de WhatsApp y cámara).",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (examples.isNotEmpty()) {
                        Text(
                            text = "Ejemplos:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        examples.forEach { ex ->
                            Text(
                                text = "• ${ex.currentBaseName} → ${ex.targetBaseName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val batch = candidates
                        standardizeCandidates = null
                        totalRenamedSoFar = 0
                        scope.launch {
                            val result = BulkMediaStandardizer.applyBatch(context, batch)
                            totalRenamedSoFar += result.renamedCount
                            if (result.renamedCount > 0) {
                                lastStandardizedUndoBatch = batch.map { item ->
                                    val parent = File(item.currentPath).parentFile
                                    val newPath = if (parent != null) File(parent, "${item.targetBaseName}.${item.extension}").absolutePath else item.currentPath
                                    item.copy(
                                        currentPath = newPath,
                                        currentBaseName = item.targetBaseName,
                                        targetBaseName = item.currentBaseName,
                                        newTitle = item.currentBaseName
                                    )
                                }
                            }
                            if (result.pendingPermissionChunks.isNotEmpty()) {
                                launchNextPermissionChunk?.invoke(result.pendingPermissionChunks)
                            } else {
                                onRescanLibrary()
                                Toast.makeText(
                                    context,
                                    "$totalRenamedSoFar archivos estandarizados correctamente",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                ) {
                    Text("Estandarizar (${candidates.size})")
                }
            },
            dismissButton = {
                TextButton(onClick = { standardizeCandidates = null }) {
                    Text("Cancelar")
                }
            }
        )
    }

    duplicateVideosToDelete?.let { duplicates ->
        val totalMb = duplicates.sumOf { it.fileSizeBytes } / (1024.0 * 1024.0)
        AlertDialog(
            onDismissRequest = { duplicateVideosToDelete = null },
            icon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
            title = { Text("Videos duplicados (${duplicates.size})") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = String.format("Se conservará 1 copia de cada video y puedes eliminar ${duplicates.size} copias repetidas para liberar %.1f MB:", totalMb),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    duplicates.take(4).forEach { item ->
                        Text(
                            text = "• ${item.title} (${item.fileSizeFormatted})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                    if (duplicates.size > 4) {
                        Text(
                            text = "… y ${duplicates.size - 4} más.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        val batch = duplicates
                        duplicateVideosToDelete = null
                        scope.launch {
                            val pendingUris = mutableListOf<Uri>()
                            var deletedDirect = 0
                            withContext(Dispatchers.IO) {
                                batch.forEach { item ->
                                    val mediaId = item.id.removePrefix("media_video_").toLongOrNull()
                                    if (mediaId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                        pendingUris.add(
                                            android.content.ContentUris.withAppendedId(
                                                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                                mediaId
                                            )
                                        )
                                    } else {
                                        val path = item.localFilePath
                                        if (!path.isNullOrBlank() && File(path).delete()) {
                                            deletedDirect++
                                        }
                                    }
                                }
                            }
                            if (pendingUris.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                val pi = android.provider.MediaStore.createDeleteRequest(context.contentResolver, pendingUris)
                                deleteDuplicatesLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                            } else {
                                onRescanLibrary()
                                Toast.makeText(context, "$deletedDirect duplicados eliminados", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) {
                    Text("Eliminar copias")
                }
            },
            dismissButton = {
                TextButton(onClick = { duplicateVideosToDelete = null }) {
                    Text("Cancelar")
                }
            }
        )
    }

    duplicateSongsToDelete?.let { duplicates ->
        val totalMb = duplicates.sumOf { it.second } / (1024.0 * 1024.0)
        AlertDialog(
            onDismissRequest = { duplicateSongsToDelete = null },
            icon = { Icon(Icons.Rounded.LibraryMusic, contentDescription = null) },
            title = { Text("Canciones duplicadas (${duplicates.size})") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = String.format("Se conservará 1 copia de cada canción y puedes eliminar ${duplicates.size} copias repetidas para liberar %.1f MB:", totalMb),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    duplicates.take(4).forEach { (song, len) ->
                        Text(
                            text = String.format("• %s (%.1f MB)", song.title, len / (1024.0 * 1024.0)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                    if (duplicates.size > 4) {
                        Text(
                            text = "… y ${duplicates.size - 4} más.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        val batch = duplicates
                        duplicateSongsToDelete = null
                        scope.launch {
                            val pendingUris = mutableListOf<Uri>()
                            var deletedDirect = 0
                            withContext(Dispatchers.IO) {
                                batch.forEach { (song, _) ->
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !song.path.contains(context.packageName)) {
                                        val candidateUri = if (song.path.startsWith("content://")) {
                                            Uri.parse(song.path)
                                        } else {
                                            android.content.ContentUris.withAppendedId(
                                                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                                song.id
                                            )
                                        }
                                        val existsInMediaStore = try {
                                            context.contentResolver.query(
                                                candidateUri,
                                                arrayOf(android.provider.MediaStore.Audio.Media._ID),
                                                null,
                                                null,
                                                null
                                            )?.use { it.moveToFirst() } == true
                                        } catch (_: Exception) { false }
                                        if (existsInMediaStore) {
                                            pendingUris.add(candidateUri)
                                        } else if (!song.path.startsWith("content://") && File(song.path).delete()) {
                                            deletedDirect++
                                        }
                                    } else if (!song.path.startsWith("content://") && File(song.path).delete()) {
                                        deletedDirect++
                                    }
                                }
                            }
                            if (pendingUris.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                try {
                                    val pi = android.provider.MediaStore.createDeleteRequest(context.contentResolver, pendingUris)
                                    deleteDuplicatesLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                                } catch (e: Exception) {
                                    Toast.makeText(context, "No se pudo solicitar eliminación: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                onRescanLibrary()
                                Toast.makeText(context, "$deletedDirect canciones duplicadas eliminadas", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) {
                    Text("Eliminar copias")
                }
            },
            dismissButton = {
                TextButton(onClick = { duplicateSongsToDelete = null }) {
                    Text("Cancelar")
                }
            }
        )
    }
}
