package com.omnivault.media.player

import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.omnivault.media.ui.MainActivity

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var exoPlayer: ExoPlayer? = null

    // Efectos de Sonido por Hardware (Android AudioFX)
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null

    companion object {
        private const val TAG = "PlaybackService"
        var instance: PlaybackService? = null
            private set
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        instance = this

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true) // Gestión automática de AudioFocus
            .setHandleAudioBecomingNoisy(true) // Pausa automática si se desconectan los auriculares
            .build()

        exoPlayer = player

        // Intent para abrir MainActivity al tocar la notificación de medios
        val sessionIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .build()

        // Vincular AudioFX cuando el reproductor inicialice el audioSessionId
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                initAudioFx(audioSessionId)
            }
        })

        // Inicializar inmediatamente si ya está disponible el session ID
        if (player.audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
            initAudioFx(player.audioSessionId)
        }

        Log.i(TAG, "Media3 PlaybackService iniciado con éxito.")
    }

    private fun initAudioFx(audioSessionId: Int) {
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) return

        try {
            // 1. LoudnessEnhancer para Super Volumen hasta 300% con limitador anti-distorsión
            loudnessEnhancer?.release()
            loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply {
                enabled = true
            }

            // 2. Ecualizador de 5 bandas nativo
            equalizer?.release()
            equalizer = Equalizer(0, audioSessionId).apply {
                enabled = true
            }

            // 3. Refuerzo de graves (Bass Boost)
            bassBoost?.release()
            bassBoost = BassBoost(0, audioSessionId).apply {
                enabled = true
            }

            // 4. Sonido Envolvente 3D (Virtualizer)
            virtualizer?.release()
            virtualizer = Virtualizer(0, audioSessionId).apply {
                enabled = true
            }

            Log.i(TAG, "AudioFX (LoudnessEnhancer 300%, EQ, BassBoost, Virtualizer) inicializado para session $audioSessionId")
        } catch (e: Exception) {
            Log.e(TAG, "Error inicializando AudioFX: ${e.message}")
        }
    }

    /**
     * Aplica el Super Volumen estilo Lark Player:
     * - De 0 a 100%: volumen normal del reproductor.
     * - De 101 a 300%: mantiene volumen al 100% y activa ganancia en milidecibelios (mB)
     *   con el limitador por hardware para evitar la saturación.
     */
    fun setSuperVolume(percentage: Int) {
        val player = exoPlayer ?: return
        if (percentage <= 100) {
            player.volume = (percentage.coerceIn(0, 100) / 100f)
            loudnessEnhancer?.setTargetGain(0)
        } else {
            player.volume = 1.0f
            val extra = (percentage - 100).coerceIn(1, 200)
            // Cada 10% extra equivale a ~150 mB (hasta 3000 mB = +30dB de ganancia)
            val gainInMb = extra * 15
            try {
                loudnessEnhancer?.setTargetGain(gainInMb)
            } catch (e: Exception) {
                Log.w(TAG, "Error ajustando ganancia de volumen: ${e.message}")
            }
        }
    }

    fun setBassBoost(strength: Short) { // 0 a 1000
        try {
            bassBoost?.setStrength(strength)
        } catch (e: Exception) {
            Log.w(TAG, "Error ajustando BassBoost", e)
        }
    }

    fun setVirtualizer(strength: Short) { // 0 a 1000
        try {
            virtualizer?.setStrength(strength)
        } catch (e: Exception) {
            Log.w(TAG, "Error ajustando Virtualizer", e)
        }
    }

    fun setEqualizerBand(band: Short, level: Short) {
        try {
            equalizer?.setBandLevel(band, level)
        } catch (e: Exception) {
            Log.w(TAG, "Error ajustando banda de ecualizador", e)
        }
    }

    fun getEqualizer(): Equalizer? = equalizer

    fun getPlayer(): ExoPlayer? = exoPlayer

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        try {
            loudnessEnhancer?.release()
            equalizer?.release()
            bassBoost?.release()
            virtualizer?.release()
            mediaSession?.run {
                player.release()
                release()
                mediaSession = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al liberar recursos de PlaybackService", e)
        }
    }
}
