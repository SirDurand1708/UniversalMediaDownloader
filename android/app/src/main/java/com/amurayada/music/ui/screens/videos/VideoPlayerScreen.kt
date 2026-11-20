package com.amurayada.music.ui.screens.videos

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.omnivault.media.model.DownloadItem
import kotlinx.coroutines.delay
import java.io.File

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    videoPath: String,
    videoTitle: String,
    queue: List<DownloadItem> = emptyList(),
    onBackClick: () -> Unit,
    onPlayInBackground: (videoPath: String, videoTitle: String, currentPositionMs: Long) -> Unit = { _, _, _ -> }
) {
    val context = LocalContext.current
    var activeIndex by remember(videoPath, queue) {
        mutableIntStateOf(
            queue.indexOfFirst { (it.localFilePath ?: it.url) == videoPath }.coerceAtLeast(0)
        )
    }
    val currentQueueItem = queue.getOrNull(activeIndex)
    val activePath = currentQueueItem?.let { it.localFilePath ?: it.url } ?: videoPath
    val activeTitle = currentQueueItem?.title ?: videoTitle
    var isPlaying by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var isLocked by remember { mutableStateOf(false) }
    var showUnlockOverlay by remember { mutableStateOf(false) }
    var gestureHudText by remember { mutableStateOf<String?>(null) }

    val activity = context as? Activity
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    val density = LocalDensity.current
    val edgeDeadZonePx = with(density) { 32.dp.toPx() }

    LaunchedEffect(gestureHudText) {
        if (gestureHudText != null) {
            delay(850)
            gestureHudText = null
        }
    }

    LaunchedEffect(showUnlockOverlay, isLocked) {
        if (showUnlockOverlay && isLocked) {
            delay(2500)
            showUnlockOverlay = false
        }
    }

    val exoPlayer = remember {
        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .build()

        ExoPlayer.Builder(context)
            .setAudioAttributes(audioAttributes, true)
            .build().apply {
                val uri = if (activePath.startsWith("http://") || activePath.startsWith("https://") || activePath.startsWith("content://")) {
                    Uri.parse(activePath)
                } else {
                    Uri.fromFile(File(activePath))
                }
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
                playWhenReady = true
            }
    }

    val playAtIndex: (Int) -> Unit = { targetIndex ->
        val item = queue.getOrNull(targetIndex)
        if (item != null) {
            activeIndex = targetIndex
            val nextPath = item.localFilePath ?: item.url
            val uri = if (nextPath.startsWith("http://") || nextPath.startsWith("https://") || nextPath.startsWith("content://")) {
                Uri.parse(nextPath)
            } else {
                Uri.fromFile(File(nextPath))
            }
            exoPlayer.setMediaItem(MediaItem.fromUri(uri))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            currentPosition = 0L
        }
    }

    DisposableEffect(queue) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    duration = exoPlayer.duration.coerceAtLeast(0L)
                } else if (playbackState == Player.STATE_ENDED && queue.size > 1 && activeIndex + 1 < queue.size) {
                    playAtIndex(activeIndex + 1)
                }
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
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
            .pointerInput(isLocked) {
                detectTapGestures(
                    onTap = {
                        if (isLocked) {
                            showUnlockOverlay = !showUnlockOverlay
                        } else {
                            controlsVisible = !controlsVisible
                        }
                    },
                    onDoubleTap = { offset ->
                        if (!isLocked) {
                            val isRight = offset.x > size.width / 2f
                            val delta = if (isRight) 10000L else -10000L
                            val maxDur = exoPlayer.duration.coerceAtLeast(0L)
                            val target = (exoPlayer.currentPosition + delta).coerceIn(0L, if (maxDur > 0L) maxDur else Long.MAX_VALUE)
                            exoPlayer.seekTo(target)
                            currentPosition = target
                            gestureHudText = if (isRight) "+10 s" else "-10 s"
                        }
                    }
                )
            }
            .pointerInput(isLocked) {
                if (isLocked) return@pointerInput
                var activeSide: Int = 0 // -1 = left (brightness), 1 = right (volume), 0 = ignored
                var dragAccumulator = 0f
                detectVerticalDragGestures(
                    onDragStart = { startOffset ->
                        dragAccumulator = 0f
                        activeSide = when {
                            startOffset.x < edgeDeadZonePx || startOffset.x > size.width - edgeDeadZonePx -> 0
                            startOffset.x < size.width / 2f -> -1
                            else -> 1
                        }
                    },
                    onVerticalDrag = { _, dragAmount ->
                        if (activeSide == 0) return@detectVerticalDragGestures
                        dragAccumulator -= dragAmount
                        val stepPx = size.height / 24f
                        if (kotlin.math.abs(dragAccumulator) >= stepPx) {
                            val steps = (dragAccumulator / stepPx).toInt()
                            dragAccumulator -= steps * stepPx
                            if (activeSide == -1 && activity != null) {
                                val lp = activity.window.attributes
                                val cur = if (lp.screenBrightness < 0f) 0.5f else lp.screenBrightness
                                val next = (cur + steps * 0.05f).coerceIn(0.05f, 1.0f)
                                lp.screenBrightness = next
                                activity.window.attributes = lp
                                gestureHudText = "Brillo ${(next * 100).toInt()}%"
                            } else if (activeSide == 1 && audioManager != null) {
                                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                val nextVol = (curVol + steps).coerceIn(0, maxVol)
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, nextVol, 0)
                                val pct = (nextVol * 100) / maxVol
                                gestureHudText = "Volumen $pct%"
                            }
                        }
                    }
                )
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
            visible = controlsVisible && !isLocked,
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
                        text = activeTitle,
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
                            onPlayInBackground(activePath, activeTitle, pos)
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
                    IconButton(
                        onClick = {
                            isLocked = true
                            controlsVisible = false
                            showUnlockOverlay = true
                        }
                    ) {
                        Icon(
                            Icons.Rounded.Lock,
                            contentDescription = "Bloquear pantalla",
                            tint = Color.White
                        )
                    }
                }

                // Center Play/Pause & Seek Buttons
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (queue.size > 1) 16.dp else 32.dp)
                ) {
                    if (queue.size > 1) {
                        IconButton(
                            onClick = { if (activeIndex > 0) playAtIndex(activeIndex - 1) },
                            enabled = activeIndex > 0,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                Icons.Rounded.SkipPrevious,
                                contentDescription = "Video anterior",
                                tint = if (activeIndex > 0) Color.White else Color.White.copy(alpha = 0.35f),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
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
                    if (queue.size > 1) {
                        IconButton(
                            onClick = { if (activeIndex + 1 < queue.size) playAtIndex(activeIndex + 1) },
                            enabled = activeIndex + 1 < queue.size,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                Icons.Rounded.SkipNext,
                                contentDescription = "Siguiente video",
                                tint = if (activeIndex + 1 < queue.size) Color.White else Color.White.copy(alpha = 0.35f),
                                modifier = Modifier.size(32.dp)
                            )
                        }
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

        // HUD temporal para gestos (-10s / +10s / Brillo / Volumen)
        AnimatedVisibility(
            visible = gestureHudText != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.72f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = gestureHudText ?: "",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                )
            }
        }

        // Botón flotante para desbloquear pantalla cuando está en modo Candado
        AnimatedVisibility(
            visible = isLocked && showUnlockOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp)
        ) {
            FilledTonalButton(
                onClick = {
                    isLocked = false
                    showUnlockOverlay = false
                    controlsVisible = true
                },
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.75f),
                    contentColor = Color.White
                )
            ) {
                Icon(Icons.Rounded.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Desbloquear")
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
