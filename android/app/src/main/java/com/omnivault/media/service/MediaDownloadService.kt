package com.omnivault.media.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.omnivault.media.downloader.DownloadEvent
import com.omnivault.media.downloader.UniversalDownloadEngine
import com.omnivault.media.downloader.UniversalDownloadState
import com.omnivault.media.model.DownloadItem
import com.omnivault.media.model.DownloadStatus
import com.omnivault.media.model.MediaFormat
import com.omnivault.media.utils.NotificationHelper
import com.omnivault.media.utils.StorageHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Servicio en segundo plano (Foreground Service) oficial de OmniVault.
 *
 * Mantiene la descarga activa al 100% incluso cuando:
 *  - La aplicación está minimizada
 *  - El usuario usa otra aplicación
 *  - La pantalla del celular está apagada / bloqueada (vía WakeLock parcial)
 */
class MediaDownloadService : Service() {

    companion object {
        const val ACTION_START_DOWNLOAD = "com.omnivault.media.START_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.omnivault.media.CANCEL_DOWNLOAD"

        const val EXTRA_URL = "extra_url"
        const val EXTRA_FORMAT = "extra_format"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_PROCESS_ID = "extra_process_id"

        const val FOREGROUND_SERVICE_NOTIF_ID = 4001
        private const val WAKELOCK_TAG = "OmniVault:MediaDownloadWakeLock"

        /**
         * Lanza el servicio en segundo plano de forma segura para Android 8+.
         * Devuelve `true` si el sistema aceptó iniciar el servicio, o `false` si fue bloqueado.
         */
        fun startDownload(
            context: Context,
            url: String,
            format: MediaFormat,
            processId: String? = null
        ): Boolean {
            NotificationHelper.createNotificationChannels(context)
            val intent = Intent(context, MediaDownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_FORMAT, format.name)
                if (processId != null) {
                    putExtra(EXTRA_PROCESS_ID, processId)
                }
            }
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (e: Exception) {
                android.util.Log.w("MediaDownloadService", "startForegroundService failed, falling back to startService", e)
                try {
                    context.startService(intent)
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }

        /** Cancela una descarga activa */
        fun cancelDownload(context: Context, taskId: String) {
            try {
                UniversalDownloadEngine.getInstance(context).cancelDownload(taskId)
            } catch (_: Exception) {}
        }
    }

    private lateinit var universalEngine: UniversalDownloadEngine
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var hasObservedActiveDownload = false

    override fun onCreate() {
        super.onCreate()

        NotificationHelper.createNotificationChannels(this)

        try {
            val initialNotif = NotificationHelper.buildForegroundServiceNotification(this).build()
            promoteToForeground(initialNotif)
        } catch (e: Exception) {
            android.util.Log.e("MediaDownloadService", "Error posting initial notification", e)
        }

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)?.apply {
                setReferenceCounted(false)
            }
        } catch (_: Exception) {}

        universalEngine = UniversalDownloadEngine.getInstance(this)

        serviceScope.launch {
            universalEngine.downloadsState.collect { stateMap ->
                val active = stateMap.values.filter { universalEngine.isActiveStatus(it.status) }
                if (active.isNotEmpty()) {
                    hasObservedActiveDownload = true
                    acquireWakeLockIfNeeded()
                    val current = active.firstOrNull {
                        it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PROCESSING
                    } ?: active.first()
                    val baseTitle = current.title.ifBlank { "Analizando enlace…" }
                    val notifTitle = if (active.size > 1) "[${active.size} en cola] $baseTitle" else baseTitle
                    val progressInt = (current.progress * 100f).toInt().coerceIn(0, 100)
                    val notif = NotificationHelper.buildForegroundProgressNotification(
                        context = this@MediaDownloadService,
                        title = notifTitle,
                        progress = progressInt,
                        speed = current.speedText,
                        indeterminate = current.progress <= 0f
                    ).build()
                    promoteToForeground(notif)
                } else if (hasObservedActiveDownload) {
                    stopForegroundAndSelf()
                }
            }
        }

        serviceScope.launch {
            universalEngine.downloadEvents.collect { event ->
                when (event) {
                    is DownloadEvent.Completed -> {
                        try {
                            NotificationHelper.showCompletedNotification(
                                this@MediaDownloadService,
                                event.state.toDownloadItem()
                            )
                        } catch (_: SecurityException) {}
                    }

                    is DownloadEvent.Failed -> {
                        try {
                            NotificationHelper.showErrorNotification(
                                this@MediaDownloadService,
                                event.state.toDownloadItem()
                            )
                        } catch (_: SecurityException) {}
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            if (universalEngine.downloadsState.value.values.none { universalEngine.isActiveStatus(it.status) }) {
                stopForegroundAndSelf()
            }
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_START_DOWNLOAD -> {
                val url = intent.getStringExtra(EXTRA_URL)
                val formatKey = intent.getStringExtra(EXTRA_FORMAT) ?: MediaFormat.MP3_320.name
                val format = MediaFormat.fromKey(formatKey)
                val processId = intent.getStringExtra(EXTRA_PROCESS_ID)

                if (!url.isNullOrBlank()) {
                    acquireWakeLockIfNeeded()
                    hasObservedActiveDownload = true
                    universalEngine.enqueueDownload(url, format, processId)
                } else if (universalEngine.downloadsState.value.values.none { universalEngine.isActiveStatus(it.status) }) {
                    stopForegroundAndSelf()
                }
            }

            ACTION_CANCEL_DOWNLOAD -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID)
                if (!taskId.isNullOrBlank()) {
                    universalEngine.cancelDownload(taskId)
                }
                if (universalEngine.downloadsState.value.values.none { universalEngine.isActiveStatus(it.status) }) {
                    stopForegroundAndSelf()
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun promoteToForeground(notification: android.app.Notification) {
        NotificationHelper.createNotificationChannels(this)
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
        } catch (e: Exception) {
            android.util.Log.e("MediaDownloadService", "promoteToForeground error", e)
        }
    }

    private fun acquireWakeLockIfNeeded() {
        try {
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(45 * 60 * 1000L)
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

    private fun stopForegroundAndSelf() {
        releaseWakeLockSafely()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun UniversalDownloadState.toDownloadItem(): DownloadItem = DownloadItem(
        id = processId,
        url = url,
        format = format,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        progress = (progress * 100f).toInt().coerceIn(0, 100),
        speedText = speedText,
        status = status,
        localFilePath = localFilePath,
        fileSizeBytes = fileSizeBytes,
        fileSizeFormatted = if (fileSizeBytes > 0L) StorageHelper.formatFileSize(fileSizeBytes) else "",
        errorReason = errorReason
    )

    override fun onTimeout(startId: Int, fgsType: Int) {
        serviceScope.cancel()
        val scope = (applicationContext as? com.amurayada.music.MusicApplication)?.applicationScope
            ?: CoroutineScope(
                SupervisorJob() +
                    Dispatchers.IO +
                    kotlinx.coroutines.CoroutineExceptionHandler { _, t ->
                        com.omnivault.media.utils.LocalCrashLogger.logError("MediaDownloadService", "Error en onTimeout", t)
                    }
            )
        scope.launch {
            try {
                UniversalDownloadEngine
                    .getInstance(applicationContext)
                    .markActiveDownloadsPaused("Pausado por límite de tiempo en 2º plano (Android)")
            } catch (e: Exception) {
                com.omnivault.media.utils.LocalCrashLogger.logError("MediaDownloadService", "Error pausando descargas en onTimeout", e)
            }
        }
        stopForegroundAndSelf()
        super.onTimeout(startId, fgsType)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        releaseWakeLockSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
