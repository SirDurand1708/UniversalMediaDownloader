package com.amurayada.music.ui.screens.downloads

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import coil.compose.AsyncImage
import com.amurayada.music.data.model.Song
import com.omnivault.media.downloader.UniversalDownloadState
import com.omnivault.media.model.DownloadItem
import com.omnivault.media.model.DownloadStatus

@Composable
fun FormatChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = com.amurayada.music.ui.theme.LocalAuraPalette.current
    val selectedBg = palette.accent
    val selectedText = palette.onAccent
    Surface(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(20.dp),
        color = if (selected) selectedBg else palette.cardBg,
        border = if (selected) null else BorderStroke(1.dp, palette.border)
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
                    color = if (selected) selectedText else palette.textPrimary.copy(alpha = 0.85f),
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
    val palette = com.amurayada.music.ui.theme.LocalAuraPalette.current
    val accentColor = palette.accent
    val percent = (state.progress.coerceIn(0f, 1f) * 100f).toInt()

    Surface(
        color = palette.cardBg,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, palette.border)
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
                        color = palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (state.artist.isNotBlank()) {
                        Text(
                            text = state.artist,
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textPrimary.copy(alpha = 0.6f),
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
                trackColor = palette.trackBg
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
                    color = palette.textPrimary.copy(alpha = 0.6f)
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
    val palette = com.amurayada.music.ui.theme.LocalAuraPalette.current
    val accentColor = palette.accent
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
                val artModel = with(com.amurayada.music.ui.utils.toArtModel(song)) { this }
                if (artModel != null) {
                    AsyncImage(
                        model = artModel,
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
                    color = palette.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "${song.artist} • ${if (isVideo) "Video MP4" else "Audio HQ"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textPrimary.copy(alpha = 0.62f),
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
                        tint = palette.textPrimary.copy(alpha = 0.72f),
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

