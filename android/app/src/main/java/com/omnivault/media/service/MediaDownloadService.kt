package com.omnivault.media.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import com.omnivault.media.downloader.DownloadManager
import com.omnivault.media.model.DownloadStatus
import com.omnivault.media.model.MediaFormat
import com.omnivault.media.utils.NotificationHelper

class MediaDownloadService : Service() {

    companion object {
        const val ACTION_START_DOWNLOAD = "com.omnivault.media.START_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.omnivault.media.CANCEL_DOWNLOAD"

        const val EXTRA_URL = "extra_url"
        const val EXTRA_FORMAT = "extra_format"
        const val EXTRA_TASK_ID = "extra_task_id"

        private const val NOTIFICATION_ID_BASE = 5000
    }

    private lateinit var downloadManager: DownloadManager

    override fun onCreate() {
        super.onCreate()
        downloadManager = DownloadManager.getInstance(this)
        downloadManager.onDownloadUpdatedListener = { item ->
            val notification = NotificationHelper.buildProgressNotification(this, item).build()
            try {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID_BASE + item.id.hashCode() % 1000, notification)
            } catch (_: SecurityException) {}

            if (item.status == DownloadStatus.COMPLETED || item.status == DownloadStatus.ERROR || item.status == DownloadStatus.CANCELLED) {
                // Si no quedan tareas activas, detener el servicio
                if (downloadManager.getActiveDownloads().isEmpty()) {
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
