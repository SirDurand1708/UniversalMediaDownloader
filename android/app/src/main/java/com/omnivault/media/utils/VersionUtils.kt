package com.omnivault.media.utils

/**
 * Comparador semántico de versiones compartido por [UpdateManager] y `SettingsViewModel`.
 */
object VersionUtils {

    fun isRemoteNewer(remote: String, current: String): Boolean {
        return try {
            val r = parseParts(remote)
            val c = parseParts(current)
            if (r.isEmpty() || c.isEmpty()) return false
            for (i in 0 until maxOf(r.size, c.size)) {
                val rPart = r.getOrElse(i) { 0 }
                val cPart = c.getOrElse(i) { 0 }
                if (rPart > cPart) return true
                if (rPart < cPart) return false
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun parseParts(version: String): List<Int> {
        val core = version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore("-")
            .substringBefore("+")
        return core.split(".")
            .mapNotNull { segment -> segment.filter { it.isDigit() }.toIntOrNull() }
    }
}
