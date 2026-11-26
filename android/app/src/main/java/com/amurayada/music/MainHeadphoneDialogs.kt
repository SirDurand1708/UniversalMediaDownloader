package com.amurayada.music

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import com.amurayada.music.data.model.PlaybackState
import com.amurayada.music.data.repository.SharedPreferencesRepositoryImpl
import com.amurayada.music.ui.viewmodel.PlaybackViewModel
import com.amurayada.music.service.HeadphoneUsageMonitor
import kotlinx.coroutines.delay

@Composable
internal fun MainHeadphoneDialogsHost(
    playbackViewModel: PlaybackViewModel,
    showWarning60: Boolean,
    onDismissWarning60: () -> Unit,
    showWarning75: Boolean,
    onDismissWarning75: () -> Unit,
    showWarning90: Boolean,
    onDismissWarning90: () -> Unit
) {
    val context = LocalContext.current

    if (showWarning60) {
        AlertDialog(
            onDismissRequest = onDismissWarning60,
            title = { Text("⏰ Hora de descansar los oídos") },
            text = { Text("Llevas 1 hora escuchando con auriculares.\n¿Te tomas un descanso de 5-10 minutos?") },
            confirmButton = {
                TextButton(onClick = {
                    onDismissWarning60()
                    if (playbackViewModel.playbackState is PlaybackState.Playing) {
                        playbackViewModel.togglePlayPause()
                    }
                }) {
                    Text("✅ Sí, descansar")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissWarning60) {
                    Text("❌ No, seguir")
                }
            }
        )
    }

    if (showWarning75) {
        var showPostpone by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(30000)
            showPostpone = true
        }

        AlertDialog(
            onDismissRequest = { },
            title = { Text("⚠️ Descanso recomendado (en serio)") },
            text = { Text("Ya son 1 hora y 15 minutos continuos.\nTu salud auditiva necesita un respiro.") },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        onDismissWarning75()
                        if (playbackViewModel.playbackState is PlaybackState.Playing) {
                            playbackViewModel.togglePlayPause()
                        }
                    }) {
                        Text("🛑 Pausar y descansar")
                    }
                    if (showPostpone) {
                        TextButton(onClick = onDismissWarning75) {
                            Text("😮‍💨 Posponer 5 min")
                        }
                    }
                }
            }
        )
    }

    if (showWarning90) {
        var timeLeft by remember { mutableStateOf(10) }
        var dailyCancels by remember { mutableStateOf(0) }

        val prefsRepo = remember {
            (context.applicationContext as? MusicApplication)?.appContainer?.preferencesRepository
                ?: SharedPreferencesRepositoryImpl(context)
        }
        LaunchedEffect(Unit) {
            dailyCancels = prefsRepo.getHeadphoneDailyCancels()
        }

        LaunchedEffect(timeLeft) {
            if (timeLeft > 0) {
                delay(1000)
                timeLeft--
            } else {
                if (playbackViewModel.playbackState is PlaybackState.Playing) {
                    playbackViewModel.togglePlayPause()
                }
                onDismissWarning90()
            }
        }

        AlertDialog(
            onDismissRequest = { },
            title = { Text("🚫 Límite de uso superado") },
            text = { Text("¡90 minutos con auriculares!\nPor tu bien, la música se pausará en $timeLeft segundos.") },
            confirmButton = {
                if (dailyCancels < HeadphoneUsageMonitor.MAX_DAILY_CANCELS) {
                    TextButton(onClick = {
                        prefsRepo.incrementHeadphoneDailyCancels()
                        onDismissWarning90()
                    }) {
                        Text("🆘 Cancelar pausa ($dailyCancels/${HeadphoneUsageMonitor.MAX_DAILY_CANCELS})")
                    }
                } else {
                    TextButton(onClick = {}, enabled = false) {
                        Text("🆘 Sin cancelaciones hoy")
                    }
                }
            }
        )
    }
}
