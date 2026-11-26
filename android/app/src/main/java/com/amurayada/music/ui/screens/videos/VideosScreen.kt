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

internal val VideoNeonCyan @Composable get() = LocalAuraPalette.current.accent
internal val VideoNeonGreen @Composable get() = LocalAuraPalette.current.accentGreen
internal val VideoTitaniumCard @Composable get() = LocalAuraPalette.current.cardBg
internal val VideoBorderSlate @Composable get() = LocalAuraPalette.current.border
internal val VideoTextSecondary @Composable get() = LocalAuraPalette.current.textSecondary
internal val VideoTextPrimary @Composable get() = LocalAuraPalette.current.textPrimary
internal val VideoOnAccent @Composable get() = LocalAuraPalette.current.onAccent
internal val VideoDangerRed = Color(0xFFFF5252)

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

    val prefsRepo = remember {
        (context.applicationContext as? com.amurayada.music.MusicApplication)?.appContainer?.preferencesRepository
            ?: com.amurayada.music.data.repository.SharedPreferencesRepositoryImpl(context)
    }
    var isGridView by remember { mutableStateOf(prefsRepo.getVideoGridView()) }
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
                    prefsRepo.setVideoGridView(next)
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
    VideoDialogsHost(
        videosViewModel = videosViewModel,
        rawVideos = rawVideos,
        videoPlaylists = videoPlaylists,
        currentSort = currentSort,
        showSortDialog = showSortDialog,
        onDismissSortDialog = { showSortDialog = false },
        videoStandardizeCandidates = videoStandardizeCandidates,
        onDismissStandardize = { videoStandardizeCandidates = null },
        onConfirmStandardize = { batch ->
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
        videoIdsPendingPlaylist = videoIdsPendingPlaylist,
        onDismissVideoIdsPendingPlaylist = { videoIdsPendingPlaylist = null },
        showCreatePlaylistDialog = showCreatePlaylistDialog,
        onShowCreatePlaylistDialogChange = { showCreatePlaylistDialog = it },
        pendingPlaylistInitialVideos = pendingPlaylistInitialVideos,
        onPendingPlaylistInitialVideosChange = { pendingPlaylistInitialVideos = it },
        renameTargetPlaylist = renameTargetPlaylist,
        onDismissRenamePlaylist = { renameTargetPlaylist = null },
        deleteTargetPlaylist = deleteTargetPlaylist,
        onDismissDeletePlaylist = { deleteTargetPlaylist = null },
        onClearOpenedPlaylistIfMatches = { id -> if (openedPlaylistId == id) openedPlaylistId = null }
    )
}
