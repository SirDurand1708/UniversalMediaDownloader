package com.omnivault.media.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import com.omnivault.media.downloader.DownloadManager
import com.omnivault.media.model.DownloadItem
import com.omnivault.media.model.DownloadStatus
import com.omnivault.media.model.MediaFormat
import com.omnivault.media.utils.NotificationHelper

/**
 * Servicio en segundo plano (Foreground Service) oficial de OmniVault.
 *
 * Mantiene la descarga activa al 100% incluso cuando:
 *  - La aplicación está minimizada
 *  - El usuario usa otra aplicación
 *  - La pantalla del celular está apagada / bloqueada (vía WakeLock parcial)
 *
 * En la barra de estado y panel de notificaciones:
 *  - Muestra una barra de carga interactiva en tiempo real con porcentaje y velocidad (MB/s).
 *  - Al finalizar, emite una notificación de "Descarga completada" con sonido, vibración
 *    y botones directos para Reproducir y Compartir.
 */
class MediaDownloadService : Service() {

    companion object {
        const val ACTION_START_DOWNLOAD = "com.omnivault.media.START_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.omnivault.media.CANCEL_DOWNLOAD"

        const val EXTRA_URL = "extra_url"
        const val EXTRA_FORMAT = "extra_format"
        const val EXTRA_TASK_ID = "extra_task_id"

        const val FOREGROUND_SERVICE_NOTIF_ID = 4001
        private const val WAKELOCK_TAG = "OmniVault:MediaDownloadWakeLock"

        /** Lanza el servicio en segundo plano de forma segura para Android 8+ */
        fun startDownload(context: Context, url: String, format: MediaFormat) {
            val intent = Intent(context, MediaDownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_FORMAT, format.name)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Cancela una descarga activa */
        fun cancelDownload(context: Context, taskId: String) {
            val intent = Intent(context, MediaDownloadService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
                putExtra(EXTRA_TASK_ID, taskId)
            }
            context.startService(intent)
        }
    }

    private lateinit var downloadManager: DownloadManager
    private var wakeLock: PowerManager.WakeLock? = null

    // Listener thread-safe para recibir cambios de progreso de DownloadManager
    private val downloadListener: (DownloadItem) -> Unit = { item ->
        handleDownloadUpdate(item)
    }

    override fun onCreate() {
        super.onCreate()

        // 1. Iniciar inmediatamente como Foreground Service para cumplir con Android 8/14+
        val initialNotif = NotificationHelper.buildForegroundServiceNotification(this).build()
        promoteToForeground(initialNotif)

        // 2. Inicializar WakeLock para descargas con pantalla bloqueada
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)?.apply {
                setReferenceCounted(false)
            }
        } catch (_: Exception) {}

        // 3. Conectar al DownloadManager Singleton mediante listener seguro
        downloadManager = DownloadManager.getInstance(this)
        downloadManager.addDownloadListener(downloadListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        acquireWakeLockIfNeeded()

        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                val url = intent.getStringExtra(EXTRA_URL)
                val formatKey = intent.getStringExtra(EXTRA_FORMAT) ?: MediaFormat.MP3_320.name
                val format = MediaFormat.fromKey(formatKey)

                if (!url.isNullOrBlank()) {
                    downloadManager.enqueueDownload(url, format)
                }
            }

            ACTION_CANCEL_DOWNLOAD -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID)
                if (!taskId.isNullOrBlank()) {
                    downloadManager.cancelDownload(taskId)
                }
            }
        }

        return START_STICKY
    }

    private fun handleDownloadUpdate(item: DownloadItem) {
        val notifId = NotificationHelper.NOTIFICATION_ID_BASE + Math.abs(item.id.hashCode() % 1000)

        when (item.status) {
            DownloadStatus.FETCHING_METADATA, DownloadStatus.QUEUED -> {
                acquireWakeLockIfNeeded()
                val notif = NotificationHelper.buildForegroundProgressNotification(
                    context = this,
                    title = item.title,
                    progress = 0,
                    speed = "Conectando…",
                    indeterminate = true
                ).build()
                promoteToForeground(notif)
            }

            DownloadStatus.DOWNLOADING -> {
                acquireWakeLockIfNeeded()
                // Actualizar la notificación persistente del Foreground Service con la barra de carga
                val notif = NotificationHelper.buildForegroundProgressNotification(
                    context = this,
                    title = item.title,
                    progress = item.progress,
                    speed = item.speedText,
                    indeterminate = item.progress <= 0
                ).build()
                promoteToForeground(notif)
            }

            DownloadStatus.PROCESSING -> {
                val notif = NotificationHelper.buildForegroundProgressNotification(
                    context = this,
                    title = item.title,
                    progress = item.progress,
                    speed = item.speedText,
                    indeterminate = true
                ).build()
                promoteToForeground(notif)
            }

            DownloadStatus.COMPLETED -> {
                // Cancelar cualquier notificación previa de progreso para este item
                NotificationHelper.cancelNotification(this, notifId)

                // Emitir la notificación de descarga completada con sonido, vibración y acciones
                try {
                    NotificationHelper.showCompletedNotification(this, item)
                } catch (_: SecurityException) {}

                checkIfAllDownloadsFinished()
            }

            DownloadStatus.ERROR -> {
                NotificationHelper.cancelNotification(this, notifId)
                try {
                    NotificationHelper.showErrorNotification(this, item)
                } catch (_: SecurityException) {}

                checkIfAllDownloadsFinished()
            }

            DownloadStatus.CANCELLED -> {
                NotificationHelper.cancelNotification(this, notifId)
                checkIfAllDownloadsFinished()
            }
        }
    }

    /**
     * Publica o actualiza la notificación de Foreground Service.
     * En Android 14+ (API 34) especifica ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC.
     */
    private fun promoteToForeground(notification: android.app.Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    FOREGROUND_SERVICE_NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(FOREGROUND_SERVICE_NOTIF_ID, notification)
            }
        } catch (_: Exception) {
            try {
                startForeground(FOREGROUND_SERVICE_NOTIF_ID, notification)
            } catch (_: Exception) {}
        }
    }

    private fun acquireWakeLockIfNeeded() {
        try {
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(45 * 60 * 1000L) // 45 minutos máximo de resguardo
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLockSafely() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
    }

    private fun checkIfAllDownloadsFinished() {
        val active = downloadManager.getActiveDownloads()
        if (active.isEmpty()) {
            // Ya no hay descargas en progreso: retirar el servicio de foreground y detenerlo
            releaseWakeLockSafely()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        } else {
            // Aún hay descargas activas en la cola: mostrar el progreso de la siguiente
            val next = active.first()
            val nextNotif = NotificationHelper.buildForegroundProgressNotification(
                context = this,
                title = next.title,
                progress = next.progress,
                speed = next.speedText,
                indeterminate = next.progress <= 0
            ).build()
            promoteToForeground(nextNotif)
        }
    }

    override fun onDestroy() {
        downloadManager.removeDownloadListener(downloadListener)
        releaseWakeLockSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
