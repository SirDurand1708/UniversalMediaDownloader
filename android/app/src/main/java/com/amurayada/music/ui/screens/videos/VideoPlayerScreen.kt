package com.amurayada.music.ui.screens.videos

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.io.File

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    videoPath: String,
    videoTitle: String,
    onBackClick: () -> Unit,
    onPlayInBackground: (videoPath: String, videoTitle: String, currentPositionMs: Long) -> Unit = { _, _, _ -> }
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var showSpeedMenu by remember { mutableStateOf(false) }

    val exoPlayer = remember {
        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .build()

        ExoPlayer.Builder(context)
            .setAudioAttributes(audioAttributes, true)
            .build().apply {
                val uri = if (videoPath.startsWith("http://") || videoPath.startsWith("https://") || videoPath.startsWith("content://")) {
                    Uri.parse(videoPath)
                } else {
                    Uri.fromFile(File(videoPath))
                }
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
                playWhenReady = true
            }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    duration = exoPlayer.duration.coerceAtLeast(0L)
                }
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Position updater loop
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
            duration = exoPlayer.duration.coerceAtLeast(0L)
            delay(500)
        }
    }

    // Auto-hide controls timer
    LaunchedEffect(controlsVisible, isPlaying) {
        if (controlsVisible && isPlaying) {
            delay(3500)
            controlsVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                controlsVisible = !controlsVisible
            }
    ) {
        // ExoPlayer View
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    this.resizeMode = resizeMode
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { playerView ->
                playerView.resizeMode = resizeMode
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay Controls
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
            ) {
                // Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Atrás",
                            tint = Color.White
                        )
                    }
                    Text(
                        text = videoTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    // Botón de Velocidad (0.5x - 2.0x)
                    Box {
                        TextButton(
                            onClick = { showSpeedMenu = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (playbackSpeed == 1.0f) "1.0x" else "${playbackSpeed}x",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                        DropdownMenu(
                            expanded = showSpeedMenu,
                            onDismissRequest = { showSpeedMenu = false }
                        ) {
                            listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "${speed}x",
                                            fontWeight = if (playbackSpeed == speed) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    },
                                    onClick = {
                                        playbackSpeed = speed
                                        exoPlayer.setPlaybackParameters(
                                            androidx.media3.common.PlaybackParameters(speed)
                                        )
                                        showSpeedMenu = false
                                    }
                                )
                            }
                        }
                    }
                    // Botón Video a Audio en Segundo Plano (Auriculares)
                    IconButton(
                        onClick = {
                            val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                            exoPlayer.pause()
                            onPlayInBackground(videoPath, videoTitle, pos)
                            onBackClick()
                        }
                    ) {
                        Icon(
                            Icons.Rounded.Headphones,
                            contentDescription = "Reproducir en segundo plano",
                            tint = Color.White
                        )
                    }
                    IconButton(
                        onClick = {
                            resizeMode = when (resizeMode) {
                                AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                        }
                    ) {
                        Icon(
                            Icons.Rounded.AspectRatio,
                            contentDescription = "Aspect Ratio",
                            tint = Color.White
                        )
                    }
                }

                // Center Play/Pause & Seek Buttons
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(32.dp)
                ) {
                    IconButton(
                        onClick = {
                            val newPos = (exoPlayer.currentPosition - 10000).coerceAtLeast(0L)
                            exoPlayer.seekTo(newPos)
                            currentPosition = newPos
                        },
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Replay10,
                            contentDescription = "Retroceder 10s",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier
                            .size(68.dp)
                            .clickable {
                                if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                                tint = Color.Black,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            val newPos = (exoPlayer.currentPosition + 10000).coerceAtMost(duration)
                            exoPlayer.seekTo(newPos)
                            currentPosition = newPos
                        },
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Forward10,
                            contentDescription = "Adelantar 10s",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                // Bottom Bar with Progress
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatTime(currentPosition),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                        Text(
                            text = formatTime(duration),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }

                    Slider(
                        value = if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f,
                        onValueChange = { fraction ->
                            val target = (fraction * duration).toLong()
                            exoPlayer.seekTo(target)
                            currentPosition = target
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        )
                    )
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val sec = ms / 1000
    val m = sec / 60
    val s = sec % 60
    val h = m / 60
    return if (h > 0) {
        String.format("%02d:%02d:%02d", h, m % 60, s)
    } else {
        String.format("%02d:%02d", m, s)
    }
}
