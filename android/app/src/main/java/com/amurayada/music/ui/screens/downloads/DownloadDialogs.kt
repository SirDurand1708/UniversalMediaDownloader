package com.amurayada.music.ui.screens.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.amurayada.music.ui.viewmodel.DownloadViewModel
import com.omnivault.media.downloader.PlaylistEntryPreview
import com.omnivault.media.model.MediaFormat

@Composable
internal fun DownloadDialogsHost(
    downloadViewModel: DownloadViewModel,
    hybridPlaylistPromptUrl: String?,
    duplicateWarning: Pair<String, MediaFormat>?,
    playlistEntries: List<PlaylistEntryPreview>,
    selectedFormat: MediaFormat,
    accentColor: Color,
    onDownloadStarted: () -> Unit
) {
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
                        downloadViewModel.startUniversalDownload(dupUrl, dupFormat, forceDuplicate = true) {
                            onDownloadStarted()
                        }
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
                        downloadViewModel.enqueueBatch(urls, selectedFormat) {
                            onDownloadStarted()
                        }
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
