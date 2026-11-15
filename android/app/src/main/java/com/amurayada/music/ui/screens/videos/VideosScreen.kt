package com.amurayada.music.ui.screens.videos

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.amurayada.music.ui.viewmodel.VideosViewModel
import com.omnivault.media.model.DownloadItem
import com.omnivault.media.model.SortOption
import com.omnivault.media.utils.BulkMediaStandardizer
import com.omnivault.media.utils.StandardizeCandidate
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideosScreen(
    videosViewModel: VideosViewModel,
    onVideoClick: (DownloadItem) -> Unit
) {
    val context = LocalContext.current
    val videos by videosViewModel.videos.collectAsState()
    val isLoading by videosViewModel.isLoading.collectAsState()
    val searchQuery by videosViewModel.searchQuery.collectAsState()
    val currentSort by videosViewModel.sortOption.collectAsState()
    val hideShortClips by videosViewModel.hideShortClips.collectAsState()
    val onlyDownloaded by videosViewModel.onlyDownloaded.collectAsState()

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
        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "VIDEOS",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "${videos.size}",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                Text(
                    text = "Biblioteca de videos de tu dispositivo",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row {
                IconButton(
                    onClick = {
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
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoFixHigh,
                        contentDescription = "Estandarizar nombres de videos",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                IconButton(onClick = { showSortDialog = true }) {
                    Icon(
                        imageVector = Icons.Rounded.Sort,
                        contentDescription = "Ordenar",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = { videosViewModel.loadVideos() }) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Recargar",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { videosViewModel.searchQuery.value = it },
            placeholder = { Text("Buscar videos por título...") },
            leadingIcon = {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { videosViewModel.searchQuery.value = "" }) {
                        Icon(Icons.Rounded.Clear, contentDescription = "Limpiar")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )
        )

        Spacer(Modifier.height(6.dp))

        // Filtros rápidos: Todos / Descargados / Ocultar clips cortos (< 5s)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = !onlyDownloaded,
                onClick = { videosViewModel.setOnlyDownloaded(false) },
                label = { Text("Todos", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            )
            FilterChip(
                selected = onlyDownloaded,
                onClick = { videosViewModel.setOnlyDownloaded(true) },
                label = { Text("Descargados", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            )
            FilterChip(
                selected = hideShortClips,
                onClick = { videosViewModel.setHideShortClips(!hideShortClips) },
                label = { Text("Ocultar < 5s", fontSize = 12.sp, maxLines = 1, softWrap = false) }
            )
        }

        Spacer(Modifier.height(6.dp))

        // Content
        if (isLoading && videos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
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
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = if (searchQuery.isNotEmpty()) "No se encontraron videos" else "No hay videos registrados",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(videos, key = { it.id }) { video ->
                    VideoGridCard(
                        video = video,
                        onClick = { onVideoClick(video) },
                        onStandardize = {
                            videosViewModel.standardizeVideo(video) { success ->
                                Toast.makeText(
                                    context,
                                    if (success) "Nombre estandarizado con éxito" else "No se pudo renombrar el archivo",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onShare = {
                            val path = video.localFilePath ?: return@VideoGridCard
                            val file = File(path)
                            if (file.exists()) {
                                try {
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file
                                    )
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "video/*"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "Compartir video"))
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Error al compartir: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onDelete = {
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
                TextButton(onClick = { showSortDialog = false }) {
                    Text("Cerrar")
                }
            }
        )
    }

    videoStandardizeCandidates?.let { candidates ->
        val examples = candidates.take(3)
        AlertDialog(
            onDismissRequest = { videoStandardizeCandidates = null },
            icon = { Icon(Icons.Rounded.AutoFixHigh, contentDescription = null) },
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    }
                ) {
                    Text("Estandarizar (${candidates.size})")
                }
            },
            dismissButton = {
                TextButton(onClick = { videoStandardizeCandidates = null }) {
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
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun VideoGridCard(
    video: DownloadItem,
    onClick: () -> Unit,
    onStandardize: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column {
            // Thumbnail container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black)
            ) {
                AsyncImage(
                    model = video.thumbnailUrl ?: video.localFilePath ?: video.url,
                    contentDescription = video.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )

                // Play icon overlay
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(36.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Reproducir",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Duration badge
                if (video.durationFormatted.isNotBlank()) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp),
                        color = Color.Black.copy(alpha = 0.75f),
                        shape = RoundedCornerShape(6.dp)
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

            // Info & Menu
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = video.title,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = video.fileSizeFormatted.ifBlank { "Video" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontSize = 11.sp,
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
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
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
                                onClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Estandarizar nombre") },
                            leadingIcon = { Icon(Icons.Rounded.AutoFixHigh, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onStandardize()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Compartir") },
                            leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onShare()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Eliminar", color = Color(0xFFFF5252)) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = Color(0xFFFF5252)) },
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
}
