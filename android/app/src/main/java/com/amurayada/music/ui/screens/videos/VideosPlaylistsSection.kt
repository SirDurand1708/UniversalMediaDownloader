package com.amurayada.music.ui.screens.videos

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.amurayada.music.ui.viewmodel.VideoPlaylist
import com.amurayada.music.ui.viewmodel.VideosViewModel
import com.omnivault.media.model.DownloadItem
import java.io.File

internal fun shareVideoItem(context: Context, video: DownloadItem) {
    val path = video.localFilePath ?: return
    try {
        val uri = if (path.startsWith("content://")) {
            Uri.parse(path)
        } else {
            val file = File(path)
            if (!file.exists()) {
                Toast.makeText(context, "Archivo de video no encontrado", Toast.LENGTH_SHORT).show()
                return
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
}

@Composable
internal fun VideosPlaylistsSection(
    videosViewModel: VideosViewModel,
    rawVideos: List<DownloadItem>,
    videoPlaylists: List<VideoPlaylist>,
    openedPlaylist: VideoPlaylist?,
    isGridView: Boolean,
    onOpenPlaylist: (String?) -> Unit,
    onShowCreatePlaylist: () -> Unit,
    onRenamePlaylist: (VideoPlaylist) -> Unit,
    onDeletePlaylist: (VideoPlaylist) -> Unit,
    onAddVideoToPlaylist: (DownloadItem) -> Unit,
    onVideoClick: (DownloadItem) -> Unit,
    onRequireDeletePermission: (DownloadItem, IntentSenderRequest) -> Unit
) {
    val context = LocalContext.current
    val videoMap = remember(rawVideos) { rawVideos.associateBy { it.id } }

    if (openedPlaylist == null) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Surface(
                    onClick = onShowCreatePlaylist,
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
                    onClick = { onOpenPlaylist(pl.id) },
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
                                        onRenamePlaylist(pl)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Eliminar", color = Color(0xFFFF5252)) },
                                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = Color(0xFFFF5252)) },
                                    onClick = {
                                        plMenuExpanded = false
                                        onDeletePlaylist(pl)
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
            IconButton(onClick = { onOpenPlaylist(null) }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Volver a playlists", tint = VideoTextPrimary)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(openedPlaylist.name, fontWeight = FontWeight.Bold, color = VideoTextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${plVideos.size} ${if (plVideos.size == 1) "video" else "videos"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = VideoTextSecondary
                )
            }
            IconButton(onClick = { onRenamePlaylist(openedPlaylist) }) {
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
                        textAlign = TextAlign.Center
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
                onAddToPlaylist = onAddVideoToPlaylist,
                onRemoveFromPlaylist = { video ->
                    videosViewModel.removeVideoFromPlaylist(openedPlaylist.id, video.id)
                    Toast.makeText(context, "Quitado de ${openedPlaylist.name}", Toast.LENGTH_SHORT).show()
                },
                onShare = { video -> shareVideoItem(context, video) },
                onDelete = { video ->
                    videosViewModel.deleteVideo(
                        item = video,
                        onRequirePermission = { req -> onRequireDeletePermission(video, req) },
                        onComplete = { success ->
                            Toast.makeText(
                                context,
                                if (success) "Video eliminado" else "No se pudo eliminar el video",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }
            )
        }
    }
}
