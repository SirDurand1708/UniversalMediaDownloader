package com.omnivault.media.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver {
    constructor() : super()

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("omnivault_prefs", Context.MODE_PRIVATE)
            val autoClipboard = prefs.getBoolean("auto_clipboard_detection", true)
            if (autoClipboard) {
                ClipboardMonitorService.startService(context)
            }
        }
    }
}
