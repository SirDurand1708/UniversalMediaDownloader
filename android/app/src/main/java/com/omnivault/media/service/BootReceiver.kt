package com.omnivault.media.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver {
    constructor() : super()

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            // Android 10+ bloquea lectura de portapapeles en segundo plano y Android 14+/15+
            // prohíbe iniciar ForegroundService(dataSync) desde BOOT_COMPLETED.
            // La detección de enlaces se realiza al volver a la app (ON_RESUME) o desde acceso directo.
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                val prefs = context.getSharedPreferences("omnivault_prefs", Context.MODE_PRIVATE)
                val autoClipboard = prefs.getBoolean("auto_clipboard_detection", true)
                if (autoClipboard) {
                    try {
                        ClipboardMonitorService.startService(context)
                    } catch (_: Exception) {}
                }
            }
        }
    }
}
