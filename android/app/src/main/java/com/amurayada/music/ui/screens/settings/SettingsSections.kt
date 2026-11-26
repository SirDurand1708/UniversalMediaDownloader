package com.amurayada.music.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.amurayada.music.ui.viewmodel.SettingsViewModel
import com.omnivault.media.utils.LocalCrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun LazyListScope.settingsBatteryUpdatesAndAboutSections(
    context: Context,
    settingsViewModel: SettingsViewModel,
    isIgnoringBatteryOptimizations: Boolean,
    dismissedUpdateDialogVersion: String?,
    onDismissUpdateDialog: (String) -> Unit,
    onResetDismissedUpdateDialog: () -> Unit,
    showCrashLogDialog: Boolean,
    onOpenCrashLogDialog: () -> Unit,
    crashLogSizeKb: Long,
    onCrashLogLoaded: (sizeKb: Long, text: String?) -> Unit
) {
    if (!isIgnoringBatteryOptimizations) {
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Rounded.BatteryAlert,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Text(
                            text = "Optimización de batería activa",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "Para evitar que Android corte descargas largas con la pantalla apagada, permite que OmniVault se ejecute en segundo plano sin restricciones.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = {
                            try {
                                val intent = Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                try {
                                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                } catch (_: Exception) {
                                    Toast.makeText(context, "Abre Ajustes > Batería para desactivar la optimización", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    ) {
                        Text("Permitir ejecución en 2º plano")
                    }
                }
            }
        }
    }

    item {
        val updateStatus by settingsViewModel.updateStatus.collectAsState()

        LaunchedEffect(updateStatus) {
            if (updateStatus is SettingsViewModel.UpdateStatus.ReadyToInstall) {
                val apk = (updateStatus as SettingsViewModel.UpdateStatus.ReadyToInstall).apkFile
                settingsViewModel.installApk(context, apk)
            }
        }

        if (updateStatus is SettingsViewModel.UpdateStatus.UpdateAvailable) {
            val release = (updateStatus as SettingsViewModel.UpdateStatus.UpdateAvailable).release
            if (dismissedUpdateDialogVersion != release.versionName) {
                AlertDialog(
                    onDismissRequest = { onDismissUpdateDialog(release.versionName) },
                    icon = { Icon(Icons.Rounded.SystemUpdate, contentDescription = null) },
                    title = { Text("Nueva versión disponible: v${release.versionName}") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Tu versión actual es v${settingsViewModel.CURRENT_VERSION}.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = release.body.ifBlank { "Mejoras de rendimiento, estabilidad y nuevas funciones." },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                onDismissUpdateDialog(release.versionName)
                                if (release.downloadUrl.endsWith(".apk", ignoreCase = true)) {
                                    settingsViewModel.downloadAndInstallApk(release.downloadUrl)
                                } else {
                                    try {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.downloadUrl)))
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "No hay aplicación disponible para abrir el enlace", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        ) {
                            Text("Descargar e Instalar")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { onDismissUpdateDialog(release.versionName) }) {
                            Text("Más tarde")
                        }
                    }
                )
            }
        }

        SettingsSection(title = "Actualizaciones de la App", icon = Icons.Rounded.SystemUpdate) {
            SettingsItem(
                title = "Buscar Actualización",
                subtitle = "Versión instalada: v${settingsViewModel.CURRENT_VERSION}",
                icon = Icons.Rounded.Refresh,
                onClick = {
                    onResetDismissedUpdateDialog()
                    settingsViewModel.checkForUpdates()
                }
            )

            when (val status = updateStatus) {
                is SettingsViewModel.UpdateStatus.Checking -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(14.dp))
                        Text("Consultando GitHub Releases...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is SettingsViewModel.UpdateStatus.UpdateAvailable -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("¡Nueva versión v${status.release.versionName} disponible!", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Button(
                                onClick = {
                                    if (status.release.downloadUrl.endsWith(".apk", ignoreCase = true)) {
                                        settingsViewModel.downloadAndInstallApk(status.release.downloadUrl)
                                    } else {
                                        try {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(status.release.downloadUrl)))
                                        } catch (_: Exception) {
                                            Toast.makeText(context, "No hay aplicación disponible para abrir el enlace", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Descargar e Instalar")
                            }
                        }
                    }
                }
                is SettingsViewModel.UpdateStatus.Downloading -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Descargando actualización… ${(status.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        LinearProgressIndicator(
                            progress = { status.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                is SettingsViewModel.UpdateStatus.ReadyToInstall -> {
                    Button(
                        onClick = { settingsViewModel.installApk(context, status.apkFile) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    ) {
                        Text("Instalar APK descargado")
                    }
                }
                is SettingsViewModel.UpdateStatus.UpToDate -> {
                    SettingsItem(
                        title = "App actualizada",
                        subtitle = "Ya tienes la última versión oficial",
                        icon = Icons.Rounded.CheckCircle,
                        onClick = { settingsViewModel.checkForUpdates() }
                    )
                }
                is SettingsViewModel.UpdateStatus.Error -> {
                    SettingsItem(
                        title = "Error al actualizar",
                        subtitle = status.message,
                        icon = Icons.Rounded.Error,
                        onClick = { settingsViewModel.checkForUpdates() }
                    )
                }
                SettingsViewModel.UpdateStatus.Idle -> Unit
            }
        }
    }

    item {
        SettingsSection(title = "Acerca de Aura Player", icon = Icons.Rounded.Info) {
            SettingsItem(
                title = "Nombre",
                subtitle = "Aura Player • Edición Oficial",
                icon = Icons.Rounded.PlayCircle,
                showChevron = false,
                onClick = { }
            )
            SettingsItem(
                title = "Versión",
                subtitle = "v${settingsViewModel.CURRENT_VERSION} (Compilación Universal)",
                icon = Icons.Rounded.Numbers,
                showChevron = false,
                onClick = { }
            )
            SettingsItem(
                title = "Desarrollador",
                subtitle = "OmniVault Team / SirDurand",
                icon = Icons.Rounded.Code,
                showChevron = true,
                onClick = {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/SirDurand1708/UniversalMediaDownloader"))
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        Toast.makeText(context, "No hay aplicación disponible para abrir el enlace", Toast.LENGTH_SHORT).show()
                    }
                }
            )
            SettingsItem(
                title = "Copiar diagnóstico técnico",
                subtitle = "Versión, dispositivo, Android SDK y espacio libre",
                icon = Icons.Rounded.BugReport,
                showChevron = false,
                onClick = {
                    val stat = StatFs(context.filesDir.absolutePath)
                    val freeMb = stat.availableBytes / (1024L * 1024L)
                    val diag = buildString {
                        appendLine("App: Aura Player / OmniVault v${settingsViewModel.CURRENT_VERSION}")
                        appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}")
                        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                        appendLine("Espacio libre interno: $freeMb MB")
                    }
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("AuraPlayerDiag", diag))
                    Toast.makeText(context, "Diagnóstico copiado al portapapeles", Toast.LENGTH_SHORT).show()
                }
            )
            LaunchedEffect(showCrashLogDialog) {
                val sizeBytes = withContext(Dispatchers.IO) {
                    LocalCrashLogger.getLogSizeBytes(context)
                }
                val sizeKb = (sizeBytes + 1023L) / 1024L
                val logText = if (showCrashLogDialog) {
                    withContext(Dispatchers.IO) { LocalCrashLogger.readLogs(context) }
                } else null
                onCrashLogLoaded(sizeKb, logText)
            }
            SettingsItem(
                title = "Registro de errores",
                subtitle = if (crashLogSizeKb > 0L) {
                    "Últimos registros disponibles ($crashLogSizeKb KB)"
                } else {
                    "Sin errores registrados"
                },
                icon = Icons.AutoMirrored.Rounded.Article,
                showChevron = true,
                onClick = onOpenCrashLogDialog
            )
            SettingsItem(
                title = "Licencia",
                subtitle = "Código Abierto (GPL-3.0)",
                icon = Icons.Rounded.VerifiedUser,
                showChevron = false,
                onClick = { }
            )
        }
    }
}
