package com.amurayada.music.ui.screens.settings

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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.amurayada.music.ui.viewmodel.SettingsViewModel

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
        topBar = {
            TopAppBar(
                title = { Text("Ajustes", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Volver")
                    }
                },
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
                                        text = release.changelog.ifBlank { "Mejoras de rendimiento, estabilidad y nuevas funciones." },
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
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.downloadUrl)))
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
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(status.release.downloadUrl)))
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
                        onClick = { }
                    )
                    SettingsItem(
                        title = "Versión",
                        subtitle = "v${settingsViewModel.CURRENT_VERSION} (Compilación Universal)",
                        icon = Icons.Rounded.Numbers,
                        onClick = { }
                    )
                    SettingsItem(
                        title = "Desarrollador",
                        subtitle = "OmniVault Team / SirDurand",
                        icon = Icons.Rounded.Code,
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/SirDurand1708/UniversalMediaDownloader"))
                            context.startActivity(intent)
                        }
                    )
                    SettingsItem(
                        title = "Licencia",
                        subtitle = "Código Abierto (GPL-3.0)",
                        icon = Icons.Rounded.VerifiedUser,
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
}

@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    val isGlassy = MaterialTheme.colorScheme.background == Color.Transparent
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isGlassy) Modifier.border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(16.dp)
                ) else Modifier
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isGlassy) 
                Color.White.copy(alpha = 0.12f) 
                else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            content()
        }
    }
}

@Composable
private fun SettingsItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingsToggleItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    val alpha = if (enabled) 1f else 0.5f
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
                ),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}
