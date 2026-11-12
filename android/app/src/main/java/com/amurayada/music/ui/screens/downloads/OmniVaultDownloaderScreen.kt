package com.amurayada.music.ui.screens.downloads

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import com.amurayada.music.ui.viewmodel.DownloadViewModel
import com.omnivault.media.model.DownloadItem
import com.omnivault.media.model.DownloadStatus
import com.omnivault.media.model.MediaFormat
import com.omnivault.media.model.PlatformType
import java.io.File

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
    val activeDownloads by downloadViewModel.omniActiveDownloads.collectAsState()

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
        // 1. Header TU BÓVEDA
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
                            text = "Aura Player • Universal Engine",
                            style = MaterialTheme.typography.labelSmall,
                            color = neonGreen
                        )
                    }
                }

                Surface(
                    color = neonGreen.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, neonGreen.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("⚡ Auto-Sync", style = MaterialTheme.typography.labelSmall, color = neonGreen, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 2. Banner informativo
        item {
            Surface(
                color = cardBg,
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, borderCol)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⚡", fontSize = 18.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Detección activa: Copia un enlace de YouTube, TikTok, IG, FB o X para descargarlo al instante.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }
        }

        // 3. Tarjeta de Entrada de Enlace
        item {
            Surface(
                color = cardBg,
                shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, borderCol)
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
                            Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp), tint = neonGreen)
                            Spacer(Modifier.width(4.dp))
                            Text("Pegar", color = neonGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { downloadViewModel.urlInput.value = it },
                        placeholder = { Text("Pega enlace de YouTube, TikTok, IG, FB...", fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = Color.Gray) },
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
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = neonGreen, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Analizando enlace...")
                        } else {
                            Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp), tint = neonGreen)
                            Spacer(Modifier.width(8.dp))
                            Text("Analizar Enlace", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    // Previsualización de Metadatos
                    AnimatedVisibility(visible = mediaPreview != null) {
                        mediaPreview?.let { preview ->
                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.Black.copy(alpha = 0.4f))
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (preview.thumbnailUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = preview.thumbnailUrl,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(54.dp)
                                            .clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                    Spacer(Modifier.width(10.dp))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = preview.title,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${preview.platform.displayName} • ${preview.author}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = neonGreen
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. Formato de Descarga
        item {
            Column {
                Text(
                    text = "FORMATO DE AUDIO Y VIDEO:",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = neonGreen,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormatChip(
                        label = "M4A • Original HQ",
                        selected = selectedFormat == MediaFormat.M4A,
                        modifier = Modifier.weight(1f),
                        onClick = { downloadViewModel.selectedFormat.value = MediaFormat.M4A }
                    )
                    FormatChip(
                        label = "MP3 • 320 kbps",
                        selected = selectedFormat == MediaFormat.MP3_320,
                        modifier = Modifier.weight(1f),
                        onClick = { downloadViewModel.selectedFormat.value = MediaFormat.MP3_320 }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormatChip(
                        label = "1080p • Full HD",
                        selected = selectedFormat == MediaFormat.MP4_1080,
                        modifier = Modifier.weight(1f),
                        onClick = { downloadViewModel.selectedFormat.value = MediaFormat.MP4_1080 }
                    )
                    FormatChip(
                        label = "720p • HD",
                        selected = selectedFormat == MediaFormat.MP4_720,
                        modifier = Modifier.weight(1f),
                        onClick = { downloadViewModel.selectedFormat.value = MediaFormat.MP4_720 }
                    )
                }
            }
        }

        // 5. Botón de Descarga Grande
        item {
            val isVideo = selectedFormat == MediaFormat.MP4_1080 || selectedFormat == MediaFormat.MP4_720
            val btnLabel = when (selectedFormat) {
                MediaFormat.M4A -> "⚡ DESCARGAR AUDIO HQ (M4A)"
                MediaFormat.MP3_320 -> "⚡ DESCARGAR AUDIO MP3 (320K)"
                MediaFormat.MP4_1080 -> "⚡ DESCARGAR VIDEO FHD (1080P)"
                MediaFormat.MP4_720 -> "⚡ DESCARGAR VIDEO HD (720P)"
                else -> "⚡ DESCARGAR AHORA"
            }

            Button(
                onClick = {
                    if (urlInput.isBlank()) {
                        Toast.makeText(context, "Por favor pega un enlace multimedia", Toast.LENGTH_SHORT).show()
                    } else {
                        downloadViewModel.startUniversalDownload(urlInput, selectedFormat)
                        Toast.makeText(context, "Descarga iniciada en segundo plano", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
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

        // 6. Descargas Activas
        if (activeDownloads.isNotEmpty()) {
            item {
                Text(
                    text = "DESCARGAS ACTIVAS (${activeDownloads.size})",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = neonGreen,
                    letterSpacing = 1.sp
                )
            }

            items(activeDownloads, key = { it.id }) { item ->
                ActiveDownloadCard(
                    item = item,
                    onCancel = { downloadViewModel.cancelOmniDownload(item.id) }
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
        border = androidx.compose.foundation.BorderStroke(
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
fun ActiveDownloadCard(
    item: DownloadItem,
    onCancel: () -> Unit
) {
    val neonGreen = Color(0xFF00E676)
    Surface(
        color = Color(0xFF141923),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF263248))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onCancel, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = "Cancelar", tint = Color.Gray, modifier = Modifier.size(16.dp))
                }
            }

            Spacer(Modifier.height(6.dp))

            LinearProgressIndicator(
                progress = { item.progress / 100f },
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
                    text = "${item.progress}% • ${item.speedText}",
                    style = MaterialTheme.typography.labelSmall,
                    color = neonGreen
                )
                Text(
                    text = item.format.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
fun HistoryDownloadCard(
    item: DownloadItem,
    onPlay: () -> Unit,
    onShare: () -> Unit
) {
    val neonGreen = Color(0xFF00E676)
    Surface(
        onClick = onPlay,
        color = Color(0xFF141923).copy(alpha = 0.7f),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF202838))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (!item.thumbnailUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = item.thumbnailUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = if (!item.format.isAudio) Icons.Rounded.Movie else Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = neonGreen,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${item.platform.displayName} • ${item.format.label} • ${item.fileSizeFormatted}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 10.sp
                )
            }

            IconButton(onClick = onShare, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.Share, contentDescription = "Compartir", tint = Color.Gray, modifier = Modifier.size(16.dp))
            }

            IconButton(onClick = onPlay, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Reproducir", tint = neonGreen, modifier = Modifier.size(20.dp))
            }
        }
    }
}
