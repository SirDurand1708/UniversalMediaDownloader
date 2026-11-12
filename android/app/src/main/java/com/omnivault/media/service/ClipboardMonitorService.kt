package com.omnivault.media.service

import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.amurayada.music.R
import com.omnivault.media.utils.NotificationHelper
import com.omnivault.media.utils.UrlUtils

class ClipboardMonitorService : Service(), ClipboardManager.OnPrimaryClipChangedListener {

    private var clipboardManager: ClipboardManager? = null
    private var lastHandledUrl: String? = null

    companion object {
        const val ACTION_START_MONITORING = "com.omnivault.media.START_MONITORING"
        const val ACTION_STOP_MONITORING = "com.omnivault.media.STOP_MONITORING"
        private const val FOREGROUND_NOTIFICATION_ID = 9002

        fun startService(context: Context) {
            val intent = Intent(context, ClipboardMonitorService::class.java).apply {
                action = ACTION_START_MONITORING
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ClipboardMonitorService::class.java).apply {
                action = ACTION_STOP_MONITORING
            }
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener(this)
        startForegroundServiceNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_MONITORING) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startForegroundServiceNotification() {
        NotificationHelper.createNotificationChannels(this)

        val notification = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_DOWNLOADS_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("OmniVault Activo")
            .setContentText("Supervisando enlaces copiados estilo Snaptube...")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

        startForeground(FOREGROUND_NOTIFICATION_ID, notification)
    }

    override fun onPrimaryClipChanged() {
        try {
            val clip = clipboardManager?.primaryClip ?: return
            if (clip.itemCount == 0) return

            val item = clip.getItemAt(0)
            val text = item.text?.toString() ?: return

            val extractedUrl = UrlUtils.extractUrlFromText(text) ?: return

            // Comprobar si es un enlace multimedia soportado y que no sea repetido
            if (UrlUtils.isSupportedMediaUrl(extractedUrl) && extractedUrl != lastHandledUrl) {
                lastHandledUrl = extractedUrl
                NotificationHelper.showClipboardDetectedNotification(this, extractedUrl)
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        clipboardManager?.removePrimaryClipChangedListener(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
