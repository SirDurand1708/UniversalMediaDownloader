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

@Composable
internal fun SortOptionRow(label: String, selected: Boolean, onSelect: () -> Unit) {
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

internal fun videoOriginLabel(video: DownloadItem): String {
    val path = video.localFilePath ?: ""
    return when {
        video.id.startsWith("db_video_") || path.contains("/OmniVault", ignoreCase = true) -> "Descargado"
        path.contains("/DCIM/Camera", ignoreCase = true) || path.contains("/Camera/", ignoreCase = true) -> "Cámara"
        path.contains("WhatsApp", ignoreCase = true) -> "WhatsApp"
        else -> "Local"
    }
}

@Composable
internal fun VideoMediaCollection(
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
internal fun VideoOptionsMenu(
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

