package com.omnivault.media.ui

import android.app.Application
import android.content.Context
import coil.Coil
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.omnivault.media.service.ClipboardMonitorService
import com.omnivault.media.utils.NotificationHelper

class OmniVaultApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannels(this)
        com.omnivault.media.player.AudioPlayerManager.init(this)

        // Configuración Global de Coil optimizada para miles de canciones y videos
        // Incluye soporte de decodificación nativa de miniaturas de video por hardware
        val imageLoader = ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_media_cache"))
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
        Coil.setImageLoader(imageLoader)

        val prefs = getSharedPreferences("omnivault_prefs", Context.MODE_PRIVATE)
        val autoClipboard = prefs.getBoolean("auto_clipboard_detection", true)
        if (autoClipboard) {
            ClipboardMonitorService.startService(this)
        }
    }
}
