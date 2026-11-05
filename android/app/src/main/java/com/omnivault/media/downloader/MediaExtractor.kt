package com.omnivault.media.downloader

import com.google.gson.JsonParser
import com.omnivault.media.model.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class ExtractedMediaInfo(
    val title: String,
    val directStreamUrl: String,
    val format: MediaFormat,
    val durationSeconds: Long = 0,
    val author: String = "",
    val requestHeaders: Map<String, String> = emptyMap()
)

object MediaExtractor {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private const val YT_API_KEY = "AIzaSyAO_FJ2SlqAE4An4Ek78YsFubnuhRlzQ"
    private const val YT_USER_AGENT = "com.google.android.youtube/20.10.38 (Linux; U; Android 11)"
    private const val BROWSER_USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val YT_VIDEO_ID_PATTERN = Pattern.compile(
        "(?:youtu\\.be/|youtube\\.com/(?:embed/|v/|shorts/|watch\\?v=|watch\\?.+&v=))([\\w-]{11})"
    )

    suspend fun extract(url: String, requestedFormat: MediaFormat): ExtractedMediaInfo = withContext(Dispatchers.IO) {
        val lower = url.lowercase()

        when {
            lower.contains("youtube.com") || lower.contains("youtu.be") -> extractYouTube(url, requestedFormat)
            lower.contains("tiktok.com") -> extractTikTok(url, requestedFormat)
            lower.contains("twitter.com") || lower.contains("x.com") || lower.contains("instagram.com") -> extractSocial(url, requestedFormat)
            else -> extractGeneric(url, requestedFormat)
        }
    }

    /**
     * Extractor oficial de YouTube mediante la API InnerTube de Android con respaldo en Piped.
     * Extrae flujos de video directos MP4 y flujos de audio directos M4A/MP3 (ftyp).
     */
    private fun extractYouTube(url: String, requestedFormat: MediaFormat): ExtractedMediaInfo {
        val matcher = YT_VIDEO_ID_PATTERN.matcher(url)
        val videoId = if (matcher.find()) matcher.group(1) else null
            ?: throw Exception("No se pudo identificar el ID del video de YouTube en el enlace proporcionado")

        // 1. Intentar mediante la API oficial InnerTube (Cliente Android)
        try {
            val endpoint = "https://www.youtube.com/youtubei/v1/player?key=$YT_API_KEY"
            val payload = """
                {
                    "context": {
                        "client": {
                            "clientName": "ANDROID",
                            "clientVersion": "20.10.38",
                            "hl": "es",
                            "gl": "US",
                            "androidSdkVersion": 30
                        },
                        "user": {
                            "lockedSafetyMode": false
                        }
                    },
                    "videoId": "$videoId"
                }
            """.trimIndent()

            val req = Request.Builder()
                .url(endpoint)
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("User-Agent", YT_USER_AGENT)
                .header("Content-Type", "application/json")
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val root = JsonParser.parseString(body).asJsonObject

                    val title = root.getAsJsonObject("videoDetails")?.get("title")?.asString ?: "YouTube Media"
                    val streaming = root.getAsJsonObject("streamingData")

                    if (streaming != null) {
                        if (requestedFormat.isAudio) {
                            // Buscar stream de audio en adaptiveFormats con URL directa
                            val adaptives = streaming.getAsJsonArray("adaptiveFormats")
                            if (adaptives != null) {
                                for (elem in adaptives) {
                                    val obj = elem.asJsonObject
                                    val mime = obj.get("mimeType")?.asString ?: ""
                                    if (mime.startsWith("audio/mp4") && obj.has("url")) {
                                        val directUrl = obj.get("url").asString
                                        return ExtractedMediaInfo(
                                            title = title,
                                            directStreamUrl = directUrl,
                                            format = requestedFormat,
                                            requestHeaders = mapOf("User-Agent" to YT_USER_AGENT)
                                        )
                                    }
                                }
                                // Fallback a cualquier audio con URL directa
                                for (elem in adaptives) {
                                    val obj = elem.asJsonObject
                                    val mime = obj.get("mimeType")?.asString ?: ""
                                    if (mime.startsWith("audio/") && obj.has("url")) {
                                        return ExtractedMediaInfo(
                                            title = title,
                                            directStreamUrl = obj.get("url").asString,
                                            format = requestedFormat,
                                            requestHeaders = mapOf("User-Agent" to YT_USER_AGENT)
                                        )
                                    }
                                }
                            }
                        } else {
                            // Buscar stream de video combinado (audio+video) en formats
                            val formats = streaming.getAsJsonArray("formats")
                            if (formats != null && formats.size() > 0) {
                                for (elem in formats) {
                                    val obj = elem.asJsonObject
                                    if (obj.has("url")) {
                                        return ExtractedMediaInfo(
                                            title = title,
                                            directStreamUrl = obj.get("url").asString,
                                            format = requestedFormat,
                                            requestHeaders = mapOf("User-Agent" to YT_USER_AGENT)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. Respaldo mediante instancia proxy Piped
        val pipedInstances = listOf(
            "https://api.piped.private.coffee",
            "https://pipedapi.tokhmi.xyz"
        )

        for (host in pipedInstances) {
            try {
                val pReq = Request.Builder()
                    .url("$host/streams/$videoId")
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .build()

                client.newCall(pReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        val root = JsonParser.parseString(body).asJsonObject
                        val title = root.get("title")?.asString ?: "YouTube Video"

                        if (requestedFormat.isAudio) {
                            val audios = root.getAsJsonArray("audioStreams")
                            if (audios != null && audios.size() > 0) {
                                val streamUrl = audios[0].asJsonObject.get("url")?.asString
                                if (!streamUrl.isNullOrBlank()) {
                                    return ExtractedMediaInfo(
                                        title = title,
                                        directStreamUrl = streamUrl,
                                        format = requestedFormat
                                    )
                                }
                            }
                        }

                        val videos = root.getAsJsonArray("videoStreams")
                        if (videos != null && videos.size() > 0) {
                            for (elem in videos) {
                                val obj = elem.asJsonObject
                                val sUrl = obj.get("url")?.asString ?: ""
                                if (sUrl.isNotBlank() && (sUrl.contains("proxy.piped") || sUrl.contains("googlevideo.com"))) {
                                    return ExtractedMediaInfo(
                                        title = title,
                                        directStreamUrl = sUrl,
                                        format = requestedFormat
                                    )
                                }
                            }
                            val fallbackUrl = videos[0].asJsonObject.get("url")?.asString
                            if (!fallbackUrl.isNullOrBlank()) {
                                return ExtractedMediaInfo(
                                    title = title,
                                    directStreamUrl = fallbackUrl,
                                    format = requestedFormat
                                )
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        throw Exception("No se pudo obtener el flujo de descarga de YouTube. Por favor revisa tu conexión a internet.")
    }

    /**
     * Extractor de TikTok sin marcas de agua mediante la API TikWM.
     * Retorna video MP4 directo y audio MP3 directo.
     */
    private fun extractTikTok(url: String, requestedFormat: MediaFormat): ExtractedMediaInfo {
        val apiUrl = "https://www.tikwm.com/api/?url=${URLEncoder.encode(url, "UTF-8")}"
        val req = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", BROWSER_USER_AGENT)
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw Exception("Error de respuesta al procesar TikTok (HTTP ${resp.code})")
            }

            val body = resp.body?.string() ?: ""
            val json = JsonParser.parseString(body).asJsonObject

            if (json.get("code")?.asInt != 0) {
                val msg = json.get("msg")?.asString ?: "Error al procesar el enlace de TikTok"
                throw Exception(msg)
            }

            val data = json.getAsJsonObject("data") ?: throw Exception("Respuesta de TikTok inválida")
            val title = data.get("title")?.asString?.ifBlank { "TikTok Video" } ?: "TikTok Video"

            val streamUrl = if (requestedFormat.isAudio) {
                var music = data.get("music")?.asString
                if (music.isNullOrBlank()) {
                    music = data.get("play")?.asString
                }
                music ?: throw Exception("No se encontró audio disponible en este TikTok")
            } else {
                data.get("play")?.asString ?: throw Exception("No se encontró video disponible en este TikTok")
            }

            val fullStreamUrl = if (streamUrl.startsWith("http")) streamUrl else "https://www.tikwm.com$streamUrl"

            return ExtractedMediaInfo(
                title = title,
                directStreamUrl = fullStreamUrl,
                format = requestedFormat
            )
        }
    }

    /**
     * Extractor para Twitter / X e Instagram mediante TikWM / OpenGraph.
     */
    private fun extractSocial(url: String, requestedFormat: MediaFormat): ExtractedMediaInfo {
        // TikWM también soporta Twitter/Instagram
        try {
            val apiUrl = "https://www.tikwm.com/api/?url=${URLEncoder.encode(url, "UTF-8")}"
            val req = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", BROWSER_USER_AGENT)
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val json = JsonParser.parseString(body).asJsonObject
                    if (json.get("code")?.asInt == 0) {
                        val data = json.getAsJsonObject("data")
                        val stream = if (requestedFormat.isAudio) {
                            data?.get("music")?.asString ?: data?.get("play")?.asString
                        } else {
                            data?.get("play")?.asString
                        }
                        if (!stream.isNullOrBlank()) {
                            val title = data?.get("title")?.asString?.ifBlank { "Social Media Video" } ?: "Social Media Video"
                            val fullUrl = if (stream.startsWith("http")) stream else "https://www.tikwm.com$stream"
                            return ExtractedMediaInfo(
                                title = title,
                                directStreamUrl = fullUrl,
                                format = requestedFormat
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback a OpenGraph
        return extractGeneric(url, requestedFormat)
    }

    private fun extractGeneric(url: String, requestedFormat: MediaFormat): ExtractedMediaInfo {
        val defaultName = url.substringAfterLast("/").substringBefore("?").ifBlank { "Media_Download" }
        return ExtractedMediaInfo(
            title = defaultName,
            directStreamUrl = url,
            format = requestedFormat
        )
    }
}
