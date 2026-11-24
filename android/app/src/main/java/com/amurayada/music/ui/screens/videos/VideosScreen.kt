package com.amurayada.music.ui.screens.videos

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.amurayada.music.ui.viewmodel.VideoPlaylist
import com.amurayada.music.ui.viewmodel.VideosViewModel
import com.omnivault.media.model.DownloadItem
import com.omnivault.media.model.SortOption
import com.omnivault.media.utils.BulkMediaStandardizer
import com.omnivault.media.utils.StandardizeCandidate
import kotlinx.coroutines.launch
import java.io.File

import com.amurayada.music.ui.theme.LocalAuraPalette

private val VideoNeonCyan @Composable get() = LocalAuraPalette.current.accent
private val VideoNeonGreen @Composable get() = LocalAuraPalette.current.accentGreen
private val VideoTitaniumCard @Composable get() = LocalAuraPalette.current.cardBg
private val VideoBorderSlate @Composable get() = LocalAuraPalette.current.border
private val VideoTextSecondary @Composable get() = LocalAuraPalette.current.textSecondary
private val VideoTextPrimary @Composable get() = LocalAuraPalette.current.textPrimary
private val VideoOnAccent @Composable get() = LocalAuraPalette.current.onAccent
private val VideoDangerRed = Color(0xFFFF5252)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideosScreen(
    videosViewModel: VideosViewModel,
    onVideoClick: (DownloadItem) -> Unit
) {
    val context = LocalContext.current
    val videos by videosViewModel.videos.collectAsState()
    val rawVideos by videosViewModel.rawVideos.collectAsState()
    val videoPlaylists by videosViewModel.videoPlaylists.collectAsState()
    val isLoading by videosViewModel.isLoading.collectAsState()
    val searchQuery by videosViewModel.searchQuery.collectAsState()
    val currentSort by videosViewModel.sortOption.collectAsState()
    val hideShortClips by videosViewModel.hideShortClips.collectAsState()
    val hideWhatsAppClips by videosViewModel.hideWhatsAppClips.collectAsState()
    val onlyDownloaded by videosViewModel.onlyDownloaded.collectAsState()
    val onlyCamera by videosViewModel.onlyCamera.collectAsState()

    val prefs = remember { context.getSharedPreferences("omnivault_prefs", Context.MODE_PRIVATE) }
    var isGridView by remember { mutableStateOf(prefs.getBoolean("video_grid_view", false)) }
    var showSearchBar by remember { mutableStateOf(false) }
    var showHeaderMenu by remember { mutableStateOf(false) }
    val selectedVideoIds = remember { mutableStateListOf<String>() }
    var pendingBatchDeleteVideos by remember { mutableStateOf<List<DownloadItem>>(emptyList()) }

    var selectedSection by remember { mutableIntStateOf(0) } // 0 = Todos, 1 = Playlists
    var openedPlaylistId by remember { mutableStateOf<String?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var pendingPlaylistInitialVideos by remember { mutableStateOf<List<String>>(emptyList()) }
    var videoIdsPendingPlaylist by remember { mutableStateOf<List<String>?>(null) }
    var renameTargetPlaylist by remember { mutableStateOf<VideoPlaylist?>(null) }
    var deleteTargetPlaylist by remember { mutableStateOf<VideoPlaylist?>(null) }
    val openedPlaylist = remember(videoPlaylists, openedPlaylistId) {
        videoPlaylists.find { it.id == openedPlaylistId }
    }

    val deleteBatchLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && pendingBatchDeleteVideos.isNotEmpty()) {
            val count = pendingBatchDeleteVideos.size
            videosViewModel.confirmVideosBatchDeleted(pendingBatchDeleteVideos)
            Toast.makeText(context, "$count videos eliminados", Toast.LENGTH_SHORT).show()
        }
        pendingBatchDeleteVideos = emptyList()
    }

    BackHandler(enabled = selectedVideoIds.isNotEmpty()) {
        selectedVideoIds.clear()
    }
    BackHandler(enabled = selectedVideoIds.isEmpty() && selectedSection == 1 && openedPlaylist != null) {
        openedPlaylistId = null
    }
    BackHandler(enabled = selectedVideoIds.isEmpty() && selectedSection == 1 && openedPlaylist == null) {
        selectedSection = 0
    }

    var showSortDialog by remember { mutableStateOf(false) }

    var pendingDeleteVideo by remember { mutableStateOf<DownloadItem?>(null) }

    val scope = rememberCoroutineScope()
    var isScanningStandardize by remember { mutableStateOf(false) }
    var videoStandardizeCandidates by remember { mutableStateOf<List<StandardizeCandidate>?>(null) }
    var pendingWriteChunks by remember { mutableStateOf<List<List<StandardizeCandidate>>>(emptyList()) }
    var totalVideosRenamed by remember { mutableIntStateOf(0) }
    var launchNextVideoChunk: ((List<List<StandardizeCandidate>>) -> Unit)? = null

    val writePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val currentChunk = pendingWriteChunks.firstOrNull()
        val remaining = if (pendingWriteChunks.size > 1) pendingWriteChunks.drop(1) else emptyList()
        if (result.resultCode == Activity.RESULT_OK && currentChunk != null) {
            scope.launch {
                val res = BulkMediaStandardizer.applyBatch(context, currentChunk, collectPermissionChunks = false)
                totalVideosRenamed += res.renamedCount
                if (remaining.isNotEmpty()) {
                    launchNextVideoChunk?.invoke(remaining)
                } else {
                    pendingWriteChunks = emptyList()
                    videosViewModel.loadVideos()
                    Toast.makeText(context, "$totalVideosRenamed videos estandarizados", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            pendingWriteChunks = emptyList()
            if (totalVideosRenamed > 0) {
                videosViewModel.loadVideos()
                Toast.makeText(context, "$totalVideosRenamed videos estandarizados", Toast.LENGTH_SHORT).show()
            }
        }
    }

    launchNextVideoChunk = { chunks ->
        val first = chunks.firstOrNull()
        val sender = if (first != null) BulkMediaStandardizer.createWriteRequestIntentSender(context, first) else null
        if (sender != null) {
            pendingWriteChunks = chunks
            writePermissionLauncher.launch(IntentSenderRequest.Builder(sender).build())
        } else {
            pendingWriteChunks = emptyList()
            if (totalVideosRenamed > 0) {
                videosViewModel.loadVideos()
                Toast.makeText(context, "$totalVideosRenamed videos estandarizados", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val deleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val target = pendingDeleteVideo
        pendingDeleteVideo = null
        if (result.resultCode == Activity.RESULT_OK) {
            if (target != null) {
                videosViewModel.confirmVideoDeleted(target)
            } else {
                videosViewModel.loadVideos()
            }
            Toast.makeText(context, "Video eliminado", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        videosViewModel.loadVideos()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // Top Header (limpio, sin subtítulo truncado ni botones amontonados)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(VideoNeonGreen, VideoNeonCyan, Color(0xFF7C4DFF))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VideoLibrary,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(21.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "VIDEOS",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = VideoTextPrimary,
                    maxLines = 1,
                    softWrap = false
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    color = VideoNeonCyan.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = if (selectedSection == 0) "${videos.size}" else "${videoPlaylists.size}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = VideoNeonCyan,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (selectedSection == 0) {
                    IconButton(onClick = {
                        if (showSearchBar && searchQuery.isNotEmpty()) {
                            videosViewModel.searchQuery.value = ""
                        }
                        showSearchBar = !showSearchBar
                    }) {
                        Icon(
                            imageVector = if (showSearchBar || searchQuery.isNotEmpty()) Icons.Rounded.SearchOff else Icons.Rounded.Search,
                            contentDescription = "Buscar videos",
                            tint = if (showSearchBar || searchQuery.isNotEmpty()) VideoNeonCyan else VideoTextPrimary
                        )
                    }
                }
                IconButton(onClick = {
                    val next = !isGridView
                    isGridView = next
                    prefs.edit().putBoolean("video_grid_view", next).apply()
                }) {
                    Icon(
                        imageVector = if (isGridView) Icons.Rounded.ViewList else Icons.Rounded.GridView,
                        contentDescription = if (isGridView) "Vista en lista" else "Vista en cuadrícula",
                        tint = VideoTextPrimary
                    )
                }
                Box {
                    IconButton(onClick = { showHeaderMenu = true }) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "Más opciones",
                            tint = VideoTextPrimary
                        )
                    }
                    DropdownMenu(
                        expanded = showHeaderMenu,
                        onDismissRequest = { showHeaderMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Ordenar videos") },
                            leadingIcon = { Icon(Icons.Rounded.Sort, contentDescription = null) },
                            onClick = {
                                showHeaderMenu = false
                                showSortDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Estandarizar nombres") },
                            leadingIcon = { Icon(Icons.Rounded.AutoFixHigh, contentDescription = null) },
                            onClick = {
                                showHeaderMenu = false
                                if (!isScanningStandardize) {
                                    isScanningStandardize = true
                                    scope.launch {
                                        val found = BulkMediaStandardizer.scanCandidates(
                                            context = context,
                                            includeAudio = false,
                                            includeVideo = true
                                        )
                                        isScanningStandardize = false
                                        if (found.isEmpty()) {
                                            Toast.makeText(context, "¡Tus videos ya están estandarizados!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            videoStandardizeCandidates = found
                                        }
                                    }
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Recargar biblioteca") },
                            leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                            onClick = {
                                showHeaderMenu = false
                                videosViewModel.loadVideos()
                            }
                        )
                    }
                }
            }
        }

        // Top Segment Switcher: Todos los videos | Playlists
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(VideoTitaniumCard)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val allSelected = selectedSection == 0
            Surface(
                onClick = {
                    selectedSection = 0
                    openedPlaylistId = null
                },
                shape = RoundedCornerShape(10.dp),
                color = if (allSelected) VideoNeonCyan else Color.Transparent,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "Todos los videos",
                    fontSize = 13.sp,
                    fontWeight = if (allSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (allSelected) VideoOnAccent else VideoTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            val playlistsSelected = selectedSection == 1
            Surface(
                onClick = {
                    selectedSection = 1
                    selectedVideoIds.clear()
                },
                shape = RoundedCornerShape(10.dp),
                color = if (playlistsSelected) VideoNeonCyan else Color.Transparent,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "Playlists (${videoPlaylists.size})",
                    fontSize = 13.sp,
                    fontWeight = if (playlistsSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (playlistsSelected) VideoOnAccent else VideoTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }

        if (selectedSection == 0) {
        // Buscador colapsable para dar mayor espacio a los videos
        AnimatedVisibility(visible = showSearchBar || searchQuery.isNotEmpty()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { videosViewModel.searchQuery.value = it },
                placeholder = { Text("Buscar videos por título...", color = VideoTextSecondary) },
                leadingIcon = {
                    Icon(Icons.Rounded.Search, contentDescription = null, tint = VideoNeonCyan)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { videosViewModel.searchQuery.value = "" }) {
                            Icon(Icons.Rounded.Clear, contentDescription = "Limpiar", tint = VideoTextSecondary)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = VideoNeonCyan,
                    unfocusedBorderColor = VideoBorderSlate,
                    focusedContainerColor = VideoTitaniumCard,
                    unfocusedContainerColor = VideoTitaniumCard,
                    focusedTextColor = VideoTextPrimary,
                    unfocusedTextColor = VideoTextPrimary,
                    cursorColor = VideoNeonCyan
                )
            )
        }

        // Filtros rápidos con padding limpio y etiquetas claras (sin cortes en el borde)
        val filterChipColors = FilterChipDefaults.filterChipColors(
            containerColor = VideoTitaniumCard,
            labelColor = VideoTextSecondary,
            selectedContainerColor = VideoNeonCyan.copy(alpha = 0.2f),
            selectedLabelColor = VideoNeonCyan
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                FilterChip(
                    selected = !onlyDownloaded && !onlyCamera,
                    onClick = {
                        videosViewModel.setOnlyDownloaded(false)
                        videosViewModel.setOnlyCamera(false)
                    },
                    label = { Text("Todos", fontSize = 12.sp, maxLines = 1, softWrap = false) },
                    colors = filterChipColors
                )
            }
            item {
                FilterChip(
                    selected = onlyDownloaded,
                    onClick = { videosViewModel.setOnlyDownloaded(true) },
                    label = { Text("Descargados", fontSize = 12.sp, maxLines = 1, softWrap = false) },
                    colors = filterChipColors
                )
            }
            item {
                FilterChip(
                    selected = onlyCamera,
                    onClick = { videosViewModel.setOnlyCamera(!onlyCamera) },
                    label = { Text("Cámara", fontSize = 12.sp, maxLines = 1, softWrap = false) },
                    colors = filterChipColors
                )
            }
            item {
                FilterChip(
                    selected = hideWhatsAppClips,
                    onClick = { videosViewModel.setHideWhatsAppClips(!hideWhatsAppClips) },
                    label = { Text("Sin WhatsApp", fontSize = 12.sp, maxLines = 1, softWrap = false) },
                    colors = filterChipColors
                )
            }
        }

        if (selectedVideoIds.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Surface(
                color = VideoTitaniumCard,
                border = BorderStroke(1.dp, VideoNeonCyan.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { selectedVideoIds.clear() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "Cancelar selección", tint = VideoTextPrimary, modifier = Modifier.size(18.dp))
                    }
                    Text(
                        text = "${selectedVideoIds.size} seleccionados",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = VideoNeonCyan,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            videoIdsPendingPlaylist = selectedVideoIds.toList()
                            selectedVideoIds.clear()
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Rounded.PlaylistAdd, contentDescription = "Añadir a playlist", tint = VideoNeonCyan)
                    }
                    IconButton(
                        onClick = {
                            val batch = videos.filter { selectedVideoIds.contains(it.id) }
                            selectedVideoIds.clear()
                            pendingBatchDeleteVideos = batch
                            videosViewModel.deleteVideosBatch(
                                items = batch,
                                onRequirePermission = { req -> deleteBatchLauncher.launch(req) },
                                onComplete = { count ->
                                    pendingBatchDeleteVideos = emptyList()
                                    Toast.makeText(context, "$count videos eliminados", Toast.LENGTH_SHORT).show()
                                }
                            )
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Eliminar seleccionados", tint = VideoDangerRed)
                    }
                    IconButton(
                        onClick = {
                            if (selectedVideoIds.size == videos.size) {
                                selectedVideoIds.clear()
                            } else {
                                selectedVideoIds.clear()
                                selectedVideoIds.addAll(videos.map { it.id })
                            }
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Rounded.SelectAll, contentDescription = "Seleccionar todo", tint = VideoNeonCyan)
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Content
        if (isLoading && videos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = VideoNeonCyan)
            }
        } else if (videos.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.VideoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = VideoTextSecondary.copy(alpha = 0.45f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = if (searchQuery.isNotEmpty()) "No se encontraron videos" else "No hay videos registrados",
                        style = MaterialTheme.typography.titleMedium,
                        color = VideoTextSecondary
                    )
                }
            }
        } else {
            VideoMediaCollection(
                items = videos,
                isGridView = isGridView,
                selectedVideoIds = selectedVideoIds,
                onVideoClick = { video ->
                    videosViewModel.setPlaybackQueue(emptyList())
                    onVideoClick(video)
                },
                onStandardize = { video ->
                    videosViewModel.standardizeVideo(video) { success ->
                        Toast.makeText(
                            context,
                            if (success) "Nombre estandarizado con éxito" else "No se pudo renombrar el archivo",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                onAddToPlaylist = { video ->
                    videoIdsPendingPlaylist = listOf(video.id)
                },
                onShare = { video ->
                    val path = video.localFilePath ?: return@VideoMediaCollection
                    try {
                        val uri = if (path.startsWith("content://")) {
                            Uri.parse(path)
                        } else {
                            val file = File(path)
                            if (!file.exists()) {
                                Toast.makeText(context, "Archivo de video no encontrado", Toast.LENGTH_SHORT).show()
                                return@VideoMediaCollection
                            }
                            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        }
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "video/*"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Compartir video"))
                    } catch (e: Exception) {
                        Toast.makeText(context, "Error al compartir: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                },
                onDelete = { video ->
                    videosViewModel.deleteVideo(
                        item = video,
                        onRequirePermission = { req ->
                            pendingDeleteVideo = video
                            deleteLauncher.launch(req)
                        },
                        onComplete = { success ->
                            if (success) {
                                Toast.makeText(context, "Video eliminado", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "No se pudo eliminar el video", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            )
        }
        } else {
            // Playlists virtuales de video (subcarpetas virtuales)
            val videoMap = remember(rawVideos) { rawVideos.associateBy { it.id } }
            if (openedPlaylist == null) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Surface(
                            onClick = {
                                pendingPlaylistInitialVideos = emptyList()
                                showCreatePlaylistDialog = true
                            },
                            color = VideoTitaniumCard,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, VideoNeonCyan.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(VideoNeonCyan.copy(alpha = 0.16f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Rounded.CreateNewFolder, contentDescription = null, tint = VideoNeonCyan)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("Nueva playlist de videos", fontWeight = FontWeight.Bold, color = VideoTextPrimary)
                                    Text("Organiza tus videos en carpetas virtuales", style = MaterialTheme.typography.bodySmall, color = VideoTextSecondary)
                                }
                            }
                        }
                    }

                    if (videoPlaylists.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Rounded.FolderOpen,
                                        contentDescription = null,
                                        modifier = Modifier.size(56.dp),
                                        tint = VideoTextSecondary.copy(alpha = 0.45f)
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        "No tienes playlists de videos todavía",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = VideoTextSecondary
                                    )
                                }
                            }
                        }
                    }

                    lazyItems(videoPlaylists, key = { it.id }) { pl ->
                        val plVideos = pl.videoIds.mapNotNull { videoMap[it] }
                        val thumb = plVideos.firstOrNull()?.let { it.thumbnailUrl ?: it.localFilePath ?: it.url }
                        var plMenuExpanded by remember { mutableStateOf(false) }

                        Surface(
                            onClick = { openedPlaylistId = pl.id },
                            color = VideoTitaniumCard,
                            border = BorderStroke(1.dp, VideoBorderSlate),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (thumb != null) {
                                    AsyncImage(
                                        model = thumb,
                                        contentDescription = pl.name,
                                        modifier = Modifier
                                            .size(52.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color.Black),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(52.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(VideoNeonCyan.copy(alpha = 0.16f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Rounded.Folder, contentDescription = null, tint = VideoNeonCyan)
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(pl.name, fontWeight = FontWeight.SemiBold, color = VideoTextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${plVideos.size} ${if (plVideos.size == 1) "video" else "videos"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = VideoTextSecondary
                                    )
                                }
                                Box {
                                    IconButton(onClick = { plMenuExpanded = true }) {
                                        Icon(Icons.Rounded.MoreVert, contentDescription = "Opciones", tint = VideoTextSecondary)
                                    }
                                    DropdownMenu(
                                        expanded = plMenuExpanded,
                                        onDismissRequest = { plMenuExpanded = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Renombrar") },
                                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                            onClick = {
                                                plMenuExpanded = false
                                                renameTargetPlaylist = pl
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Eliminar", color = Color(0xFFFF5252)) },
                                            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = Color(0xFFFF5252)) },
                                            onClick = {
                                                plMenuExpanded = false
                                                deleteTargetPlaylist = pl
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                val plVideos = openedPlaylist.videoIds.mapNotNull { videoMap[it] }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { openedPlaylistId = null }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Volver a playlists", tint = VideoTextPrimary)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(openedPlaylist.name, fontWeight = FontWeight.Bold, color = VideoTextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${plVideos.size} ${if (plVideos.size == 1) "video" else "videos"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = VideoTextSecondary
                        )
                    }
                    IconButton(onClick = { renameTargetPlaylist = openedPlaylist }) {
                        Icon(Icons.Rounded.Edit, contentDescription = "Renombrar playlist", tint = VideoTextPrimary)
                    }
                    if (plVideos.isNotEmpty()) {
                        FilledTonalButton(
                            onClick = {
                                videosViewModel.setPlaybackQueue(plVideos)
                                onVideoClick(plVideos.first())
                            },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = VideoNeonCyan,
                                contentColor = VideoOnAccent
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Reproducir", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }
                if (plVideos.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Rounded.VideoLibrary,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = VideoTextSecondary.copy(alpha = 0.45f)
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Esta playlist está vacía.\nAñade videos desde el menú (⋮) o selección múltiple.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = VideoTextSecondary,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                } else {
                    VideoMediaCollection(
                        items = plVideos,
                        isGridView = isGridView,
                        selectedVideoIds = null,
                        allowSelection = false,
                        onVideoClick = { video ->
                            videosViewModel.setPlaybackQueue(plVideos)
                            onVideoClick(video)
                        },
                        onStandardize = { video ->
                            videosViewModel.standardizeVideo(video) { success ->
                                Toast.makeText(
                                    context,
                                    if (success) "Nombre estandarizado con éxito" else "No se pudo renombrar el archivo",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onAddToPlaylist = { video ->
                            videoIdsPendingPlaylist = listOf(video.id)
                        },
                        onRemoveFromPlaylist = { video ->
                            videosViewModel.removeVideoFromPlaylist(openedPlaylist.id, video.id)
                            Toast.makeText(context, "Quitado de ${openedPlaylist.name}", Toast.LENGTH_SHORT).show()
                        },
                        onShare = { video ->
                            val path = video.localFilePath ?: return@VideoMediaCollection
                            try {
                                val uri = if (path.startsWith("content://")) {
                                    Uri.parse(path)
                                } else {
                                    val file = File(path)
                                    if (!file.exists()) {
                                        Toast.makeText(context, "Archivo de video no encontrado", Toast.LENGTH_SHORT).show()
                                        return@VideoMediaCollection
                                    }
                                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                }
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "video/*"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Compartir video"))
                            } catch (e: Exception) {
                                Toast.makeText(context, "Error al compartir: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDelete = { video ->
                            videosViewModel.deleteVideo(
                                item = video,
                                onRequirePermission = { req ->
                                    pendingDeleteVideo = video
                                    deleteLauncher.launch(req)
                                },
                                onComplete = { success ->
                                    if (success) {
                                        Toast.makeText(context, "Video eliminado", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "No se pudo eliminar el video", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                    )
                }
            }
        }
    }

    // Sort Dialog
    if (showSortDialog) {
        AlertDialog(
            onDismissRequest = { showSortDialog = false },
            containerColor = VideoTitaniumCard,
            titleContentColor = VideoTextPrimary,
            textContentColor = VideoTextPrimary,
            title = { Text("Ordenar videos por", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    SortOptionRow("Más reciente (Fecha)", currentSort == SortOption.DATE_DESC) {
                        videosViewModel.setSortOption(SortOption.DATE_DESC)
                        showSortDialog = false
                    }
                    SortOptionRow("Más antiguo", currentSort == SortOption.DATE_ASC) {
                        videosViewModel.setSortOption(SortOption.DATE_ASC)
                        showSortDialog = false
                    }
                    SortOptionRow("Nombre: A a Z", currentSort == SortOption.NAME_ASC) {
                        videosViewModel.setSortOption(SortOption.NAME_ASC)
                        showSortDialog = false
                    }
                    SortOptionRow("Nombre: Z a A", currentSort == SortOption.NAME_DESC) {
                        videosViewModel.setSortOption(SortOption.NAME_DESC)
                        showSortDialog = false
                    }
                    SortOptionRow("Tamaño: Mayor a menor", currentSort == SortOption.SIZE_DESC) {
                        videosViewModel.setSortOption(SortOption.SIZE_DESC)
                        showSortDialog = false
                    }
                    SortOptionRow("Tamaño: Menor a mayor", currentSort == SortOption.SIZE_ASC) {
                        videosViewModel.setSortOption(SortOption.SIZE_ASC)
                        showSortDialog = false
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showSortDialog = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = VideoNeonCyan)
                ) {
                    Text("Cerrar")
                }
            }
        )
    }

    videoStandardizeCandidates?.let { candidates ->
        val examples = candidates.take(3)
        AlertDialog(
            onDismissRequest = { videoStandardizeCandidates = null },
            containerColor = VideoTitaniumCard,
            titleContentColor = VideoTextPrimary,
            textContentColor = VideoTextPrimary,
            icon = { Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, tint = VideoNeonCyan) },
            title = { Text("Estandarizar videos", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Se encontraron ${candidates.size} videos para limpiar etiquetas (se omiten WhatsApp y cámara).",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    examples.forEach { ex ->
                        Text(
                            text = "• ${ex.currentBaseName} → ${ex.targetBaseName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = VideoTextSecondary,
                            maxLines = 2
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val batch = candidates
                        videoStandardizeCandidates = null
                        totalVideosRenamed = 0
                        scope.launch {
                            val result = BulkMediaStandardizer.applyBatch(context, batch)
                            totalVideosRenamed += result.renamedCount
                            if (result.pendingPermissionChunks.isNotEmpty()) {
                                launchNextVideoChunk?.invoke(result.pendingPermissionChunks)
                            } else {
                                videosViewModel.loadVideos()
                                Toast.makeText(context, "$totalVideosRenamed videos estandarizados", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VideoNeonCyan, contentColor = VideoOnAccent)
                ) {
                    Text("Estandarizar (${candidates.size})", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { videoStandardizeCandidates = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = VideoTextSecondary)
                ) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Picker: Añadir video(s) a playlist virtual
    videoIdsPendingPlaylist?.let { pendingIds ->
        val validRawIds = remember(rawVideos) { rawVideos.mapTo(HashSet()) { it.id } }
        AlertDialog(
            onDismissRequest = { videoIdsPendingPlaylist = null },
            containerColor = VideoTitaniumCard,
            titleContentColor = VideoTextPrimary,
            textContentColor = VideoTextPrimary,
            title = { Text("Añadir a playlist", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            pendingPlaylistInitialVideos = pendingIds
                            videoIdsPendingPlaylist = null
                            showCreatePlaylistDialog = true
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = VideoNeonCyan),
                        border = BorderStroke(1.dp, VideoNeonCyan.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.CreateNewFolder, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Crear nueva playlist")
                    }
                    if (videoPlaylists.isEmpty()) {
                        Text(
                            "Aún no tienes playlists creadas.",
                            style = MaterialTheme.typography.bodySmall,
                            color = VideoTextSecondary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            videoPlaylists.forEach { pl ->
                                val validCount = pl.videoIds.count { it in validRawIds }
                                Surface(
                                    onClick = {
                                        videosViewModel.addVideosToPlaylist(pl.id, pendingIds)
                                        videoIdsPendingPlaylist = null
                                        Toast.makeText(context, "Añadido a ${pl.name}", Toast.LENGTH_SHORT).show()
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    color = VideoTitaniumCard,
                                    border = BorderStroke(1.dp, VideoBorderSlate),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Rounded.Folder, contentDescription = null, tint = VideoNeonCyan)
                                        Spacer(Modifier.width(10.dp))
                                        Text(pl.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium, color = VideoTextPrimary)
                                        Text("$validCount", style = MaterialTheme.typography.labelMedium, color = VideoTextSecondary)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { videoIdsPendingPlaylist = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = VideoNeonCyan)
                ) {
                    Text("Cerrar")
                }
            }
        )
    }

    if (showCreatePlaylistDialog) {
        var newName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = {
                showCreatePlaylistDialog = false
                pendingPlaylistInitialVideos = emptyList()
            },
            containerColor = VideoTitaniumCard,
            titleContentColor = VideoTextPrimary,
            textContentColor = VideoTextPrimary,
            title = { Text("Nueva playlist de videos", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Nombre de la carpeta/playlist") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VideoNeonCyan,
                        unfocusedBorderColor = VideoBorderSlate,
                        focusedLabelColor = VideoNeonCyan,
                        unfocusedLabelColor = VideoTextSecondary,
                        focusedTextColor = VideoTextPrimary,
                        unfocusedTextColor = VideoTextPrimary,
                        cursorColor = VideoNeonCyan
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val created = videosViewModel.createVideoPlaylist(newName, pendingPlaylistInitialVideos)
                        if (created != null) {
                            Toast.makeText(context, "Playlist \"${created.name}\" creada", Toast.LENGTH_SHORT).show()
                        }
                        showCreatePlaylistDialog = false
                        pendingPlaylistInitialVideos = emptyList()
                    },
                    enabled = newName.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = VideoNeonCyan, contentColor = VideoOnAccent)
                ) {
                    Text("Crear", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showCreatePlaylistDialog = false
                        pendingPlaylistInitialVideos = emptyList()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = VideoTextSecondary)
                ) {
                    Text("Cancelar")
                }
            }
        )
    }

    renameTargetPlaylist?.let { target ->
        var updatedName by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTargetPlaylist = null },
            containerColor = VideoTitaniumCard,
            titleContentColor = VideoTextPrimary,
            textContentColor = VideoTextPrimary,
            title = { Text("Renombrar playlist", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = updatedName,
                    onValueChange = { updatedName = it },
                    label = { Text("Nuevo nombre") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VideoNeonCyan,
                        unfocusedBorderColor = VideoBorderSlate,
                        focusedLabelColor = VideoNeonCyan,
                        unfocusedLabelColor = VideoTextSecondary,
                        focusedTextColor = VideoTextPrimary,
                        unfocusedTextColor = VideoTextPrimary,
                        cursorColor = VideoNeonCyan
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        videosViewModel.renameVideoPlaylist(target.id, updatedName)
                        renameTargetPlaylist = null
                    },
                    enabled = updatedName.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = VideoNeonCyan, contentColor = VideoOnAccent)
                ) {
                    Text("Guardar", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { renameTargetPlaylist = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = VideoTextSecondary)
                ) {
                    Text("Cancelar")
                }
            }
        )
    }

    deleteTargetPlaylist?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTargetPlaylist = null },
            containerColor = VideoTitaniumCard,
            titleContentColor = VideoTextPrimary,
            textContentColor = VideoTextSecondary,
            title = { Text("Eliminar playlist", fontWeight = FontWeight.Bold) },
            text = { Text("¿Eliminar \"${target.name}\"? Solo se eliminará la lista virtual; tus videos seguirán en tu dispositivo.") },
            confirmButton = {
                Button(
                    onClick = {
                        if (openedPlaylistId == target.id) openedPlaylistId = null
                        videosViewModel.deleteVideoPlaylist(target.id)
                        deleteTargetPlaylist = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VideoDangerRed, contentColor = Color.White)
                ) {
                    Text("Eliminar")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleteTargetPlaylist = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = VideoTextSecondary)
                ) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@Composable
private fun SortOptionRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(
                selectedColor = VideoNeonCyan,
                unselectedColor = VideoTextSecondary
            )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) VideoNeonCyan else VideoTextPrimary
        )
    }
}

private fun videoOriginLabel(video: DownloadItem): String {
    val path = video.localFilePath ?: ""
    return when {
        video.id.startsWith("db_video_") || path.contains("/OmniVault", ignoreCase = true) -> "Descargado"
        path.contains("/DCIM/Camera", ignoreCase = true) || path.contains("/Camera/", ignoreCase = true) -> "Cámara"
        path.contains("WhatsApp", ignoreCase = true) -> "WhatsApp"
        else -> "Local"
    }
}

@Composable
private fun VideoMediaCollection(
    items: List<DownloadItem>,
    isGridView: Boolean,
    selectedVideoIds: MutableList<String>? = null,
    allowSelection: Boolean = true,
    onVideoClick: (DownloadItem) -> Unit,
    onStandardize: (DownloadItem) -> Unit,
    onAddToPlaylist: (DownloadItem) -> Unit,
    onRemoveFromPlaylist: ((DownloadItem) -> Unit)? = null,
    onShare: (DownloadItem) -> Unit,
    onDelete: (DownloadItem) -> Unit
) {
    val inSelectionMode = allowSelection && !selectedVideoIds.isNullOrEmpty()
    if (isGridView) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp, top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(items, key = { it.id }) { video ->
                val isSelected = allowSelection && (selectedVideoIds?.contains(video.id) == true)
                VideoGridCard(
                    video = video,
                    inSelectionMode = inSelectionMode,
                    isSelected = isSelected,
                    onClick = {
                        if (inSelectionMode && selectedVideoIds != null) {
                            if (isSelected) selectedVideoIds.remove(video.id) else selectedVideoIds.add(video.id)
                        } else {
                            onVideoClick(video)
                        }
                    },
                    onLongClick = {
                        if (allowSelection && selectedVideoIds != null) {
                            if (isSelected) selectedVideoIds.remove(video.id) else selectedVideoIds.add(video.id)
                        }
                    },
                    onStandardize = { onStandardize(video) },
                    onAddToPlaylist = { onAddToPlaylist(video) },
                    onRemoveFromPlaylist = onRemoveFromPlaylist?.let { cb -> { cb(video) } },
                    onShare = { onShare(video) },
                    onDelete = { onDelete(video) }
                )
            }
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp, top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            lazyItems(items, key = { it.id }) { video ->
                val isSelected = allowSelection && (selectedVideoIds?.contains(video.id) == true)
                VideoListRow(
                    video = video,
                    isSelected = isSelected,
                    onClick = {
                        if (inSelectionMode && selectedVideoIds != null) {
                            if (isSelected) selectedVideoIds.remove(video.id) else selectedVideoIds.add(video.id)
                        } else {
                            onVideoClick(video)
                        }
                    },
                    onLongClick = {
                        if (allowSelection && selectedVideoIds != null) {
                            if (isSelected) selectedVideoIds.remove(video.id) else selectedVideoIds.add(video.id)
                        }
                    },
                    onStandardize = { onStandardize(video) },
                    onAddToPlaylist = { onAddToPlaylist(video) },
                    onRemoveFromPlaylist = onRemoveFromPlaylist?.let { cb -> { cb(video) } },
                    onShare = { onShare(video) },
                    onDelete = { onDelete(video) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoListRow(
    video: DownloadItem,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onStandardize: () -> Unit,
    onAddToPlaylist: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val thumbData = video.thumbnailUrl ?: video.localFilePath ?: video.url
    val imageRequest = remember(video.id, thumbData) {
        ImageRequest.Builder(context)
            .data(thumbData)
            .size(320, 180)
            .crossfade(true)
            .build()
    }
    val origin = remember(video.id, video.localFilePath) { videoOriginLabel(video) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(12.dp),
        border = if (isSelected) BorderStroke(1.5.dp, VideoNeonCyan) else BorderStroke(1.dp, VideoBorderSlate),
        color = if (isSelected) {
            VideoNeonCyan.copy(alpha = 0.14f)
        } else {
            VideoTitaniumCard
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(124.dp)
                    .height(70.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black)
            ) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = video.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                if (video.durationFormatted.isNotBlank()) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp),
                        color = Color.Black.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = video.durationFormatted,
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = VideoTextPrimary,
                    fontSize = 13.5.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = video.fileSizeFormatted.ifBlank { "Video" },
                        style = MaterialTheme.typography.labelSmall,
                        color = VideoTextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                    Surface(
                        color = VideoNeonCyan.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = origin,
                            color = VideoNeonCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = "Opciones",
                        tint = VideoTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                VideoOptionsMenu(
                    expanded = menuExpanded,
                    onDismiss = { menuExpanded = false },
                    onPlay = onClick,
                    onStandardize = onStandardize,
                    onAddToPlaylist = onAddToPlaylist,
                    onRemoveFromPlaylist = onRemoveFromPlaylist,
                    onShare = onShare,
                    onDelete = onDelete
                )
            }
        }
    }
}

@Composable
private fun VideoOptionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onStandardize: () -> Unit,
    onAddToPlaylist: (() -> Unit)?,
    onRemoveFromPlaylist: (() -> Unit)?,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Reproducir") },
            leadingIcon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null) },
            onClick = { onDismiss(); onPlay() }
        )
        DropdownMenuItem(
            text = { Text("Estandarizar nombre") },
            leadingIcon = { Icon(Icons.Rounded.AutoFixHigh, contentDescription = null) },
            onClick = { onDismiss(); onStandardize() }
        )
        if (onAddToPlaylist != null) {
            DropdownMenuItem(
                text = { Text("Añadir a playlist") },
                leadingIcon = { Icon(Icons.Rounded.PlaylistAdd, contentDescription = null) },
                onClick = { onDismiss(); onAddToPlaylist() }
            )
        }
        if (onRemoveFromPlaylist != null) {
            DropdownMenuItem(
                text = { Text("Quitar de esta playlist") },
                leadingIcon = { Icon(Icons.Rounded.PlaylistRemove, contentDescription = null) },
                onClick = { onDismiss(); onRemoveFromPlaylist() }
            )
        }
        DropdownMenuItem(
            text = { Text("Compartir") },
            leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
            onClick = { onDismiss(); onShare() }
        )
        DropdownMenuItem(
            text = { Text("Eliminar", color = Color(0xFFFF5252)) },
            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = Color(0xFFFF5252)) },
            onClick = { onDismiss(); onDelete() }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoGridCard(
    video: DownloadItem,
    inSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onStandardize: () -> Unit,
    onAddToPlaylist: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val thumbData = video.thumbnailUrl ?: video.localFilePath ?: video.url
    val imageRequest = remember(video.id, thumbData) {
        ImageRequest.Builder(context)
            .data(thumbData)
            .size(360, 202)
            .crossfade(true)
            .build()
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(12.dp),
        border = if (isSelected) BorderStroke(2.dp, VideoNeonCyan) else BorderStroke(1.dp, VideoBorderSlate),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                VideoNeonCyan.copy(alpha = 0.14f)
            } else {
                VideoTitaniumCard
            }
        )
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black)
            ) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = video.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )

                if (video.durationFormatted.isNotBlank()) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp),
                        color = Color.Black.copy(alpha = 0.78f),
                        shape = RoundedCornerShape(5.dp)
                    ) {
                        Text(
                            text = video.durationFormatted,
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 7.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = video.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = VideoTextPrimary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "${video.fileSizeFormatted.ifBlank { "Video" }} • ${videoOriginLabel(video)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = VideoTextSecondary,
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            contentDescription = "Opciones",
                            tint = VideoTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    VideoOptionsMenu(
                        expanded = menuExpanded,
                        onDismiss = { menuExpanded = false },
                        onPlay = onClick,
                        onStandardize = onStandardize,
                        onAddToPlaylist = onAddToPlaylist,
                        onRemoveFromPlaylist = onRemoveFromPlaylist,
                        onShare = onShare,
                        onDelete = onDelete
                    )
                }
            }
        }
    }
}
