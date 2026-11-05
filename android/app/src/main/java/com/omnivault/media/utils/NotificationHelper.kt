package com.omnivault.media.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.omnivault.media.R
import com.omnivault.media.model.DownloadItem
import com.omnivault.media.model.MediaFormat
import com.omnivault.media.service.MediaDownloadService
import com.omnivault.media.ui.MainActivity
import com.omnivault.media.ui.QuickShareActivity
import java.io.File

object NotificationHelper {
    const val CHANNEL_DOWNLOADS_ID = "omnivault_downloads"
    const val CHANNEL_COMPLETED_ID = "omnivault_completed"
    const val CHANNEL_CLIPBOARD_ID = "omnivault_clipboard"
    const val CHANNEL_UPDATES_ID = "omnivault_updates"

    private const val CLIPBOARD_NOTIFICATION_ID = 9001
    private const val UPDATE_NOTIFICATION_ID = 9002
    const val NOTIFICATION_ID_BASE = 5000

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Canal 1: Progreso de descargas (silencioso para no molestar continuamente)
            val downloadChannel = NotificationChannel(
                CHANNEL_DOWNLOADS_ID,
                "Descargas en Curso",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progreso en tiempo real de descargas activas"
                setShowBadge(false)
            }

            // Canal 2: Descargas completadas (con sonido y vibración)
            val completedChannel = NotificationChannel(
                CHANNEL_COMPLETED_ID,
                "Descargas Completadas",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones de archivos descargados con éxito"
                enableVibration(true)
                setShowBadge(true)
            }

            // Canal 3: Detección estilo Snaptube
            val clipboardChannel = NotificationChannel(
                CHANNEL_CLIPBOARD_ID,
                "Detección de Enlaces (Estilo Snaptube)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Avisos rápidos cuando copias un enlace multimedia"
                enableVibration(true)
                setShowBadge(true)
            }

            // Canal 4: Actualizaciones oficiales del sistema
            val updatesChannel = NotificationChannel(
                CHANNEL_UPDATES_ID,
                "Actualizaciones de OmniVault",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones de nuevas versiones disponibles"
                enableVibration(true)
                setShowBadge(true)
            }

            manager.createNotificationChannel(downloadChannel)
            manager.createNotificationChannel(completedChannel)
            manager.createNotificationChannel(clipboardChannel)
            manager.createNotificationChannel(updatesChannel)
        }
    }

    /**
     * Muestra la notificación interactiva estilo Snaptube cuando se copia un enlace.
     */
    fun showClipboardDetectedNotification(context: Context, detectedUrl: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

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

        // Acción: MP3
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

        // Acción: MP4
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
            .setStyle(NotificationCompat.BigTextStyle().bigText("Enlace: $detectedUrl\nElige el formato para iniciar la descarga de inmediato."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .addAction(0, "🎵 Descargar MP3", mp3PendingIntent)
            .addAction(0, "🎬 Descargar MP4", mp4PendingIntent)

        manager.notify(CLIPBOARD_NOTIFICATION_ID, builder.build())
    }

    /**
     * Construye la notificación de progreso en tiempo real.
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

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            201,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val progressText = if (item.progress > 0) "${item.progress}% (${item.speedText})" else "Iniciando descarga..."

        return NotificationCompat.Builder(context, CHANNEL_DOWNLOADS_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Descargando: ${item.title}")
            .setContentText(progressText)
            .setProgress(100, item.progress, item.progress <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(0, "Cancelar", cancelPendingIntent)
    }

    /**
     * Muestra la notificación de descarga completada con botones de Reproducir y Compartir.
     */
    fun showCompletedNotification(context: Context, item: DownloadItem) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifId = NOTIFICATION_ID_BASE + Math.abs(item.id.hashCode() % 1000)

        val localPath = item.localFilePath
        val file = if (localPath != null) File(localPath) else null

        val builder = NotificationCompat.Builder(context, CHANNEL_COMPLETED_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("✅ Descarga Completada")
            .setContentText("${item.title} (${item.format.extension.uppercase()} • ${item.fileSizeFormatted})")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Archivo guardado con éxito:\n${item.title}\nFormato: ${item.format.extension.uppercase()} • Tamaño: ${item.fileSizeFormatted}"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        if (file != null && file.exists()) {
            try {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

                // Acción al pulsar la notificación: Reproducir
                val playIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, item.format.mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val playPendingIntent = PendingIntent.getActivity(
                    context,
                    notifId + 1,
                    playIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.setContentIntent(playPendingIntent)
                builder.addAction(0, "▶ Reproducir", playPendingIntent)

                // Acción de Compartir
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = item.format.mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val shareChooser = Intent.createChooser(shareIntent, "Compartir ${item.title}")
                val sharePendingIntent = PendingIntent.getActivity(
                    context,
                    notifId + 2,
                    shareChooser,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(0, "↗ Compartir", sharePendingIntent)

            } catch (_: Exception) {}
        }

        manager.notify(notifId, builder.build())
    }

    /**
     * Muestra la notificación de error en caso de fallo.
     */
    fun showErrorNotification(context: Context, item: DownloadItem) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifId = NOTIFICATION_ID_BASE + Math.abs(item.id.hashCode() % 1000)

        val builder = NotificationCompat.Builder(context, CHANNEL_COMPLETED_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("❌ Error al descargar")
            .setContentText("${item.title}: ${item.errorReason ?: "Fallo de conexión"}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("No se pudo completar la descarga de:\n${item.title}\nMotivo: ${item.errorReason ?: "Error de red"}"))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        manager.notify(notifId, builder.build())
    }

    /**
     * Notificación cuando hay una nueva versión oficial de OmniVault disponible.
     */
    fun showUpdateNotification(context: Context, version: String, apkUrl: String, changelog: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("trigger_update", true)
            putExtra("update_version", version)
            putExtra("update_url", apkUrl)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            UPDATE_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_UPDATES_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("🚀 Actualización disponible: OmniVault v$version")
            .setContentText("Toca aquí para actualizar automáticamente con 1 solo toque.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("✦ Nueva versión disponible: v$version\n\n$changelog\n\nToca para descargar e instalar automáticamente."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        manager.notify(UPDATE_NOTIFICATION_ID, builder.build())
    }

    /**
     * Progreso de la descarga de actualización.
     */
    fun showUpdateProgressNotification(context: Context, progress: Int, speed: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val builder = NotificationCompat.Builder(context, CHANNEL_UPDATES_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("⚡ Descargando actualización OmniVault...")
            .setContentText("$progress% completado • $speed")
            .setProgress(100, progress, progress == 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        manager.notify(UPDATE_NOTIFICATION_ID, builder.build())
    }

    fun cancelNotification(context: Context, notifId: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(notifId)
    }
}
