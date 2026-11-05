package com.omnivault.media.ui

import android.app.Application
import android.content.Context
import com.omnivault.media.service.ClipboardMonitorService
import com.omnivault.media.utils.NotificationHelper

class OmniVaultApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannels(this)

        val prefs = getSharedPreferences("omnivault_prefs", Context.MODE_PRIVATE)
        val autoClipboard = prefs.getBoolean("auto_clipboard_detection", true)
        if (autoClipboard) {
            ClipboardMonitorService.startService(this)
        }
    }
}
