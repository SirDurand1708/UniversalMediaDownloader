package com.amurayada.music.ui.screens.downloads

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.amurayada.music.data.model.Song
import com.amurayada.music.ui.viewmodel.DownloadViewModel
import com.omnivault.media.downloader.UniversalDownloadState
import com.omnivault.media.model.DownloadItem
import com.omnivault.media.model.DownloadStatus
import com.omnivault.media.model.MediaFormat

@Composable
fun OmniVaultDownloaderScreen(
    downloadViewModel: DownloadViewModel,
    onPlayMedia: (DownloadItem) -> Unit = {},
    onOpenYouTubeLogin: () -> Unit = {}
) {
    val context = LocalContext.current
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
    val urlInput by downloadViewModel.urlInput.collectAsState()
    val selectedFormat by downloadViewModel.selectedFormat.collectAsState()
    val mediaPreview by downloadViewModel.mediaPreview.collectAsState()
    val isAnalyzing by downloadViewModel.isAnalyzing.collectAsState()
    val analyzeTimeoutMessage by downloadViewModel.analyzeTimeoutMessage.collectAsState()
    val universalDownloadStates by downloadViewModel.universalDownloadStates.collectAsState()
    val downloadedSongs by downloadViewModel.downloadedSongs.collectAsState()
    val batchUrls by downloadViewModel.batchUrls.collectAsState()
    val hybridPlaylistPromptUrl by downloadViewModel.hybridPlaylistPromptUrl.collectAsState()
    val playlistEntries by downloadViewModel.playlistEntries.collectAsState()
    val isExtractingPlaylist by downloadViewModel.isExtractingPlaylist.collectAsState()
    val duplicateWarning by downloadViewModel.duplicateWarning.collectAsState()
    val lastQueuedUrl by downloadViewModel.lastQueuedUrl.collectAsState()
    val batchFeedbackMessage by downloadViewModel.batchFeedbackMessage.collectAsState()

    LaunchedEffect(batchFeedbackMessage) {
        batchFeedbackMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            downloadViewModel.batchFeedbackMessage.value = null
        }
    }

    val activeDownloadList = remember(universalDownloadStates) {
        universalDownloadStates.values.filter { state ->
            state.status == DownloadStatus.QUEUED ||
                state.status == DownloadStatus.FETCHING_METADATA ||
                state.status == DownloadStatus.DOWNLOADING ||
                state.status == DownloadStatus.PROCESSING
        }
    }
    val pausedDownloadList = remember(universalDownloadStates) {
        universalDownloadStates.values.filter { it.status == DownloadStatus.PAUSED }
    }
    val failedDownloadList = remember(universalDownloadStates) {
        universalDownloadStates.values.filter { it.status == DownloadStatus.ERROR }
    }

    var completedFilter by remember { mutableStateOf(0) } // 0 = Todos, 1 = Audios, 2 = Videos
    var completedSearchQuery by remember { mutableStateOf("") }

    val filteredCompletedSongs = remember(downloadedSongs, completedFilter, completedSearchQuery) {
        val query = completedSearchQuery.trim()
        downloadedSongs.filter { song ->
            val mimeType = song.mimeType
            val isVideo = mimeType?.startsWith("video/") == true ||
                song.album.contains("Video", ignoreCase = true) ||
                song.path.contains("/video/", ignoreCase = true) ||
                song.path.endsWith(".mp4", ignoreCase = true) ||
                song.path.endsWith(".mkv", ignoreCase = true) ||
                song.path.endsWith(".webm", ignoreCase = true)

            val matchesTab = when (completedFilter) {
                1 -> !isVideo
                2 -> isVideo
                else -> true
            }
            val matchesQuery = query.isEmpty() ||
                song.title.contains(query, ignoreCase = true) ||
                song.artist.contains(query, ignoreCase = true)

            matchesTab && matchesQuery
        }
    }

    val palette = com.amurayada.music.ui.theme.LocalAuraPalette.current
    val accentColor = palette.accent
    val darkOnAccent = palette.onAccent
    val cardBg = palette.cardBg
    val borderCol = palette.border
    var selectedDownloadTab by remember { mutableIntStateOf(0) } // 0 = Nuevo, 1 = Completadas
    val listState = rememberLazyListState()
    LaunchedEffect(selectedDownloadTab) {
        listState.scrollToItem(0)
    }
    var isAudioFormatTab by remember(selectedFormat) { mutableStateOf(selectedFormat.isAudio) }

    var detectedClipboardUrl by remember { mutableStateOf<String?>(null) }
    var clipboardTick by remember { mutableIntStateOf(0) }

    fun readClipboardUrl(): String {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                ?.coerceToText(context)?.toString()?.trim() ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    DisposableEffect(lifecycleOwner) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
            clipboardTick++
        }
        try {
            clipboard?.addPrimaryClipChangedListener(clipListener)
        } catch (_: Exception) {}

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                clipboardTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        onDispose {
            try {
                clipboard?.removePrimaryClipChangedListener(clipListener)
            } catch (_: Exception) {}
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        }
    }

    LaunchedEffect(urlInput, lastQueuedUrl, clipboardTick) {
        if (urlInput.isBlank()) {
            val clipText = readClipboardUrl()
            if ((clipText.startsWith("http://", ignoreCase = true) ||
                    clipText.startsWith("https://", ignoreCase = true)) &&
                clipText != lastQueuedUrl
            ) {
                detectedClipboardUrl = clipText
            } else {
                detectedClipboardUrl = null
            }
        } else {
            detectedClipboardUrl = null
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Header estilo Seal / Material You + 2 Sub-pestañas pill
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(Color(0xFF00E676), Color(0xFF00E5FF), Color(0xFF7C4DFF))
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CloudDownload,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                        Text(
                            text = "Descargas",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = palette.textPrimary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    var isYtLoggedIn by remember { mutableStateOf(false) }
                    LaunchedEffect(clipboardTick) {
                        isYtLoggedIn = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            try { com.amurayada.music.data.auth.YouTubeAuthManager.getInstance(context).isLoggedIn() } catch (_: Exception) { false }
                        }
                    }
                    IconButton(onClick = onOpenYouTubeLogin) {
                        Icon(
                            imageVector = Icons.Rounded.AccountCircle,
                            contentDescription = if (isYtLoggedIn) "YT conectado" else "Sesión YT",
                            tint = if (isYtLoggedIn) accentColor else palette.textPrimary.copy(alpha = 0.72f),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                // Selector pill de 2 sub-pestañas con paleta de Música: Nuevo | Completadas
                Surface(
                    color = cardBg,
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, borderCol),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val isNewTab = selectedDownloadTab == 0
                        Surface(
                            onClick = { selectedDownloadTab = 0 },
                            shape = RoundedCornerShape(20.dp),
                            color = if (isNewTab) accentColor else Color.Transparent,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "Nuevo",
                                fontSize = 14.sp,
                                fontWeight = if (isNewTab) FontWeight.Bold else FontWeight.Medium,
                                color = if (isNewTab) darkOnAccent else palette.textPrimary.copy(alpha = 0.68f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(vertical = 9.dp, horizontal = 10.dp)
                            )
                        }

                        val isCompletedTab = selectedDownloadTab == 1
                        Surface(
                            onClick = { selectedDownloadTab = 1 },
                            shape = RoundedCornerShape(20.dp),
                            color = if (isCompletedTab) accentColor else Color.Transparent,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "Completadas (${downloadedSongs.size})",
                                fontSize = 14.sp,
                                fontWeight = if (isCompletedTab) FontWeight.Bold else FontWeight.Medium,
                                color = if (isCompletedTab) darkOnAccent else palette.textPrimary.copy(alpha = 0.68f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(vertical = 9.dp, horizontal = 10.dp)
                            )
                        }
                    }
                }
            }
        }

        if (selectedDownloadTab == 0) {

        // Escudo Anti-Doze (Optimización de batería)
        if (!isIgnoringBatteryOptimizations) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (palette.isDark) Color(0xFF2D2212).copy(alpha = 0.9f) else Color(0xFFFFF8E1)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0xFFFFB300).copy(alpha = 0.45f))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Rounded.BatteryAlert,
                                contentDescription = null,
                                tint = Color(0xFFFFB300),
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Ahorro de batería activo",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (palette.isDark) Color(0xFFFFE082) else Color(0xFF92400E)
                            )
                        }
                        Text(
                            text = "Android puede pausar descargas largas al apagar la pantalla. Permite la ejecución sin restricciones para evitar cortes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textPrimary.copy(alpha = 0.85f)
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
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFFD54F)),
                            border = BorderStroke(1.dp, Color(0xFFFFB300).copy(alpha = 0.6f))
                        ) {
                            Icon(Icons.Rounded.Shield, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Permitir ejecución en 2º plano",
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // 2. Barra compacta de enlace + botón Analizar en la misma fila
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = {
                            downloadViewModel.urlInput.value = it
                            val multi = com.omnivault.media.utils.UrlUtils.extractAllUrlsFromText(it)
                            if (multi.size > 1) {
                                downloadViewModel.batchUrls.value = multi
                            } else if (downloadViewModel.batchUrls.value.isNotEmpty()) {
                                downloadViewModel.batchUrls.value = emptyList()
                            }
                        },
                        placeholder = {
                            Text(
                                "Pega un enlace...",
                                fontSize = 13.5.sp,
                                color = palette.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (urlInput.isNotEmpty()) {
                                    IconButton(
                                        onClick = {
                                            downloadViewModel.abortAnalysis()
                                            downloadViewModel.urlInput.value = ""
                                            downloadViewModel.mediaPreview.value = null
                                            downloadViewModel.batchUrls.value = emptyList()
                                        },
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(Icons.Rounded.Clear, contentDescription = "Limpiar", tint = palette.textSecondary, modifier = Modifier.size(18.dp))
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        val clipText = readClipboardUrl()
                                        if (clipText.isNotBlank()) {
                                            if (downloadViewModel.urlInput.value != clipText) {
                                                downloadViewModel.mediaPreview.value = null
                                            }
                                            downloadViewModel.urlInput.value = clipText
                                            downloadViewModel.analyzeUrl(clipText)
                                            detectedClipboardUrl = null
                                        } else {
                                            Toast.makeText(context, "El portapapeles está vacío", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Rounded.ContentPaste, contentDescription = "Pegar", tint = palette.textPrimary.copy(alpha = 0.85f), modifier = Modifier.size(19.dp))
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(26.dp),
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accentColor,
                            unfocusedBorderColor = borderCol,
                            focusedTextColor = palette.textPrimary,
                            unfocusedTextColor = palette.textPrimary
                        )
                    )

                    Button(
                        onClick = {
                            if (isAnalyzing) {
                                downloadViewModel.abortAnalysis()
                            } else {
                                downloadViewModel.analyzeUrl(urlInput)
                            }
                        },
                        enabled = urlInput.isNotBlank() || isAnalyzing,
                        shape = RoundedCornerShape(26.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        modifier = Modifier.height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accentColor,
                            contentColor = darkOnAccent,
                            disabledContainerColor = accentColor.copy(alpha = 0.35f),
                            disabledContentColor = darkOnAccent.copy(alpha = 0.6f)
                        )
                    ) {
                        if (isAnalyzing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = darkOnAccent,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Parar", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        } else {
                            Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Analizar", fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                        }
                    }
                }

                detectedClipboardUrl?.let { clipUrl ->
                    Surface(
                        onClick = {
                            val freshClip = readClipboardUrl().ifBlank { clipUrl }
                            if (downloadViewModel.urlInput.value != freshClip) {
                                downloadViewModel.mediaPreview.value = null
                            }
                            downloadViewModel.urlInput.value = freshClip
                            downloadViewModel.analyzeUrl(freshClip)
                            detectedClipboardUrl = null
                        },
                        shape = RoundedCornerShape(14.dp),
                        color = accentColor.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.ContentPaste,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Enlace en portapapeles • Analizar",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = accentColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { detectedClipboardUrl = null },
                                modifier = Modifier.size(22.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.Clear,
                                    contentDescription = "Descartar",
                                    tint = palette.textSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = analyzeTimeoutMessage != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (palette.isDark) Color(0xFF2B2214) else Color(0xFFFFF8E1)
                        ),
                        border = BorderStroke(1.dp, Color(0xFFFFB300).copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = analyzeTimeoutMessage ?: "",
                                fontSize = 12.sp,
                                color = if (palette.isDark) Color(0xFFFFE082) else Color(0xFF92400E)
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = { downloadViewModel.abortAnalysis() },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Abortar", fontSize = 12.sp, color = palette.textSecondary)
                                }
                                Spacer(Modifier.width(6.dp))
                                TextButton(
                                    onClick = { downloadViewModel.analyzeUrl(urlInput) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Reintentar", fontSize = 12.sp, color = accentColor, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }

        newDownloadPreviewAndControls(
            context = context,
            palette = palette,
            cardBg = cardBg,
            borderCol = borderCol,
            accentColor = accentColor,
            darkOnAccent = darkOnAccent,
            downloadViewModel = downloadViewModel,
            mediaPreview = mediaPreview,
            isAudioFormatTab = isAudioFormatTab,
            onAudioFormatTabChange = { isAudioFormatTab = it },
            selectedFormat = selectedFormat,
            batchUrls = batchUrls,
            urlInput = urlInput,
            activeDownloadList = activeDownloadList,
            pausedDownloadList = pausedDownloadList,
            failedDownloadList = failedDownloadList,
            onNavigateToCompletedTab = { selectedDownloadTab = 1 }
        )
        } else {
        completedDownloadsTab(
            context = context,
            palette = palette,
            cardBg = cardBg,
            borderCol = borderCol,
            accentColor = accentColor,
            downloadViewModel = downloadViewModel,
            activeDownloadList = activeDownloadList,
            pausedDownloadList = pausedDownloadList,
            failedDownloadList = failedDownloadList,
            downloadedSongs = downloadedSongs,
            filteredCompletedSongs = filteredCompletedSongs,
            completedFilter = completedFilter,
            onCompletedFilterChange = { completedFilter = it },
            completedSearchQuery = completedSearchQuery,
            onCompletedSearchQueryChange = { completedSearchQuery = it },
            onPlayMedia = onPlayMedia
        )
        }
    }

    DownloadDialogsHost(
        downloadViewModel = downloadViewModel,
        hybridPlaylistPromptUrl = hybridPlaylistPromptUrl,
        duplicateWarning = duplicateWarning,
        playlistEntries = playlistEntries,
        selectedFormat = selectedFormat,
        accentColor = accentColor,
        onDownloadStarted = { selectedDownloadTab = 1 }
    )
}
