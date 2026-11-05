package com.omnivault.media.player

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

data class NowPlayingTrack(
    val filePath: String,
    val title: String,
    val artist: String,
    val coverPath: String? = null
)

object AudioPlayerManager {
    private const val TAG = "AudioPlayerManager"

    private var mediaPlayer: MediaPlayer? = null
    private var currentTrack: NowPlayingTrack? = null
    private val handler = Handler(Looper.getMainLooper())

    var onStateChangedListener: ((isPlaying: Boolean, track: NowPlayingTrack?) -> Unit)? = null
    var onProgressListener: ((currentMs: Int, totalMs: Int) -> Unit)? = null

    private val progressRunnable = object : Runnable {
        override fun run() {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    val current = player.currentPosition
                    val total = player.duration
                    onProgressListener?.invoke(current, total)
                    handler.postDelayed(this, 500)
                }
            }
        }
    }

    fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true

    fun getCurrentTrack(): NowPlayingTrack? = currentTrack

    fun playTrack(track: NowPlayingTrack) {
        val file = File(track.filePath)
        if (!file.exists()) {
            Log.e(TAG, "El archivo de audio no existe: ${track.filePath}")
            return
        }

        try {
            stop()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(track.filePath)
                prepare()
                start()
                setOnCompletionListener {
                    stopProgressTracking()
                    onStateChangedListener?.invoke(false, currentTrack)
                }
            }
            currentTrack = track
            startProgressTracking()
            onStateChangedListener?.invoke(true, track)
            Log.i(TAG, "Reproduciendo: ${track.title}")
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando reproducción de ${track.title}", e)
        }
    }

    fun togglePlayPause() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
                stopProgressTracking()
                onStateChangedListener?.invoke(false, currentTrack)
            } else {
                player.start()
                startProgressTracking()
                onStateChangedListener?.invoke(true, currentTrack)
            }
        }
    }

    fun seekTo(positionMs: Int) {
        mediaPlayer?.seekTo(positionMs)
    }

    fun getDuration(): Int = mediaPlayer?.duration ?: 0

    fun getCurrentPosition(): Int = mediaPlayer?.currentPosition ?: 0

    fun stop() {
        stopProgressTracking()
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        onStateChangedListener?.invoke(false, currentTrack)
    }

    private fun startProgressTracking() {
        stopProgressTracking()
        handler.post(progressRunnable)
    }

    private fun stopProgressTracking() {
        handler.removeCallbacks(progressRunnable)
    }
}
