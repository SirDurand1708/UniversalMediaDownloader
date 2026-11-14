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
import com.amurayada.music.R
import com.omnivault.media.model.DownloadItem
import com.omnivault.media.model.MediaFormat
import com.omnivault.media.service.MediaDownloadService
import com.amurayada.music.MainActivity
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
                setSound(null, null)
                enableVibration(false)
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

    // =========================================================================
    // NOTIFICACIONES DE FOREGROUND SERVICE (segundo plano persistente)
    // =========================================================================

    /**
     * Notificación mínima inicial que el Foreground Service publica inmediatamente al crearse.
     * Requerida por Android 8+ antes de cualquier trabajo asíncrono.
     */
    fun buildForegroundServiceNotification(context: Context): NotificationCompat.Builder {
        createNotificationChannels(context)
        val openPendingIntent = openMainActivityPendingIntent(context, 300)
        return NotificationCompat.Builder(context, CHANNEL_DOWNLOADS_ID)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle("Aura Player")
            .setContentText("Listo para descargar…")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openPendingIntent)
    }

    /**
     * Notificación del Foreground Service mostrando el progreso de la descarga activa.
     * Se actualiza continuamente vía startForeground() mientras el servicio esté vivo.
     *
     * @param title         Título del contenido que se está descargando
     * @param progress      Porcentaje 0-100
     * @param speed         Texto de velocidad, ej. "1.4 MB/s"
     * @param indeterminate true cuando no se conoce el tamaño total (anima infinitamente)
     */
    fun buildForegroundProgressNotification(
        context: Context,
        title: String,
        progress: Int,
        speed: String,
        indeterminate: Boolean = false
    ): NotificationCompat.Builder {
        createNotificationChannels(context)
        val openPendingIntent = openMainActivityPendingIntent(context, 301)

        val progressText = when {
            indeterminate -> "Procesando…"
            progress > 0  -> "$progress%  •  $speed"
            else          -> "Iniciando descarga…"
        }

        return NotificationCompat.Builder(context, CHANNEL_DOWNLOADS_ID)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle("⬇ $title")
            .setContentText(progressText)
            .setProgress(100, progress, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openPendingIntent)
    }

    // =========================================================================
    // NOTIFICACIÓN INDIVIDUAL POR DESCARGA (con botón Cancelar)
    // =========================================================================

    /**
     * Construye la notificación de progreso individual en tiempo real.
     * Cada descarga tiene su propia notificación con ID único.
     */
    fun buildProgressNotification(context: Context, item: DownloadItem): NotificationCompat.Builder {
        createNotificationChannels(context)
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

        val progressText = when {
            item.status.name == "FETCHING_METADATA" -> "Analizando enlace…"
            item.status.name == "PROCESSING"        -> "Procesando audio y metadatos…"
            item.progress > 0 -> "${item.progress}%  •  ${item.speedText}"
            else -> "Iniciando descarga…"
        }

        return NotificationCompat.Builder(context, CHANNEL_DOWNLOADS_ID)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle(item.title.ifBlank { "Descargando…" })
            .setContentText(progressText)
            .setProgress(100, item.progress, item.progress <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openMainActivityPendingIntent(context, 201))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancelar", cancelPendingIntent)
    }

    // =========================================================================
    // NOTIFICACIÓN DE COMPLETADO
    // =========================================================================

    /**
     * Muestra la notificación de descarga completada con botones de Reproducir y Compartir.
     */
    fun showCompletedNotification(context: Context, item: DownloadItem) {
        createNotificationChannels(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifId = NOTIFICATION_ID_BASE + Math.abs(item.id.hashCode() % 1000)

        val localPath = item.localFilePath
        val mediaUri: Uri? = when {
            localPath.isNullOrBlank() -> null
            localPath.startsWith("content://") -> Uri.parse(localPath)
            else -> {
                val file = File(localPath)
                if (file.exists()) {
                    try {
                        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    } catch (_: Exception) {
                        null
                    }
                } else null
            }
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_COMPLETED_ID)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle("✅ Descarga completada")
            .setContentText("${item.title} (${item.format.extension.uppercase()}${if (item.fileSizeFormatted.isNotBlank()) " • ${item.fileSizeFormatted}" else ""})")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Archivo guardado con éxito:\n${item.title}\n" +
                    "Formato: ${item.format.extension.uppercase()}" +
                    if (item.fileSizeFormatted.isNotBlank()) " • Tamaño: ${item.fileSizeFormatted}" else ""
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        if (mediaUri != null) {
            try {
                // Toque sobre la notificación → Reproducir
                val playIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(mediaUri, item.format.mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val playPendingIntent = PendingIntent.getActivity(
                    context, notifId + 1, playIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.setContentIntent(playPendingIntent)
                builder.addAction(android.R.drawable.ic_media_play, "▶ Reproducir", playPendingIntent)

                // Acción de Compartir
                val shareIntent = Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = item.format.mimeType
                        putExtra(Intent.EXTRA_STREAM, mediaUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "Compartir ${item.title}"
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                val sharePendingIntent = PendingIntent.getActivity(
                    context, notifId + 2, shareIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(android.R.drawable.ic_menu_share, "↗ Compartir", sharePendingIntent)

            } catch (_: Exception) {}
        } else {
            // El archivo no está disponible localmente; tocar abre la app
            builder.setContentIntent(openMainActivityPendingIntent(context, notifId + 3))
        }

        manager.notify(notifId, builder.build())
    }

    // =========================================================================
    // NOTIFICACIÓN DE ERROR
    // =========================================================================

    /**
     * Muestra la notificación de error en caso de fallo.
     */
    fun showErrorNotification(context: Context, item: DownloadItem) {
        createNotificationChannels(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifId = NOTIFICATION_ID_BASE + Math.abs(item.id.hashCode() % 1000)

        val builder = NotificationCompat.Builder(context, CHANNEL_COMPLETED_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("❌ Error al descargar")
            .setContentText("${item.title.ifBlank { "Contenido" }}: ${item.errorReason ?: "Fallo de conexión"}")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "No se pudo completar la descarga de:\n${item.title}\nMotivo: ${item.errorReason ?: "Error de red o enlace no soportado"}"
                )
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openMainActivityPendingIntent(context, notifId + 4))

        manager.notify(notifId, builder.build())
    }

    // =========================================================================
    // DETECCIÓN DE PORTAPAPELES (Estilo Snaptube)
    // =========================================================================

    /**
     * Muestra la notificación interactiva estilo Snaptube cuando se copia un enlace.
     */
    fun showClipboardDetectedNotification(context: Context, detectedUrl: String) {
        createNotificationChannels(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, detectedUrl)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context, 101, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Acción: MP3
        val mp3Intent = Intent(context, MediaDownloadService::class.java).apply {
            action = MediaDownloadService.ACTION_START_DOWNLOAD
            putExtra(MediaDownloadService.EXTRA_URL, detectedUrl)
            putExtra(MediaDownloadService.EXTRA_FORMAT, MediaFormat.MP3_320.name)
        }
        val mp3PendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                context, 102, mp3Intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                context, 102, mp3Intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        // Acción: MP4
        val mp4Intent = Intent(context, MediaDownloadService::class.java).apply {
            action = MediaDownloadService.ACTION_START_DOWNLOAD
            putExtra(MediaDownloadService.EXTRA_URL, detectedUrl)
            putExtra(MediaDownloadService.EXTRA_FORMAT, MediaFormat.MP4_1080.name)
        }
        val mp4PendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                context, 103, mp4Intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                context, 103, mp4Intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val platform = com.omnivault.media.model.PlatformType.fromUrl(detectedUrl)

        val builder = NotificationCompat.Builder(context, CHANNEL_CLIPBOARD_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("✦ OmniVault: [${platform.displayName}] detectado")
            .setContentText("Enlace multimedia copiado. ¿Deseas descargarlo ahora?")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Enlace: $detectedUrl\nElige el formato para iniciar la descarga de inmediato.")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .addAction(0, "🎵 MP3 320k", mp3PendingIntent)
            .addAction(0, "🎬 MP4 1080p", mp4PendingIntent)

        manager.notify(CLIPBOARD_NOTIFICATION_ID, builder.build())
    }

    // =========================================================================
    // ACTUALIZACIONES DE LA APP
    // =========================================================================

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
            context, UPDATE_NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_UPDATES_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("🚀 Actualización disponible: OmniVault v$version")
            .setContentText("Toca aquí para actualizar automáticamente con 1 solo toque.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("✦ Nueva versión disponible: v$version\n\n$changelog\n\nToca para descargar e instalar automáticamente.")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        manager.notify(UPDATE_NOTIFICATION_ID, builder.build())
    }

    /**
     * Progreso de la descarga de actualización del APK.
     */
    fun showUpdateProgressNotification(context: Context, progress: Int, speed: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val builder = NotificationCompat.Builder(context, CHANNEL_UPDATES_ID)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle("⚡ Descargando actualización OmniVault…")
            .setContentText("$progress% completado  •  $speed")
            .setProgress(100, progress, progress == 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)

        manager.notify(UPDATE_NOTIFICATION_ID, builder.build())
    }

    // =========================================================================
    // UTILIDADES
    // =========================================================================

    fun cancelNotification(context: Context, notifId: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(notifId)
    }

    private fun openMainActivityPendingIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
