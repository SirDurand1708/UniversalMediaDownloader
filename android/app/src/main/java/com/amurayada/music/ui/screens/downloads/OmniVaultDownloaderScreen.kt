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
            val mimeType = if (song.path.startsWith("content://")) {
                try { context.contentResolver.getType(android.net.Uri.parse(song.path)) } catch (_: Exception) { null }
            } else null
            val isVideo = mimeType?.startsWith("video/") == true ||
                song.album.contains("Video", ignoreCase = true) ||
                song.path.contains("/video/", ignoreCase = true) ||
                song.path.endsWith(".mp4", ignoreCase = true)

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

    val accentColor = Color(0xFF00E5FF)
    val darkOnAccent = Color.Black
    val cardBg = Color(0xFF141923)
    val borderCol = Color(0xFF263248)
    var selectedDownloadTab by remember { mutableIntStateOf(0) } // 0 = Nuevo, 1 = Completadas
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
                            color = Color.White,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    val isYtLoggedIn = remember {
                        try { com.amurayada.music.data.auth.YouTubeAuthManager.getInstance(context).isLoggedIn() } catch (_: Exception) { false }
                    }
                    IconButton(onClick = onOpenYouTubeLogin) {
                        Icon(
                            imageVector = Icons.Rounded.AccountCircle,
                            contentDescription = if (isYtLoggedIn) "YT conectado" else "Sesión YT",
                            tint = if (isYtLoggedIn) accentColor else Color.White.copy(alpha = 0.72f),
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
                        val newLabel = if (activeDownloadList.isNotEmpty()) "Nuevo (${activeDownloadList.size})" else "Nuevo"
                        Surface(
                            onClick = { selectedDownloadTab = 0 },
                            shape = RoundedCornerShape(20.dp),
                            color = if (isNewTab) accentColor else Color.Transparent,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = newLabel,
                                fontSize = 14.sp,
                                fontWeight = if (isNewTab) FontWeight.Bold else FontWeight.Medium,
                                color = if (isNewTab) darkOnAccent else Color.White.copy(alpha = 0.68f),
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
                                color = if (isCompletedTab) darkOnAccent else Color.White.copy(alpha = 0.68f),
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
                        containerColor = Color(0xFF2D2212).copy(alpha = 0.9f)
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
                                color = Color(0xFFFFE082)
                            )
                        }
                        Text(
                            text = "Android puede pausar descargas largas al apagar la pantalla. Permite la ejecución sin restricciones para evitar cortes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.85f)
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
                                color = Color(0xFF9AA4AF),
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
                                        Icon(Icons.Rounded.Clear, contentDescription = "Limpiar", tint = Color(0xFF9AA4AF), modifier = Modifier.size(18.dp))
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
                                    Icon(Icons.Rounded.ContentPaste, contentDescription = "Pegar", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(19.dp))
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(26.dp),
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accentColor,
                            unfocusedBorderColor = borderCol,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
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
                                    tint = Color(0xFF9AA4AF),
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
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2214)),
                        border = BorderStroke(1.dp, Color(0xFFFFB300).copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = analyzeTimeoutMessage ?: "",
                                fontSize = 12.sp,
                                color = Color(0xFFFFE082)
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
                                    Text("Abortar", fontSize = 12.sp, color = Color.LightGray)
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

        // 3. Tarjeta 16:9 de Previsualización + Chips de formato + Botón pill Iniciar descarga
        item {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                AnimatedVisibility(visible = mediaPreview != null) {
                    mediaPreview?.let { preview ->
                        Surface(
                            color = cardBg,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(16f / 9f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color.Black)
                                ) {
                                    AsyncImage(
                                        model = preview.thumbnailUrl,
                                        contentDescription = preview.title,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                    if (preview.durationText.isNotBlank()) {
                                        Surface(
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(8.dp),
                                            color = Color.Black.copy(alpha = 0.78f),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = preview.durationText,
                                                color = Color.White,
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Medium,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = preview.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = preview.author,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF9AA4AF),
                                    fontSize = 13.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(2.dp))
                                val durPart = if (preview.durationText.isNotBlank()) "${preview.durationText} • " else ""
                                Text(
                                    text = "${durPart}${preview.platform.displayName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF9AA4AF),
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // Switch Audio | Video estilo Fusión 3 con paleta de Música
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
                        Surface(
                            onClick = {
                                isAudioFormatTab = true
                                if (!selectedFormat.isAudio) {
                                    downloadViewModel.selectedFormat.value = MediaFormat.M4A
                                }
                            },
                            shape = RoundedCornerShape(20.dp),
                            color = if (isAudioFormatTab) accentColor else Color.Transparent,
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 9.dp, horizontal = 10.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Rounded.MusicNote,
                                    contentDescription = null,
                                    tint = if (isAudioFormatTab) darkOnAccent else Color.White.copy(alpha = 0.72f),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "Audio",
                                    fontSize = 14.sp,
                                    fontWeight = if (isAudioFormatTab) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isAudioFormatTab) darkOnAccent else Color.White.copy(alpha = 0.72f),
                                    maxLines = 1
                                )
                            }
                        }

                        Surface(
                            onClick = {
                                isAudioFormatTab = false
                                if (selectedFormat.isAudio) {
                                    downloadViewModel.selectedFormat.value = MediaFormat.MP4_MAX
                                }
                            },
                            shape = RoundedCornerShape(20.dp),
                            color = if (!isAudioFormatTab) accentColor else Color.Transparent,
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 9.dp, horizontal = 10.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Rounded.Videocam,
                                    contentDescription = null,
                                    tint = if (!isAudioFormatTab) darkOnAccent else Color.White.copy(alpha = 0.72f),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "Video",
                                    fontSize = 14.sp,
                                    fontWeight = if (!isAudioFormatTab) FontWeight.Bold else FontWeight.Medium,
                                    color = if (!isAudioFormatTab) darkOnAccent else Color.White.copy(alpha = 0.72f),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                if (isAudioFormatTab) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FormatChip(
                            label = "MP3 • 320 kbps",
                            selected = selectedFormat == MediaFormat.MP3_320,
                            modifier = Modifier.weight(1f),
                            onClick = { downloadViewModel.selectedFormat.value = MediaFormat.MP3_320 }
                        )
                        FormatChip(
                            label = "M4A • Original HQ",
                            selected = selectedFormat == MediaFormat.M4A,
                            modifier = Modifier.weight(1f),
                            onClick = { downloadViewModel.selectedFormat.value = MediaFormat.M4A }
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FormatChip(
                            label = "Máxima • 4K/HD",
                            selected = selectedFormat == MediaFormat.MP4_MAX,
                            modifier = Modifier.weight(1f),
                            onClick = { downloadViewModel.selectedFormat.value = MediaFormat.MP4_MAX }
                        )
                        FormatChip(
                            label = "MP4 • 1080p",
                            selected = selectedFormat == MediaFormat.MP4_1080,
                            modifier = Modifier.weight(1f),
                            onClick = { downloadViewModel.selectedFormat.value = MediaFormat.MP4_1080 }
                        )
                        FormatChip(
                            label = "MP4 • 720p",
                            selected = selectedFormat == MediaFormat.MP4_720,
                            modifier = Modifier.weight(1f),
                            onClick = { downloadViewModel.selectedFormat.value = MediaFormat.MP4_720 }
                        )
                    }
                }

                val btnLabel = if (batchUrls.size > 1) {
                    "Descargar lote (${batchUrls.size} enlaces)"
                } else {
                    when (selectedFormat) {
                        MediaFormat.MP3_320 -> "Iniciar descarga (MP3 320K)"
                        MediaFormat.M4A -> "Iniciar descarga (M4A HQ)"
                        MediaFormat.MP4_MAX -> "Iniciar descarga (Máxima)"
                        MediaFormat.MP4_1080 -> "Iniciar descarga (MP4 1080p)"
                        MediaFormat.MP4_720 -> "Iniciar descarga (MP4 720p)"
                        MediaFormat.MP4_480 -> "Iniciar descarga (MP4 480p)"
                    }
                }

                Button(
                    onClick = {
                        if (batchUrls.size > 1) {
                            downloadViewModel.enqueueBatch(batchUrls, selectedFormat)
                        } else {
                            val targetUrl = mediaPreview?.originalUrl?.takeIf { it.isNotBlank() } ?: urlInput
                            if (targetUrl.isBlank()) {
                                Toast.makeText(context, "Por favor pega un enlace multimedia", Toast.LENGTH_SHORT).show()
                            } else if (com.omnivault.media.utils.UrlUtils.isDirectPlaylistUrl(targetUrl)) {
                                downloadViewModel.analyzeUrl(targetUrl, forcePlaylist = true)
                            } else {
                                downloadViewModel.startUniversalDownload(targetUrl, selectedFormat)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor = darkOnAccent
                    )
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = btnLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // 4. Controles globales de cola y Descargas Activas / Pausadas / Error
        if (activeDownloadList.isNotEmpty() || pausedDownloadList.isNotEmpty() || failedDownloadList.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (pausedDownloadList.isNotEmpty() || failedDownloadList.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { downloadViewModel.retryFailedAndPaused() },
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.6f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = accentColor)
                        ) {
                            Text("Reanudar / Reintentar todas", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    OutlinedButton(
                        onClick = { downloadViewModel.cancelAllDownloads() },
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF8A80))
                    ) {
                        Text("Cancelar todas", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (activeDownloadList.isNotEmpty()) {
            item {
                Text(
                    text = "DESCARGAS ACTIVAS (${activeDownloadList.size})",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    letterSpacing = 1.sp
                )
            }

            items(activeDownloadList, key = { it.processId }) { state ->
                UniversalActiveDownloadCard(
                    state = state,
                    onCancel = { downloadViewModel.cancelOmniDownload(state.processId) }
                )
            }
        }

        if (pausedDownloadList.isNotEmpty()) {
            item {
                Text(
                    text = "EN PAUSA (${pausedDownloadList.size})",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFFB300),
                    letterSpacing = 1.sp
                )
            }
            items(pausedDownloadList, key = { "paused_${it.processId}" }) { state ->
                Surface(
                    color = Color(0xFF1A1914),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0xFFFFB300).copy(alpha = 0.45f))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = state.title,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = state.errorReason ?: "Pausada • Toca Reanudar",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFFFE082),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(
                            onClick = {
                                downloadViewModel.cancelOmniDownload(state.processId)
                                downloadViewModel.startUniversalDownload(state.url, state.format, forceDuplicate = true)
                            }
                        ) {
                            Text("Reanudar", color = accentColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        if (failedDownloadList.isNotEmpty()) {
            item {
                Text(
                    text = "DESCARGAS CON ERROR (${failedDownloadList.size})",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFF5252),
                    letterSpacing = 1.sp
                )
            }

            items(failedDownloadList, key = { "err_${it.processId}" }) { state ->
                Surface(
                    color = Color(0xFF1A1418),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.45f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = state.title,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = state.errorReason ?: "Error de red o enlace",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFFF8A80),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(
                            onClick = {
                                downloadViewModel.cancelOmniDownload(state.processId)
                                downloadViewModel.startUniversalDownload(state.url, state.format)
                            }
                        ) {
                            Text("Reintentar", color = accentColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        IconButton(
                            onClick = { downloadViewModel.cancelOmniDownload(state.processId) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = "Descartar", tint = Color.Gray, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        } else {
        // 5. Sección de Descargas Completadas (sub-pestaña Completadas)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Historial (${filteredCompletedSongs.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (downloadedSongs.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                downloadViewModel.clearCompletedHistory()
                                Toast.makeText(context, "Historial limpiado (los archivos siguen en tu dispositivo)", Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("Limpiar", fontSize = 11.sp, color = Color.Gray)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("Todos", "Audios", "Videos").forEachIndexed { index, label ->
                        FilterChip(
                            selected = completedFilter == index,
                            onClick = { completedFilter = index },
                            label = {
                                Text(
                                    text = label,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 12.sp
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = accentColor.copy(alpha = 0.2f),
                                selectedLabelColor = accentColor
                            )
                        )
                    }
                }

                OutlinedTextField(
                    value = completedSearchQuery,
                    onValueChange = { completedSearchQuery = it },
                    placeholder = { Text("Buscar en descargas...", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (completedSearchQuery.isNotEmpty()) {
                            IconButton(onClick = { completedSearchQuery = "" }) {
                                Icon(Icons.Rounded.Clear, contentDescription = "Limpiar búsqueda", tint = Color.Gray, modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentColor,
                        unfocusedBorderColor = borderCol
                    )
                )
            }
        }

        if (filteredCompletedSongs.isEmpty()) {
            item {
                Surface(
                    color = cardBg,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, borderCol),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Rounded.LibraryMusic,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (downloadedSongs.isEmpty()) {
                                "Aún no tienes descargas completadas"
                            } else {
                                "No hay descargas que coincidan con el filtro"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        } else {
            items(filteredCompletedSongs, key = { it.id }) { song ->
                val mimeType = remember(song.path) {
                    if (song.path.startsWith("content://")) {
                        try { context.contentResolver.getType(android.net.Uri.parse(song.path)) } catch (_: Exception) { null }
                    } else null
                }
                val isVideoSong = mimeType?.startsWith("video/") == true ||
                    song.album.contains("Video", ignoreCase = true) ||
                    song.path.contains("/video/", ignoreCase = true) ||
                    song.path.endsWith(".mp4", ignoreCase = true)

                CompletedDownloadSongCard(
                    song = song,
                    isVideo = isVideoSong,
                    onPlay = {
                        val format = if (isVideoSong) MediaFormat.MP4_1080 else MediaFormat.M4A
                        val item = DownloadItem(
                            id = song.id.toString(),
                            url = song.path,
                            title = song.title,
                            artist = song.artist,
                            thumbnailUrl = song.albumArtUri?.toString(),
                            format = format,
                            status = DownloadStatus.COMPLETED,
                            progress = 100,
                            localFilePath = song.path
                        )
                        onPlayMedia(item)
                    },
                    onDelete = {
                        downloadViewModel.deleteDownload(song.id)
                        Toast.makeText(context, "Descarga eliminada", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
        }
    }

    // Diálogo URL híbrida (video + playlist)
    hybridPlaylistPromptUrl?.let { hybridUrl ->
        AlertDialog(
            onDismissRequest = { downloadViewModel.hybridPlaylistPromptUrl.value = null },
            title = { Text("Playlist detectada en enlace") },
            text = { Text("Este enlace pertenece a una playlist de YouTube. ¿Qué deseas analizar o descargar?") },
            confirmButton = {
                Button(
                    onClick = { downloadViewModel.analyzeUrl(hybridUrl, forcePlaylist = true) }
                ) {
                    Text("Playlist completa")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { downloadViewModel.analyzeUrl(hybridUrl, forceSingleVideo = true) }
                ) {
                    Text("Solo este video")
                }
            }
        )
    }

    // Diálogo de confirmación de duplicado
    duplicateWarning?.let { (dupUrl, dupFormat) ->
        AlertDialog(
            onDismissRequest = { downloadViewModel.duplicateWarning.value = null },
            title = { Text("Ya descargado") },
            text = { Text("Ya tienes este enlace descargado en formato ${if (dupFormat.isAudio) "Audio" else "Video"}. ¿Deseas descargar una copia adicional?") },
            confirmButton = {
                Button(
                    onClick = {
                        downloadViewModel.duplicateWarning.value = null
                        downloadViewModel.startUniversalDownload(dupUrl, dupFormat, forceDuplicate = true)
                    }
                ) {
                    Text("Descargar copia")
                }
            },
            dismissButton = {
                TextButton(onClick = { downloadViewModel.duplicateWarning.value = null }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Diálogo de selección de ítems de Playlist
    if (playlistEntries.isNotEmpty()) {
        val selectedIndices = remember(playlistEntries) {
            mutableStateListOf<Int>().apply { addAll(playlistEntries.indices) }
        }
        AlertDialog(
            onDismissRequest = { downloadViewModel.dismissPlaylistSheet() },
            title = {
                Text(
                    text = "Playlist (${playlistEntries.size} elementos)",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${selectedIndices.size} seleccionados",
                            style = MaterialTheme.typography.labelMedium,
                            color = accentColor
                        )
                        TextButton(
                            onClick = {
                                if (selectedIndices.size == playlistEntries.size) {
                                    selectedIndices.clear()
                                } else {
                                    selectedIndices.clear()
                                    selectedIndices.addAll(playlistEntries.indices)
                                }
                            }
                        ) {
                            Text(if (selectedIndices.size == playlistEntries.size) "Ninguno" else "Todos")
                        }
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(playlistEntries.size) { idx ->
                            val entry = playlistEntries[idx]
                            val checked = idx in selectedIndices
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        if (checked) selectedIndices.remove(idx) else selectedIndices.add(idx)
                                    }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.title,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (entry.durationText.isNotBlank()) {
                                        Text(
                                            text = entry.durationText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.Gray
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = selectedIndices.isNotEmpty(),
                    onClick = {
                        val urls = selectedIndices.sorted().map { playlistEntries[it].url }
                        downloadViewModel.enqueueBatch(urls, selectedFormat)
                    }
                ) {
                    Text("Descargar (${selectedIndices.size})")
                }
            },
            dismissButton = {
                TextButton(onClick = { downloadViewModel.dismissPlaylistSheet() }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@Composable
fun FormatChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val selectedBg = Color(0xFF00E5FF)
    val selectedText = Color.Black
    Surface(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(20.dp),
        color = if (selected) selectedBg else Color(0xFF141923),
        border = if (selected) null else BorderStroke(1.dp, Color(0xFF263248))
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) selectedText else Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
                if (selected) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint = selectedText,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun UniversalActiveDownloadCard(
    state: UniversalDownloadState,
    onCancel: () -> Unit
) {
    val accentColor = Color(0xFF00E5FF)
    val percent = (state.progress.coerceIn(0f, 1f) * 100f).toInt()

    Surface(
        color = Color(0xFF141923),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color(0xFF263248))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (state.artist.isNotBlank()) {
                        Text(
                            text = state.artist,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Cancelar descarga",
                        tint = Color.Gray,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { state.progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = accentColor,
                trackColor = Color(0xFF263248)
            )

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val etaSuffix = if (state.etaInSeconds > 0L) " • ~${state.etaInSeconds}s" else ""
                Text(
                    text = "$percent% • ${state.speedText}$etaSuffix",
                    style = MaterialTheme.typography.labelSmall,
                    color = accentColor
                )
                Text(
                    text = state.format.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
        }
    }

}

@Composable
fun CompletedDownloadSongCard(
    song: Song,
    isVideo: Boolean = song.album.contains("Video", ignoreCase = true) ||
        song.path.contains("/video/", ignoreCase = true) ||
        song.path.endsWith(".mp4", ignoreCase = true),
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    val accentColor = Color(0xFF00E5FF)
    var menuExpanded by remember { mutableStateOf(false) }
    val shareContext = LocalContext.current

    Surface(
        onClick = onPlay,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (song.albumArtUri != null) {
                    AsyncImage(
                        model = song.albumArtUri,
                        contentDescription = song.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = if (isVideo) Icons.Rounded.Movie else Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 13.5.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "${song.artist} • ${if (isVideo) "Video MP4" else "Audio HQ"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.62f),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 11.sp
                )
            }

            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = "Opciones",
                        tint = Color.White.copy(alpha = 0.72f),
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
                            onPlay()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Compartir") },
                        leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            try {
                                val shareUri = if (song.path.startsWith("content://")) {
                                    android.net.Uri.parse(song.path)
                                } else {
                                    androidx.core.content.FileProvider.getUriForFile(
                                        shareContext,
                                        "${shareContext.packageName}.fileprovider",
                                        java.io.File(song.path)
                                    )
                                }
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = if (isVideo) "video/*" else "audio/*"
                                    putExtra(Intent.EXTRA_STREAM, shareUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                shareContext.startActivity(Intent.createChooser(shareIntent, "Compartir ${song.title}"))
                            } catch (_: Exception) {
                                Toast.makeText(shareContext, "No se pudo compartir este archivo", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Eliminar", color = Color(0xFFFF5252)) },
                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = Color(0xFFFF5252)) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}
