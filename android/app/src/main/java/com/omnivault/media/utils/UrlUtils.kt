package com.omnivault.media.utils

import java.util.regex.Pattern

object UrlUtils {
    private val URL_REGEX_PATTERN = Pattern.compile(
        "(https?://(?:www\\.|(?!www))[a-zA-Z0-9][a-zA-Z0-9-]+[a-zA-Z0-9]\\.[^\\s]{2,}|https?://[a-zA-Z0-9]+\\.[^\\s]{2,})"
    )

    private val SUPPORTED_HOSTS = listOf(
        "youtube.com", "youtu.be",
        "tiktok.com",
        "instagram.com",
        "twitter.com", "x.com",
        "facebook.com", "fb.watch", "fb.me",
        "soundcloud.com",
        "spotify.com",
        "vimeo.com",
        "reddit.com",
        "twitch.tv",
        "xvideos.com", "xnxx.com", "pornhub.com", "spankbang.com", "redtube.com", "youporn.com"
    )

    /**
     * Extrae la primera URL válida de una cadena de texto (e.g., desde el menú Compartir).
     */
    fun extractUrlFromText(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val matcher = URL_REGEX_PATTERN.matcher(text)
        if (matcher.find()) {
            return cleanUrl(matcher.group(1))
        }
        return null
    }

    /**
     * Verifica si una URL pertenece a una plataforma soportada o es un flujo multimedia reproducible.
     */
    fun isSupportedMediaUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        if (SUPPORTED_HOSTS.any { lower.contains(it) }) return true
        if (lower.endsWith(".mp4") || lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".webm") || lower.endsWith(".m3u8")) return true
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    /**
     * Limpia parámetros de rastreo innecesarios como si=..., feature=share, etc.
     */
    fun cleanUrl(rawUrl: String): String {
        return try {
            val uri = java.net.URI(rawUrl)
            val query = uri.query
            if (query == null) return rawUrl

            // Filtrar parametros de rastreo comunes
            val filteredParams = query.split("&")
                .filterNot { param ->
                    val k = param.substringBefore("=").lowercase()
                    k in listOf("si", "feature", "fbclid", "igshid", "utm_source", "utm_medium", "utm_campaign")
                }

            val newQuery = if (filteredParams.isNotEmpty()) "?" + filteredParams.joinToString("&") else ""
            "${uri.scheme}://${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}${uri.path}$newQuery"
        } catch (e: Exception) {
            rawUrl
        }
    }
}
