package com.amurayada.music.ui.screens.downloads

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.amurayada.music.data.model.Song
import com.amurayada.music.ui.theme.AuraAdaptivePalette
import com.amurayada.music.ui.viewmodel.DownloadViewModel
import com.omnivault.media.downloader.MediaPreview
import com.omnivault.media.downloader.UniversalDownloadState
import com.omnivault.media.model.DownloadItem
import com.omnivault.media.model.DownloadStatus
import com.omnivault.media.model.MediaFormat
import com.omnivault.media.utils.UrlUtils

internal fun LazyListScope.newDownloadPreviewAndControls(
    context: Context,
    palette: AuraAdaptivePalette,
    cardBg: Color,
    borderCol: Color,
    accentColor: Color,
    darkOnAccent: Color,
    downloadViewModel: DownloadViewModel,
    mediaPreview: MediaPreview?,
    isAudioFormatTab: Boolean,
    onAudioFormatTabChange: (Boolean) -> Unit,
    selectedFormat: MediaFormat,
    batchUrls: List<String>,
    urlInput: String,
    activeDownloadList: List<UniversalDownloadState>,
    pausedDownloadList: List<UniversalDownloadState>,
    failedDownloadList: List<UniversalDownloadState>,
    onNavigateToCompletedTab: () -> Unit
) {
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
                                color = palette.textPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = preview.author,
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textSecondary,
                                fontSize = 13.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            val durPart = if (preview.durationText.isNotBlank()) "${preview.durationText} • " else ""
                            Text(
                                text = "${durPart}${preview.platform.displayName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textSecondary,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

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
                            onAudioFormatTabChange(true)
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
                                tint = if (isAudioFormatTab) darkOnAccent else palette.textPrimary.copy(alpha = 0.72f),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Audio",
                                fontSize = 14.sp,
                                fontWeight = if (isAudioFormatTab) FontWeight.Bold else FontWeight.Medium,
                                color = if (isAudioFormatTab) darkOnAccent else palette.textPrimary.copy(alpha = 0.72f),
                                maxLines = 1
                            )
                        }
                    }

                    Surface(
                        onClick = {
                            onAudioFormatTabChange(false)
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
                                tint = if (!isAudioFormatTab) darkOnAccent else palette.textPrimary.copy(alpha = 0.72f),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Video",
                                fontSize = 14.sp,
                                fontWeight = if (!isAudioFormatTab) FontWeight.Bold else FontWeight.Medium,
                                color = if (!isAudioFormatTab) darkOnAccent else palette.textPrimary.copy(alpha = 0.72f),
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
                        downloadViewModel.enqueueBatch(batchUrls, selectedFormat) {
                            onNavigateToCompletedTab()
                        }
                    } else {
                        val targetUrl = mediaPreview?.originalUrl?.takeIf { it.isNotBlank() } ?: urlInput
                        if (targetUrl.isBlank()) {
                            Toast.makeText(context, "Por favor pega un enlace multimedia", Toast.LENGTH_SHORT).show()
                        } else if (UrlUtils.isDirectPlaylistUrl(targetUrl)) {
                            downloadViewModel.analyzeUrl(targetUrl, forcePlaylist = true)
                        } else {
                            downloadViewModel.startUniversalDownload(targetUrl, selectedFormat) {
                                onNavigateToCompletedTab()
                            }
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

    val pendingQueueCount = activeDownloadList.size + pausedDownloadList.size + failedDownloadList.size
    if (pendingQueueCount > 0) {
        item {
            Surface(
                onClick = onNavigateToCompletedTab,
                color = cardBg,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = buildString {
                            val parts = mutableListOf<String>()
                            if (activeDownloadList.isNotEmpty()) parts.add("${activeDownloadList.size} descargando")
                            if (pausedDownloadList.isNotEmpty()) parts.add("${pausedDownloadList.size} en pausa")
                            if (failedDownloadList.isNotEmpty()) parts.add("${failedDownloadList.size} con error")
                            append(parts.joinToString(" • "))
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Ver →",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )
                }
            }
        }
    }
}

internal fun LazyListScope.completedDownloadsTab(
    context: Context,
    palette: AuraAdaptivePalette,
    cardBg: Color,
    borderCol: Color,
    accentColor: Color,
    downloadViewModel: DownloadViewModel,
    activeDownloadList: List<UniversalDownloadState>,
    pausedDownloadList: List<UniversalDownloadState>,
    failedDownloadList: List<UniversalDownloadState>,
    downloadedSongs: List<Song>,
    filteredCompletedSongs: List<Song>,
    completedFilter: Int,
    onCompletedFilterChange: (Int) -> Unit,
    completedSearchQuery: String,
    onCompletedSearchQueryChange: (String) -> Unit,
    onPlayMedia: (DownloadItem) -> Unit
) {
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
                color = if (palette.isDark) Color(0xFF1A1914) else Color(0xFFFFFBEB),
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
                            color = palette.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = state.errorReason ?: "Pausada • Toca Reanudar",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (palette.isDark) Color(0xFFFFE082) else Color(0xFFB45309),
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
                color = if (palette.isDark) Color(0xFF1A1418) else Color(0xFFFEF2F2),
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
                            color = palette.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = state.errorReason ?: "Error de red o enlace",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (palette.isDark) Color(0xFFFF8A80) else palette.danger,
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
                    color = palette.textPrimary,
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
                        onClick = { onCompletedFilterChange(index) },
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
                onValueChange = onCompletedSearchQueryChange,
                placeholder = { Text("Buscar en descargas...", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (completedSearchQuery.isNotEmpty()) {
                        IconButton(onClick = { onCompletedSearchQueryChange("") }) {
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
                        color = palette.textPrimary.copy(alpha = 0.7f)
                    )
                }
            }
        }
    } else {
        items(filteredCompletedSongs, key = { it.id }) { song ->
            val isVideoSong = song.mimeType?.startsWith("video/") == true ||
                song.album.contains("Video", ignoreCase = true) ||
                song.path.contains("/video/", ignoreCase = true) ||
                song.path.endsWith(".mp4", ignoreCase = true) ||
                song.path.endsWith(".mkv", ignoreCase = true) ||
                song.path.endsWith(".webm", ignoreCase = true)

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
