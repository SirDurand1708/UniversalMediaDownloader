package com.amurayada.music.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amurayada.music.data.model.Song
import com.amurayada.music.ui.viewmodel.SettingsViewModel
import com.omnivault.media.model.DownloadItem
import com.omnivault.media.utils.BulkMediaStandardizer
import com.omnivault.media.utils.LocalCrashLogger
import com.omnivault.media.utils.StandardizeCandidate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

@Composable
internal fun SettingsDialogsHost(
    settingsViewModel: SettingsViewModel,
    scope: CoroutineScope,
    themeMode: String,
    sleepTimerMinutes: Int,
    showCrashLogDialog: Boolean,
    onDismissCrashLog: () -> Unit,
    crashLogText: String,
    onClearCrashLog: () -> Unit,
    showThemeDialog: Boolean,
    onDismissThemeDialog: () -> Unit,
    showSleepTimerDialog: Boolean,
    onDismissSleepTimerDialog: () -> Unit,
    showRescanDialog: Boolean,
    onDismissRescanDialog: () -> Unit,
    onRescanLibrary: () -> Unit,
    standardizeCandidates: List<StandardizeCandidate>?,
    onDismissStandardize: () -> Unit,
    onStandardizeSuccess: (renamedCount: Int, undoBatch: List<StandardizeCandidate>, pendingChunks: List<List<StandardizeCandidate>>) -> Unit,
    duplicateVideosToDelete: List<DownloadItem>?,
    onDismissDuplicateVideos: () -> Unit,
    duplicateSongsToDelete: List<Pair<Song, Long>>?,
    onDismissDuplicateSongs: () -> Unit
) {
    val context = LocalContext.current

    if (showCrashLogDialog) {
        AlertDialog(
            onDismissRequest = onDismissCrashLog,
            title = { Text("Registro de errores local") },
            text = {
                if (crashLogText.isBlank()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = "No se han registrado errores ni cierres inesperados.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = crashLogText,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        )
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (crashLogText.isNotBlank()) {
                        TextButton(
                            onClick = {
                                LocalCrashLogger.clearLogs(context)
                                onClearCrashLog()
                                Toast.makeText(context, "Registro limpiado", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("Limpiar")
                        }
                        Button(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("AuraPlayerCrashLog", crashLogText))
                                Toast.makeText(context, "Log copiado al portapapeles", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("Copiar")
                        }
                    } else {
                        Button(onClick = onDismissCrashLog) {
                            Text("Cerrar")
                        }
                    }
                }
            },
            dismissButton = if (crashLogText.isNotBlank()) {
                {
                    TextButton(onClick = onDismissCrashLog) {
                        Text("Cerrar")
                    }
                }
            } else null
        )
    }

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = onDismissThemeDialog,
            title = { Text("Seleccionar tema") },
            text = {
                Column {
                    listOf(
                        "system" to "Sistema",
                        "light" to "Claro",
                        "dark" to "Oscuro"
                    ).forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    settingsViewModel.setThemeMode(value)
                                    onDismissThemeDialog()
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = themeMode == value,
                                onClick = {
                                    settingsViewModel.setThemeMode(value)
                                    onDismissThemeDialog()
                                }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = { }
        )
    }

    if (showSleepTimerDialog) {
        AlertDialog(
            onDismissRequest = onDismissSleepTimerDialog,
            title = { Text("Temporizador de sueño") },
            text = {
                Column {
                    listOf(
                        0 to "Desactivado",
                        15 to "15 minutos",
                        30 to "30 minutos",
                        45 to "45 minutos",
                        60 to "1 hora",
                        90 to "1 hora 30 min",
                        120 to "2 horas"
                    ).forEach { (minutes, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    settingsViewModel.setSleepTimer(minutes)
                                    onDismissSleepTimerDialog()
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = sleepTimerMinutes == minutes,
                                onClick = {
                                    settingsViewModel.setSleepTimer(minutes)
                                    onDismissSleepTimerDialog()
                                }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = { }
        )
    }

    if (showRescanDialog) {
        AlertDialog(
            onDismissRequest = onDismissRescanDialog,
            icon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
            title = { Text("Reescanear biblioteca") },
            text = { Text("Se buscarán nuevas canciones en tu dispositivo.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRescanLibrary()
                        onDismissRescanDialog()
                    }
                ) {
                    Text("Escanear")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRescanDialog) {
                    Text("Cancelar")
                }
            }
        )
    }

    standardizeCandidates?.let { candidates ->
        val audioCount = candidates.count { !it.isVideo }
        val videoCount = candidates.count { it.isVideo }
        val examples = candidates.take(3)
        AlertDialog(
            onDismissRequest = onDismissStandardize,
            icon = { Icon(Icons.Rounded.AutoFixHigh, contentDescription = null) },
            title = { Text("Estandarizar nombres", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Se encontraron $audioCount audios y $videoCount videos para limpiar y ordenar (se omiten notas de WhatsApp y cámara).",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (examples.isNotEmpty()) {
                        Text(
                            text = "Ejemplos:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
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
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val batch = candidates
                        onDismissStandardize()
                        scope.launch {
                            val result = BulkMediaStandardizer.applyBatch(context, batch)
                            val undoBatch = if (result.renamedCount > 0) {
                                batch.map { item ->
                                    val parent = File(item.currentPath).parentFile
                                    val newPath = if (parent != null) File(parent, "${item.targetBaseName}.${item.extension}").absolutePath else item.currentPath
                                    item.copy(
                                        currentPath = newPath,
                                        currentBaseName = item.targetBaseName,
                                        targetBaseName = item.currentBaseName,
                                        newTitle = item.currentBaseName
                                    )
                                }
                            } else emptyList()
                            onStandardizeSuccess(result.renamedCount, undoBatch, result.pendingPermissionChunks)
                        }
                    }
                ) {
                    Text("Estandarizar (${candidates.size})")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissStandardize) {
                    Text("Cancelar")
                }
            }
        )
    }

    duplicateVideosToDelete?.let { duplicates ->
        val totalMb = duplicates.sumOf { it.fileSizeBytes } / (1024.0 * 1024.0)
        AlertDialog(
            onDismissRequest = onDismissDuplicateVideos,
            icon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
            title = { Text("Videos duplicados (${duplicates.size})") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = String.format("Se conservará 1 copia de cada video y puedes eliminar ${duplicates.size} copias repetidas para liberar %.1f MB:", totalMb),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    duplicates.take(4).forEach { item ->
                        Text(
                            text = String.format("• %s (%.1f MB)", item.title, item.fileSizeBytes / (1024.0 * 1024.0)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                    if (duplicates.size > 4) {
                        Text(
                            text = "… y ${duplicates.size - 4} más.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        val batch = duplicates
                        onDismissDuplicateVideos()
                        settingsViewModel.deleteDuplicateVideos(batch)
                    }
                ) {
                    Text("Eliminar copias")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDuplicateVideos) {
                    Text("Cancelar")
                }
            }
        )
    }

    duplicateSongsToDelete?.let { duplicates ->
        val totalMb = duplicates.sumOf { it.second } / (1024.0 * 1024.0)
        AlertDialog(
            onDismissRequest = onDismissDuplicateSongs,
            icon = { Icon(Icons.Rounded.LibraryMusic, contentDescription = null) },
            title = { Text("Canciones duplicadas (${duplicates.size})") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = String.format("Se conservará 1 copia de cada canción y puedes eliminar ${duplicates.size} copias repetidas para liberar %.1f MB:", totalMb),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    duplicates.take(4).forEach { (song, len) ->
                        Text(
                            text = String.format("• %s (%.1f MB)", song.title, len / (1024.0 * 1024.0)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                    if (duplicates.size > 4) {
                        Text(
                            text = "… y ${duplicates.size - 4} más.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        val batch = duplicates
                        onDismissDuplicateSongs()
                        settingsViewModel.deleteDuplicateAudios(batch.map { it.first })
                    }
                ) {
                    Text("Eliminar copias")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDuplicateSongs) {
                    Text("Cancelar")
                }
            }
        )
    }
}
