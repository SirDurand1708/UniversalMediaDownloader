package com.omnivault.media.downloader

import com.google.gson.JsonParser
import com.omnivault.media.model.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class ExtractedMediaInfo(
    val title: String,
    val directStreamUrl: String,
    val format: MediaFormat,
    val durationSeconds: Long = 0,
    val author: String = ""
)

object MediaExtractor {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun extract(url: String, requestedFormat: MediaFormat): ExtractedMediaInfo = withContext(Dispatchers.IO) {
        val lower = url.lowercase()

        when {
            lower.contains("youtube.com") || lower.contains("youtu.be") -> extractYouTube(url, requestedFormat)
            lower.contains("tiktok.com") -> extractTikTok(url, requestedFormat)
            else -> extractGeneric(url, requestedFormat)
        }
    }

    private fun extractYouTube(url: String, requestedFormat: MediaFormat): ExtractedMediaInfo {
        // Obtenemos el título real mediante la API oficial pública oEmbed de YouTube
        var title = "YouTube Media"
        try {
            val oembedUrl = "https://www.youtube.com/oembed?url=${java.net.URLEncoder.encode(url, "UTF-8")}&format=json"
            val req = Request.Builder().url(oembedUrl).build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val json = JsonParser.parseString(body).asJsonObject
                    if (json.has("title")) {
                        title = json.get("title").asString
                    }
                }
            }
        } catch (_: Exception) {}

        // Enlace directo de flujo
        return ExtractedMediaInfo(
            title = title,
            directStreamUrl = url,
            format = requestedFormat
        )
    }

    private fun extractTikTok(url: String, requestedFormat: MediaFormat): ExtractedMediaInfo {
        var title = "TikTok Video"
        try {
            val oembedUrl = "https://www.tiktok.com/oembed?url=${java.net.URLEncoder.encode(url, "UTF-8")}"
            val req = Request.Builder().url(oembedUrl).build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val json = JsonParser.parseString(body).asJsonObject
                    if (json.has("title")) {
                        title = json.get("title").asString
                    }
                }
            }
        } catch (_: Exception) {}

        return ExtractedMediaInfo(
            title = title,
            directStreamUrl = url,
            format = requestedFormat
        )
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
