package com.omnivault.media.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.omnivault.media.R
import com.omnivault.media.model.DownloadItem
import com.omnivault.media.model.MediaFormat
import com.omnivault.media.service.MediaDownloadService
import com.omnivault.media.ui.MainActivity
import com.omnivault.media.ui.QuickShareActivity

object NotificationHelper {
    const val CHANNEL_DOWNLOADS_ID = "omnivault_downloads"
    const val CHANNEL_CLIPBOARD_ID = "omnivault_clipboard"
    private const val CLIPBOARD_NOTIFICATION_ID = 9001

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val downloadChannel = NotificationChannel(
                CHANNEL_DOWNLOADS_ID,
                "Descargas OmniVault",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progreso de descargas de medios en curso"
                setShowBadge(false)
            }

            val clipboardChannel = NotificationChannel(
                CHANNEL_CLIPBOARD_ID,
                "Detección de Enlaces (Estilo Snaptube)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Avisos rápidos cuando copias un enlace multimedia"
                enableVibration(true)
                setShowBadge(true)
            }

            manager.createNotificationChannel(downloadChannel)
            manager.createNotificationChannel(clipboardChannel)
        }
    }

    /**
     * Muestra la notificación estilo Snaptube cuando el usuario copia un enlace.
     */
    fun showClipboardDetectedNotification(context: Context, detectedUrl: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Intent para abrir QuickShareActivity al tocar la notificación
        val openIntent = Intent(context, QuickShareActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, detectedUrl)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            101,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Acción rápida: Descargar MP3 directo
        val mp3Intent = Intent(context, MediaDownloadService::class.java).apply {
            action = MediaDownloadService.ACTION_START_DOWNLOAD
            putExtra(MediaDownloadService.EXTRA_URL, detectedUrl)
            putExtra(MediaDownloadService.EXTRA_FORMAT, MediaFormat.MP3_320.name)
        }
        val mp3PendingIntent = PendingIntent.getService(
            context,
            102,
            mp3Intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Acción rápida: Descargar MP4 directo
        val mp4Intent = Intent(context, MediaDownloadService::class.java).apply {
            action = MediaDownloadService.ACTION_START_DOWNLOAD
            putExtra(MediaDownloadService.EXTRA_URL, detectedUrl)
            putExtra(MediaDownloadService.EXTRA_FORMAT, MediaFormat.MP4_1080.name)
        }
        val mp4PendingIntent = PendingIntent.getService(
            context,
            103,
            mp4Intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val platform = com.omnivault.media.model.PlatformType.fromUrl(detectedUrl)

        val builder = NotificationCompat.Builder(context, CHANNEL_CLIPBOARD_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("✦ OmniVault: [${platform.displayName}]")
            .setContentText("Enlace detectado. ¿Deseas descargarlo ahora?")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Enlace: $detectedUrl\nSelecciona el formato para descargarlo al instante."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .addAction(0, "🎵 Descargar MP3", mp3PendingIntent)
            .addAction(0, "🎬 Descargar MP4", mp4PendingIntent)

        manager.notify(CLIPBOARD_NOTIFICATION_ID, builder.build())
    }

    /**
     * Construye la notificación de progreso de descarga activa.
     */
    fun buildProgressNotification(context: Context, item: DownloadItem): NotificationCompat.Builder {
        val cancelIntent = Intent(context, MediaDownloadService::class.java).apply {
            action = MediaDownloadService.ACTION_CANCEL_DOWNLOAD
            putExtra(MediaDownloadService.EXTRA_TASK_ID, item.id)
        }
        val cancelPendingIntent = PendingIntent.getService(
            context,
            item.id.hashCode(),
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_DOWNLOADS_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("OmniVault: ${item.title}")
            .setContentText("${item.speedText} • ${item.progress}%")
            .setProgress(100, item.progress, item.progress == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Cancelar", cancelPendingIntent)
    }
}
