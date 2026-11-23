package com.omnivault.media.utils

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * Logger diagnóstico local ligero (sin red ni telemetría externa).
 * - Rotación O(1) con 2 archivos de 50 KB cada uno (máximo 100 KB en disco).
 * - `logError(...)` escribe de forma asíncrona en un ejecutor dedicado para no bloquear el hilo UI.
 * - Captura crashes fatales con `UncaughtExceptionHandler` de forma síncrona y delega al handler previo.
 */
object LocalCrashLogger {

    private const val LOG_FILE_NAME = "omnivault_diagnostics.log"
    private const val BACKUP_FILE_NAME = "omnivault_diagnostics.log.1"
    private const val MAX_FILE_BYTES = 50L * 1024L
    private const val MAX_STACK_LINES = 20

    private val initialized = AtomicBoolean(false)
    private val lock = ReentrantLock()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "OmniVaultCrashLogger").apply { isDaemon = true }
    }

    @Volatile
    private var logsDir: File? = null

    fun init(context: Context) {
        logsDir = context.applicationContext.filesDir
        if (!initialized.compareAndSet(false, true)) return

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashSync(thread, throwable)
            } catch (_: Throwable) {
            } finally {
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        val dir = logsDir ?: return
        val entry = formatEntry("ERROR", tag, message, throwable)
        try {
            executor.execute {
                lock.lock()
                try {
                    appendEntryLocked(dir, entry)
                } catch (_: Throwable) {
                } finally {
                    lock.unlock()
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun readLogs(context: Context): String {
        val dir = logsDir ?: context.applicationContext.filesDir
        lock.lock()
        return try {
            val backup = File(dir, BACKUP_FILE_NAME)
            val current = File(dir, LOG_FILE_NAME)
            buildString {
                if (backup.exists() && backup.length() > 0L) {
                    append(backup.readText())
                }
                if (current.exists() && current.length() > 0L) {
                    append(current.readText())
                }
            }.trim()
        } catch (_: Throwable) {
            ""
        } finally {
            lock.unlock()
        }
    }

    fun getLogSizeBytes(context: Context): Long {
        val dir = logsDir ?: context.applicationContext.filesDir
        lock.lock()
        return try {
            val backup = File(dir, BACKUP_FILE_NAME)
            val current = File(dir, LOG_FILE_NAME)
            (if (backup.exists()) backup.length() else 0L) +
                (if (current.exists()) current.length() else 0L)
        } catch (_: Throwable) {
            0L
        } finally {
            lock.unlock()
        }
    }

    fun clearLogs(context: Context) {
        val dir = logsDir ?: context.applicationContext.filesDir
        lock.lock()
        try {
            File(dir, BACKUP_FILE_NAME).takeIf { it.exists() }?.delete()
            File(dir, LOG_FILE_NAME).takeIf { it.exists() }?.delete()
        } catch (_: Throwable) {
        } finally {
            lock.unlock()
        }
    }

    private fun writeCrashSync(thread: Thread, throwable: Throwable) {
        val dir = logsDir ?: return
        val entry = formatEntry("FATAL", "Uncaught[${thread.name}]", throwable.message ?: "Crash inesperado", throwable)
        val acquired = try {
            lock.tryLock(250, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        try {
            appendEntryLocked(dir, entry)
        } finally {
            if (acquired) lock.unlock()
        }
    }

    private fun appendEntryLocked(dir: File, entry: String) {
        if (!dir.exists()) dir.mkdirs()
        val current = File(dir, LOG_FILE_NAME)
        val backup = File(dir, BACKUP_FILE_NAME)
        if (current.exists() && current.length() + entry.length > MAX_FILE_BYTES) {
            if (backup.exists()) backup.delete()
            current.renameTo(backup)
        }
        current.appendText(entry)
    }

    private fun formatEntry(level: String, tag: String, message: String, throwable: Throwable?): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            append("[$timestamp] [$level] [$tag] $message\n")
            if (throwable != null) {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val lines = sw.toString().lineSequence().take(MAX_STACK_LINES).toList()
                lines.forEach { append("  ").append(it).append('\n') }
                if (lines.size >= MAX_STACK_LINES) {
                    append("  ... (truncado)\n")
                }
            }
        }
    }
}
