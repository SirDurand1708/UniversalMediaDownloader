package com.amurayada.music.ui.screens.downloads

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
    onPlayMedia: (DownloadItem) -> Unit = {}
) {
    val context = LocalContext.current
    val urlInput by downloadViewModel.urlInput.collectAsState()
    val selectedFormat by downloadViewModel.selectedFormat.collectAsState()
    val mediaPreview by downloadViewModel.mediaPreview.collectAsState()
    val isAnalyzing by downloadViewModel.isAnalyzing.collectAsState()
    val universalDownloadStates by downloadViewModel.universalDownloadStates.collectAsState()
    val downloadedSongs by downloadViewModel.downloadedSongs.collectAsState()

    val activeDownloadList = remember(universalDownloadStates) {
        universalDownloadStates.values.filter { state ->
            state.status == DownloadStatus.QUEUED ||
                state.status == DownloadStatus.FETCHING_METADATA ||
                state.status == DownloadStatus.DOWNLOADING ||
                state.status == DownloadStatus.PROCESSING
        }
    }

    val neonGreen = Color(0xFF00E676)
    val cardBg = Color(0xFF141923).copy(alpha = 0.85f)
    val borderCol = Color(0xFF263248)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Header
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFF00E676), Color(0xFF00B0FF)))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.CloudDownload,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "DESCARGAS",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Text(
                            text = "Aura Player • Universal Engine (yt-dlp + FFmpeg)",
                            style = MaterialTheme.typography.labelSmall,
                            color = neonGreen
                        )
                    }
                }

                Surface(
                    color = neonGreen.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, neonGreen.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "⚡ Auto-Sync",
                            style = MaterialTheme.typography.labelSmall,
                            color = neonGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // 2. Tarjeta de Entrada de Enlace (URL + Pegar + Analizar)
        item {
            Surface(
                color = cardBg,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, borderCol)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ENLACE MULTIMEDIA",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = neonGreen,
                            letterSpacing = 1.sp
                        )

                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                if (clipText.isNotBlank()) {
                                    downloadViewModel.urlInput.value = clipText
                                    downloadViewModel.analyzeUrl(clipText)
                                } else {
                                    Toast.makeText(context, "El portapapeles está vacío", Toast.LENGTH_SHORT).show()
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                Icons.Rounded.ContentPaste,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = neonGreen
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Pegar", color = neonGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { downloadViewModel.urlInput.value = it },
                        placeholder = { Text("Pega enlace de YouTube, TikTok, Instagram, X...", fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null, tint = Color.Gray) },
                        trailingIcon = {
                            if (urlInput.isNotEmpty()) {
                                IconButton(onClick = {
                                    downloadViewModel.urlInput.value = ""
                                    downloadViewModel.mediaPreview.value = null
                                }) {
                                    Icon(Icons.Rounded.Clear, contentDescription = "Limpiar", tint = Color.Gray)
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = neonGreen,
                            unfocusedBorderColor = borderCol
                        )
                    )

                    Spacer(Modifier.height(10.dp))

                    Button(
                        onClick = { downloadViewModel.analyzeUrl(urlInput) },
                        enabled = urlInput.isNotBlank() && !isAnalyzing,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1E2838),
                            contentColor = Color.White
                        )
                    ) {
                        if (isAnalyzing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = neonGreen,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Analizando enlace...")
                        } else {
                            Icon(
                                Icons.Rounded.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = neonGreen
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Analizar Enlace", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // 3. Tarjeta de Previsualización, Selector de Formato y Botón Descargar Ahora
        item {
            Surface(
                color = cardBg,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, borderCol)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    AnimatedVisibility(visible = mediaPreview != null) {
                        mediaPreview?.let { preview ->
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color.Black.copy(alpha = 0.45f))
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AsyncImage(
                                        model = preview.thumbnailUrl,
                                        contentDescription = preview.title,
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF1E2838)),
                                        contentScale = ContentScale.Crop
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = preview.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = "${preview.platform.displayName} • ${preview.author}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = neonGreen
                                        )
                                    }
                                }
                                Spacer(Modifier.height(14.dp))
                            }
                        }
                    }

                    Text(
                        text = "FORMATO DE DESCARGA:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = neonGreen,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(8.dp))

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

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FormatChip(
                            label = "MP4 • 1080p Full HD",
                            selected = selectedFormat == MediaFormat.MP4_1080,
                            modifier = Modifier.weight(1f),
                            onClick = { downloadViewModel.selectedFormat.value = MediaFormat.MP4_1080 }
                        )
                        FormatChip(
                            label = "MP4 • 720p HD",
                            selected = selectedFormat == MediaFormat.MP4_720,
                            modifier = Modifier.weight(1f),
                            onClick = { downloadViewModel.selectedFormat.value = MediaFormat.MP4_720 }
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    val btnLabel = when (selectedFormat) {
                        MediaFormat.MP3_320 -> "⚡ DESCARGAR AHORA (MP3 320K)"
                        MediaFormat.M4A -> "⚡ DESCARGAR AHORA (M4A HQ)"
                        MediaFormat.MP4_1080 -> "⚡ DESCARGAR AHORA (MP4 1080P)"
                        MediaFormat.MP4_720 -> "⚡ DESCARGAR AHORA (MP4 720P)"
                    }

                    Button(
                        onClick = {
                            val targetUrl = mediaPreview?.originalUrl?.takeIf { it.isNotBlank() } ?: urlInput
                            if (targetUrl.isBlank()) {
                                Toast.makeText(context, "Por favor pega un enlace multimedia", Toast.LENGTH_SHORT).show()
                            } else {
                                downloadViewModel.startUniversalDownload(targetUrl, selectedFormat)
                                Toast.makeText(context, "Descarga iniciada en segundo plano", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = neonGreen,
                            contentColor = Color.Black
                        )
                    ) {
                        Text(
                            text = btnLabel,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }
        }

        // 4. Sección de Descargas Activas (universalDownloadStates)
        if (activeDownloadList.isNotEmpty()) {
            item {
                Text(
                    text = "DESCARGAS ACTIVAS (${activeDownloadList.size})",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = neonGreen,
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

        // 5. Sección de Descargas Completadas (downloadedSongs)
        item {
            Text(
                text = "DESCARGAS COMPLETADAS (${downloadedSongs.size})",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = neonGreen,
                letterSpacing = 1.sp
            )
        }

        if (downloadedSongs.isEmpty()) {
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
                            text = "Aún no tienes descargas completadas",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        } else {
            items(downloadedSongs, key = { it.id }) { song ->
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

@Composable
fun FormatChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val neonGreen = Color(0xFF00E676)
    Surface(
        onClick = onClick,
        modifier = modifier.height(42.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) neonGreen.copy(alpha = 0.2f) else Color(0xFF141923),
        border = BorderStroke(
            1.2.dp,
            if (selected) neonGreen else Color(0xFF263248)
        )
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected) {
                    Text("✓ ", color = neonGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) Color.White else Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
fun UniversalActiveDownloadCard(
    state: UniversalDownloadState,
    onCancel: () -> Unit
) {
    val neonGreen = Color(0xFF00E676)
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
                color = neonGreen,
                trackColor = Color(0xFF263248)
            )

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "$percent% • ${state.speedText}",
                    style = MaterialTheme.typography.labelSmall,
                    color = neonGreen
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
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    val neonGreen = Color(0xFF00E676)
    val isVideo = song.album.contains("Video", ignoreCase = true) ||
        song.path.contains("/video/", ignoreCase = true) ||
        song.path.endsWith(".mp4", ignoreCase = true)

    Surface(
        onClick = onPlay,
        color = Color(0xFF141923).copy(alpha = 0.75f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFF202838))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
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
                        tint = neonGreen,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${song.artist} • ${if (isVideo) "Video MP4" else "Audio HQ"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 11.sp
                )
            }

            IconButton(onClick = onPlay, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = "Reproducir",
                    tint = neonGreen,
                    modifier = Modifier.size(22.dp)
                )
            }

            IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = "Eliminar descarga",
                    tint = Color(0xFFFF5252),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
