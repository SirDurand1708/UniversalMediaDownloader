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
internal fun VideoDialogsHost(
    videosViewModel: VideosViewModel,
    rawVideos: List<DownloadItem>,
    videoPlaylists: List<com.amurayada.music.ui.viewmodel.VideoPlaylist>,
    currentSort: SortOption,
    showSortDialog: Boolean,
    onDismissSortDialog: () -> Unit,
    videoStandardizeCandidates: List<StandardizeCandidate>?,
    onDismissStandardize: () -> Unit,
    onConfirmStandardize: (List<StandardizeCandidate>) -> Unit,
    videoIdsPendingPlaylist: List<String>?,
    onDismissVideoIdsPendingPlaylist: () -> Unit,
    showCreatePlaylistDialog: Boolean,
    onShowCreatePlaylistDialogChange: (Boolean) -> Unit,
    pendingPlaylistInitialVideos: List<String>,
    onPendingPlaylistInitialVideosChange: (List<String>) -> Unit,
    renameTargetPlaylist: com.amurayada.music.ui.viewmodel.VideoPlaylist?,
    onDismissRenamePlaylist: () -> Unit,
    deleteTargetPlaylist: com.amurayada.music.ui.viewmodel.VideoPlaylist?,
    onDismissDeletePlaylist: () -> Unit,
    onClearOpenedPlaylistIfMatches: (String) -> Unit
) {
    val context = LocalContext.current
    if (showSortDialog) {
        AlertDialog(
            onDismissRequest = { onDismissSortDialog() },
            containerColor = VideoTitaniumCard,
            titleContentColor = VideoTextPrimary,
            textContentColor = VideoTextPrimary,
            title = { Text("Ordenar videos por", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    SortOptionRow("Más reciente (Fecha)", currentSort == SortOption.DATE_DESC) {
                        videosViewModel.setSortOption(SortOption.DATE_DESC)
                        onDismissSortDialog()
                    }
                    SortOptionRow("Más antiguo", currentSort == SortOption.DATE_ASC) {
                        videosViewModel.setSortOption(SortOption.DATE_ASC)
                        onDismissSortDialog()
                    }
                    SortOptionRow("Nombre: A a Z", currentSort == SortOption.NAME_ASC) {
                        videosViewModel.setSortOption(SortOption.NAME_ASC)
                        onDismissSortDialog()
                    }
                    SortOptionRow("Nombre: Z a A", currentSort == SortOption.NAME_DESC) {
                        videosViewModel.setSortOption(SortOption.NAME_DESC)
                        onDismissSortDialog()
                    }
                    SortOptionRow("Tamaño: Mayor a menor", currentSort == SortOption.SIZE_DESC) {
                        videosViewModel.setSortOption(SortOption.SIZE_DESC)
                        onDismissSortDialog()
                    }
                    SortOptionRow("Tamaño: Menor a mayor", currentSort == SortOption.SIZE_ASC) {
                        videosViewModel.setSortOption(SortOption.SIZE_ASC)
                        onDismissSortDialog()
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onDismissSortDialog() },
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
            onDismissRequest = { onDismissStandardize() },
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
                        onDismissStandardize()
                        onConfirmStandardize(batch)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VideoNeonCyan, contentColor = VideoOnAccent)
                ) {
                    Text("Estandarizar (${candidates.size})", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { onDismissStandardize() },
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
            onDismissRequest = { onDismissVideoIdsPendingPlaylist() },
            containerColor = VideoTitaniumCard,
            titleContentColor = VideoTextPrimary,
            textContentColor = VideoTextPrimary,
            title = { Text("Añadir a playlist", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            onPendingPlaylistInitialVideosChange(pendingIds)
                            onDismissVideoIdsPendingPlaylist()
                            onShowCreatePlaylistDialogChange(true)
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
                                        onDismissVideoIdsPendingPlaylist()
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
                    onClick = { onDismissVideoIdsPendingPlaylist() },
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
                onShowCreatePlaylistDialogChange(false)
                onPendingPlaylistInitialVideosChange(emptyList())
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
                        onShowCreatePlaylistDialogChange(false)
                        onPendingPlaylistInitialVideosChange(emptyList())
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
                        onShowCreatePlaylistDialogChange(false)
                        onPendingPlaylistInitialVideosChange(emptyList())
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
            onDismissRequest = { onDismissRenamePlaylist() },
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
                        onDismissRenamePlaylist()
                    },
                    enabled = updatedName.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = VideoNeonCyan, contentColor = VideoOnAccent)
                ) {
                    Text("Guardar", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { onDismissRenamePlaylist() },
                    colors = ButtonDefaults.textButtonColors(contentColor = VideoTextSecondary)
                ) {
                    Text("Cancelar")
                }
            }
        )
    }

    deleteTargetPlaylist?.let { target ->
        AlertDialog(
            onDismissRequest = { onDismissDeletePlaylist() },
            containerColor = VideoTitaniumCard,
            titleContentColor = VideoTextPrimary,
            textContentColor = VideoTextSecondary,
            title = { Text("Eliminar playlist", fontWeight = FontWeight.Bold) },
            text = { Text("¿Eliminar \"${target.name}\"? Solo se eliminará la lista virtual; tus videos seguirán en tu dispositivo.") },
            confirmButton = {
                Button(
                    onClick = {
                        onClearOpenedPlaylistIfMatches(target.id)
                        videosViewModel.deleteVideoPlaylist(target.id)
                        onDismissDeletePlaylist()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VideoDangerRed, contentColor = Color.White)
                ) {
                    Text("Eliminar")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { onDismissDeletePlaylist() },
                    colors = ButtonDefaults.textButtonColors(contentColor = VideoTextSecondary)
                ) {
                    Text("Cancelar")
                }
            }
        )
    }
}

