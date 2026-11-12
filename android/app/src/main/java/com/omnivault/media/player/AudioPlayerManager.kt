package com.omnivault.media.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.io.File

data class NowPlayingTrack(
    val filePath: String,
    val title: String,
    val artist: String,
    val coverPath: String? = null
)

object AudioPlayerManager {
    private const val TAG = "AudioPlayerManager"

    private var appContext: Context? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private var currentTrack: NowPlayingTrack? = null
    private val playlistQueue = mutableListOf<NowPlayingTrack>()
    private var currentQueueIndex = -1

    private var currentVolumePercent = 100 // 0 a 300%
    private var isShuffleEnabled = false
    private var repeatMode = Player.REPEAT_MODE_OFF // OFF, ALL, ONE

    private val handler = Handler(Looper.getMainLooper())

    var onStateChangedListener: ((isPlaying: Boolean, track: NowPlayingTrack?) -> Unit)? = null
    var onProgressListener: ((currentMs: Int, totalMs: Int) -> Unit)? = null
    var onTrackChangedListener: ((track: NowPlayingTrack?) -> Unit)? = null
    var onVolumeBoostChangedListener: ((percentage: Int) -> Unit)? = null

    private val progressRunnable = object : Runnable {
        override fun run() {
            val player = controller ?: PlaybackService.instance?.getPlayer()
            if (player != null && player.isPlaying) {
                val current = player.currentPosition.toInt()
                val total = player.duration.toInt().coerceAtLeast(0)
                onProgressListener?.invoke(current, total)
                handler.postDelayed(this, 500)
            }
        }
    }

    /**
     * Inicializa la conexión con el MediaSessionService de Media3
     */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext

        ensureServiceStarted(context)

        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                controller = controllerFuture?.get()
                setupPlayerListener(controller)
                Log.i(TAG, "MediaController conectado exitosamente con Media3 PlaybackService.")
            } catch (e: Exception) {
                Log.e(TAG, "Error conectando MediaController: ${e.message}")
            }
        }, MoreExecutors.directExecutor())
    }

    private fun ensureServiceStarted(context: Context) {
        val intent = Intent(context, PlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun setupPlayerListener(player: Player?) {
        player?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    startProgressTracking()
                } else {
                    stopProgressTracking()
                }
                onStateChangedListener?.invoke(isPlaying, currentTrack)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    playNextTrack()
                }
            }
        })
    }

    fun isPlaying(): Boolean {
        val player = controller ?: PlaybackService.instance?.getPlayer()
        return player?.isPlaying == true
    }

    fun getCurrentTrack(): NowPlayingTrack? = currentTrack

    fun getVolumePercent(): Int = currentVolumePercent

    /**
     * Reproduce una pista individual
     */
    @OptIn(UnstableApi::class)
    fun playTrack(track: NowPlayingTrack) {
        val file = File(track.filePath)
        if (!file.exists()) {
            Log.e(TAG, "El archivo no existe: ${track.filePath}")
            return
        }

        appContext?.let { ensureServiceStarted(it) }

        val player = controller ?: PlaybackService.instance?.getPlayer()
        if (player == null) {
            // Si el controlador aún no conecta, intentar reintentar en 200ms
            handler.postDelayed({ playTrack(track) }, 200)
            return
        }

        try {
            val metadata = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .apply {
                    if (!track.coverPath.isNullOrBlank()) {
                        setArtworkUri(Uri.parse(track.coverPath))
                    }
                }
                .build()

            val mediaItem = MediaItem.Builder()
                .setUri(Uri.fromFile(file))
                .setMediaMetadata(metadata)
                .build()

            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()

            currentTrack = track
            if (!playlistQueue.contains(track)) {
                playlistQueue.add(0, track)
                currentQueueIndex = 0
            } else {
                currentQueueIndex = playlistQueue.indexOf(track)
            }

            // Aplicar volumen actual y ganancia de Super Volumen 300%
            applySuperVolume(currentVolumePercent)

            startProgressTracking()
            onTrackChangedListener?.invoke(track)
            onStateChangedListener?.invoke(true, track)
            Log.i(TAG, "Reproduciendo pista en Media3: ${track.title}")
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando reproducción con Media3: ${e.message}", e)
        }
    }

    /**
     * Carga una lista completa de canciones (Lark Player Playlist Queue) y reproduce el índice seleccionado
     */
    fun setPlaylist(tracks: List<NowPlayingTrack>, startIndex: Int = 0) {
        playlistQueue.clear()
        playlistQueue.addAll(tracks)
        if (tracks.isNotEmpty()) {
            val index = startIndex.coerceIn(0, tracks.size - 1)
            currentQueueIndex = index
            playTrack(tracks[index])
        }
    }

    fun togglePlayPause() {
        val player = controller ?: PlaybackService.instance?.getPlayer() ?: return
        if (player.isPlaying) {
            player.pause()
            stopProgressTracking()
            onStateChangedListener?.invoke(false, currentTrack)
        } else {
            player.play()
            startProgressTracking()
            onStateChangedListener?.invoke(true, currentTrack)
        }
    }

    fun playNextTrack() {
        if (playlistQueue.isEmpty()) return
        if (isShuffleEnabled) {
            currentQueueIndex = (0 until playlistQueue.size).random()
        } else {
            currentQueueIndex = (currentQueueIndex + 1) % playlistQueue.size
        }
        playTrack(playlistQueue[currentQueueIndex])
    }

    fun playPreviousTrack() {
        if (playlistQueue.isEmpty()) return
        currentQueueIndex = if (currentQueueIndex - 1 < 0) playlistQueue.size - 1 else currentQueueIndex - 1
        playTrack(playlistQueue[currentQueueIndex])
    }

    fun seekTo(positionMs: Int) {
        val player = controller ?: PlaybackService.instance?.getPlayer()
        player?.seekTo(positionMs.toLong())
    }

    fun getDuration(): Int {
        val player = controller ?: PlaybackService.instance?.getPlayer()
        return player?.duration?.toInt()?.coerceAtLeast(0) ?: 0
    }

    fun getCurrentPosition(): Int {
        val player = controller ?: PlaybackService.instance?.getPlayer()
        return player?.currentPosition?.toInt()?.coerceAtLeast(0) ?: 0
    }

    /**
     * Ajuste de Super Volumen (0% a 300%) con LoudnessEnhancer
     */
    fun setVolumeBoost(percentage: Int) {
        currentVolumePercent = percentage.coerceIn(0, 300)
        applySuperVolume(currentVolumePercent)
        onVolumeBoostChangedListener?.invoke(currentVolumePercent)
    }

    private fun applySuperVolume(percentage: Int) {
        PlaybackService.instance?.setSuperVolume(percentage)
    }

    fun setBassBoost(strength: Short) { // 0 a 1000
        PlaybackService.instance?.setBassBoost(strength)
    }

    fun setVirtualizer(strength: Short) { // 0 a 1000
        PlaybackService.instance?.setVirtualizer(strength)
    }

    fun setEqualizerBand(band: Short, level: Short) {
        PlaybackService.instance?.setEqualizerBand(band, level)
    }

    fun toggleShuffle(): Boolean {
        isShuffleEnabled = !isShuffleEnabled
        return isShuffleEnabled
    }

    fun toggleRepeat(): Int {
        repeatMode = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        val player = controller ?: PlaybackService.instance?.getPlayer()
        player?.repeatMode = repeatMode
        return repeatMode
    }

    private fun startProgressTracking() {
        stopProgressTracking()
        handler.post(progressRunnable)
    }

    private fun stopProgressTracking() {
        handler.removeCallbacks(progressRunnable)
    }

    fun stop() {
        stopProgressTracking()
        val player = controller ?: PlaybackService.instance?.getPlayer()
        player?.stop()
        onStateChangedListener?.invoke(false, currentTrack)
    }
}
