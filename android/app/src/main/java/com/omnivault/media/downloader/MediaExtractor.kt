package com.omnivault.media.downloader

import com.google.gson.JsonParser
import com.omnivault.media.model.MediaFormat
import com.omnivault.media.model.PlatformType
import com.omnivault.media.utils.UrlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Matcher
import java.util.regex.Pattern

data class ExtractedMediaInfo(
    val title: String,
    val directStreamUrl: String,
    val format: MediaFormat,
    val durationSeconds: Long = 0,
    val author: String = "",
    val thumbnailUrl: String = "",
    val requestHeaders: Map<String, String> = emptyMap(),
    val audioStreamUrl: String? = null
)

data class MediaPreview(
    val title: String,
    val author: String,
    val thumbnailUrl: String,
    val platform: PlatformType,
    val durationText: String = "",
    val originalUrl: String
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
    private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val BOT_USER_AGENT = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"

    private val YT_VIDEO_ID_PATTERN = Pattern.compile(
        "(?:youtu\\.be/|youtube\\.com/(?:embed/|v/|shorts/|watch\\?v=|watch\\?.+&v=))([\\w-]{11})"
    )

    /**
     * Resuelve redirecciones para enlaces acortados (vm.tiktok.com, vt.tiktok.com, fb.watch, ig.me, t.co).
     */
    private fun resolveRedirect(url: String): String {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .build()
            client.newCall(req).execute().use { resp ->
                resp.request.url.toString()
            }
        } catch (_: Exception) {
            url
        }
    }

    private fun cleanFbUrl(raw: String): String = cleanStreamUrl(raw)

    private fun cleanStreamUrl(raw: String): String {
        val clean = raw.replace("\\/", "/").replace("&amp;", "&")
        val unicodePattern = Pattern.compile("\\\\u([0-9a-fA-F]{4})")
        val matcher = unicodePattern.matcher(clean)
        val sb = StringBuffer()
        while (matcher.find()) {
            val code = matcher.group(1).toInt(16)
            matcher.appendReplacement(sb, Matcher.quoteReplacement(code.toChar().toString()))
        }
        matcher.appendTail(sb)
        return sb.toString().trim()
    }

    /**
     * Detecta si una URL corresponde a una lista de reproducción o álbum de YouTube.
     */
    fun isYouTubePlaylist(url: String): Boolean {
        val lower = url.lowercase()
        return (lower.contains("youtube.com") || lower.contains("youtu.be")) && lower.contains("list=")
    }

    /**
     * Extrae todos los identificadores de video de un álbum o lista de reproducción de YouTube.
     */
    fun extractPlaylistVideoUrls(playlistUrl: String): List<String> {
        val listMatcher = Pattern.compile("[?&]list=([a-zA-Z0-9_-]+)").matcher(playlistUrl)
        val listId = if (listMatcher.find()) listMatcher.group(1) else return emptyList()

        try {
            val endpoint = "https://www.youtube.com/youtubei/v1/browse?key=$YT_API_KEY"
            val payload = """
                {
                    "context": {
                        "client": {
                            "clientName": "ANDROID",
                            "clientVersion": "20.10.38",
                            "hl": "es",
                            "gl": "US"
                        }
                    },
                    "browseId": "VL$listId"
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
                    val videoIdMatcher = Pattern.compile("\"videoId\":\\s*\"([a-zA-Z0-9_-]{11})\"").matcher(body)
                    val ids = mutableListOf<String>()
                    val seen = mutableSetOf<String>()
                    while (videoIdMatcher.find()) {
                        val vid = videoIdMatcher.group(1)
                        if (seen.add(vid)) {
                            ids.add("https://youtu.be/$vid")
                        }
                    }
                    if (ids.isNotEmpty()) return ids
                }
            }
        } catch (_: Exception) {}

        return emptyList()
    }

    /**
     * Analiza el enlace y devuelve metadatos enriquecidos (Título, Autor, Miniatura)
     * para que el usuario previsualice el contenido antes de descargarlo.
     */
    suspend fun analyze(url: String): MediaPreview = withContext(Dispatchers.IO) {
        val clean = UrlUtils.extractUrlFromText(url) ?: url
        val resolved = resolveRedirect(clean)
        val platform = PlatformType.fromUrl(resolved)

        when (platform) {
            PlatformType.YOUTUBE -> analyzeYouTube(resolved)
            PlatformType.TIKTOK -> analyzeTikTok(resolved)
            PlatformType.FACEBOOK -> analyzeFacebook(resolved)
            PlatformType.INSTAGRAM, PlatformType.TWITTER -> analyzeSocial(resolved, platform)
            else -> analyzeUniversal(resolved, platform)
        }
    }

    private fun analyzeYouTube(url: String): MediaPreview {
        val isPlaylist = isYouTubePlaylist(url)
        val matcher = YT_VIDEO_ID_PATTERN.matcher(url)
        val videoId = if (matcher.find()) matcher.group(1) else ""
        val thumb = if (videoId.isNotBlank()) "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" else ""

        if (isPlaylist && videoId.isBlank()) {
            val listMatcher = Pattern.compile("[?&]list=([a-zA-Z0-9_-]+)").matcher(url)
            val listId = if (listMatcher.find()) listMatcher.group(1) else ""
            try {
                val oembedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/playlist?list=$listId&format=json"
                val req = Request.Builder().url(oembedUrl).header("User-Agent", BROWSER_USER_AGENT).build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        val root = JsonParser.parseString(body).asJsonObject
                        val title = root.get("title")?.asString ?: "Álbum / Lista de Reproducción"
                        val author = root.get("author_name")?.asString ?: "YouTube Music"
                        return MediaPreview(
                            title = "📀 Álbum: $title",
                            author = author,
                            thumbnailUrl = "https://www.gstatic.com/youtube/media/ytm/images/channel/default_channel_art.png",
                            platform = PlatformType.YOUTUBE,
                            originalUrl = url
                        )
                    }
                }
            } catch (_: Exception) {}

            return MediaPreview(
                title = "📀 Álbum / Lista de YouTube ($listId)",
                author = "YouTube Playlist",
                thumbnailUrl = "",
                platform = PlatformType.YOUTUBE,
                originalUrl = url
            )
        }

        try {
            val oembedUrl = "https://www.youtube.com/oembed?url=https://youtu.be/$videoId&format=json"
            val req = Request.Builder()
                .url(oembedUrl)
                .header("User-Agent", BROWSER_USER_AGENT)
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val root = JsonParser.parseString(body).asJsonObject
                    val rawTitle = root.get("title")?.asString ?: "YouTube Video"
                    val title = if (isPlaylist) "📀 Álbum/Video: $rawTitle" else rawTitle
                    val author = root.get("author_name")?.asString ?: "YouTube Creator"
                    val thumbUrl = root.get("thumbnail_url")?.asString ?: thumb
                    return MediaPreview(
                        title = title,
                        author = author,
                        thumbnailUrl = thumbUrl,
                        platform = PlatformType.YOUTUBE,
                        originalUrl = url
                    )
                }
            }
        } catch (_: Exception) {}

        return MediaPreview(
            title = if (videoId.isNotBlank()) "Video de YouTube ($videoId)" else "Video de YouTube",
            author = "YouTube",
            thumbnailUrl = thumb,
            platform = PlatformType.YOUTUBE,
            originalUrl = url
        )
    }

    private fun analyzeTikTok(url: String): MediaPreview {
        try {
            // Intentar GET TikWM
            val apiUrl = "https://www.tikwm.com/api/?url=${URLEncoder.encode(url, "UTF-8")}"
            val req = Request.Builder().url(apiUrl).header("User-Agent", BROWSER_USER_AGENT).build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val json = JsonParser.parseString(body).asJsonObject
                    if (json.get("code")?.asInt == 0) {
                        val data = json.getAsJsonObject("data")
                        val title = data?.get("title")?.asString?.ifBlank { "TikTok Video" } ?: "TikTok Video"
                        val author = data?.getAsJsonObject("author")?.get("nickname")?.asString ?: "TikTok Creator"
                        val cover = data?.get("cover")?.asString ?: ""
                        val duration = data?.get("duration")?.asInt ?: 0
                        val durationStr = if (duration > 0) String.format("%02d:%02d", duration / 60, duration % 60) else ""

                        return MediaPreview(
                            title = title,
                            author = author,
                            thumbnailUrl = cover,
                            platform = PlatformType.TIKTOK,
                            durationText = durationStr,
                            originalUrl = url
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback OpenGraph
        val og = parseOpenGraph(url)
        return MediaPreview(
            title = og["title"] ?: "Contenido de TikTok",
            author = "TikTok",
            thumbnailUrl = og["image"] ?: "",
            platform = PlatformType.TIKTOK,
            originalUrl = url
        )
    }

    private fun analyzeFacebook(url: String): MediaPreview {
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val html = resp.body?.string() ?: ""

                    // 1. Título
                    var title = "Video de Facebook"
                    val titleMatcher = Pattern.compile("<meta\\s+(?:property|name)=[\"']og:title[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
                    if (titleMatcher.find()) {
                        title = titleMatcher.group(1)
                            .replace("&amp;", "&")
                            .replace("&#xb7;", "•")
                            .replace("&#039;", "'")
                            .replace("&quot;", "\"")
                            .replace(" | Facebook", "")
                            .trim()
                    }

                    // 2. Miniatura
                    var thumbUrl = ""
                    val imgMatcher = Pattern.compile("<meta\\s+(?:property|name)=[\"']og:image(?::url)?[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
                    if (imgMatcher.find()) {
                        thumbUrl = cleanFbUrl(imgMatcher.group(1))
                    } else {
                        val jsonThumb = Pattern.compile("\"(?:preferred_thumbnail|thumbnailImage)\":\\{\"image\":\\{\"uri\":\"([^\"]+)\"").matcher(html)
                        if (jsonThumb.find()) {
                            thumbUrl = cleanFbUrl(jsonThumb.group(1))
                        }
                    }

                    // 3. Autor
                    var author = "Facebook Video"
                    val authorMatcher = Pattern.compile("\"video_owner\":\\{\"name\":\"([^\"]+)\"").matcher(html)
                    if (authorMatcher.find()) {
                        author = authorMatcher.group(1)
                    }

                    return MediaPreview(
                        title = title,
                        author = author,
                        thumbnailUrl = thumbUrl,
                        platform = PlatformType.FACEBOOK,
                        originalUrl = url
                    )
                }
            }
        } catch (_: Exception) {}

        // Fallback OpenGraph
        val og = parseOpenGraph(url)
        return MediaPreview(
            title = og["title"] ?: "Video de Facebook",
            author = "Facebook",
            thumbnailUrl = og["image"] ?: "",
            platform = PlatformType.FACEBOOK,
            originalUrl = url
        )
    }

    private fun analyzeSocial(url: String, platform: PlatformType): MediaPreview {
        // TikWM también soporta Instagram y Twitter
        try {
            val apiUrl = "https://www.tikwm.com/api/?url=${URLEncoder.encode(url, "UTF-8")}"
            val req = Request.Builder().url(apiUrl).header("User-Agent", BROWSER_USER_AGENT).build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val json = JsonParser.parseString(body).asJsonObject
                    if (json.get("code")?.asInt == 0) {
                        val data = json.getAsJsonObject("data")
                        val title = data?.get("title")?.asString?.ifBlank { platform.displayName } ?: platform.displayName
                        val author = data?.getAsJsonObject("author")?.get("nickname")?.asString ?: platform.displayName
                        val cover = data?.get("cover")?.asString ?: ""

                        return MediaPreview(
                            title = title,
                            author = author,
                            thumbnailUrl = cover,
                            platform = platform,
                            originalUrl = url
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback OpenGraph
        val og = parseOpenGraph(url)
        return MediaPreview(
            title = og["title"] ?: "${platform.displayName} Media",
            author = platform.displayName,
            thumbnailUrl = og["image"] ?: "",
            platform = platform,
            originalUrl = url
        )
    }

    private fun analyzeUniversal(url: String, platform: PlatformType): MediaPreview {
        val cleanUrl = url.trim()
        val lower = cleanUrl.lowercase()

        // 1. Enlace directo a archivo multimedia
        val directExtensions = listOf(".mp4", ".mp3", ".m4a", ".webm", ".m3u8", ".wav", ".aac", ".flv", ".mov", ".ogg")
        if (directExtensions.any { lower.contains(it) }) {
            val fileName = cleanUrl.substringAfterLast("/").substringBefore("?").ifBlank { "Archivo Multimedia" }
            return MediaPreview(
                title = fileName,
                author = "Enlace Directo",
                thumbnailUrl = "",
                platform = platform,
                originalUrl = cleanUrl
            )
        }

        // 2. Rastreo profundo de la página con bypass para adultos (+18) y web players
        try {
            val req = Request.Builder()
                .url(cleanUrl)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                .header("Cookie", "age_verified=1; has_age_verified=1; accessAgeDisclaimerPH=1; platform=pc; consent=1; mature_content=1; over18=1; il=es")
                .header("Referer", cleanUrl)
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val html = resp.body?.string() ?: ""

                    // Título (XVideos, Pornhub, OpenGraph, <title>)
                    var title: String? = null
                    val xvTitle = Pattern.compile("setVideoTitle\\s*\\(\\s*['\"]([^'\"]+)['\"]").matcher(html)
                    if (xvTitle.find()) {
                        title = xvTitle.group(1)
                    }

                    if (title.isNullOrBlank()) {
                        val ogTitle = Pattern.compile("<meta\\s+(?:property|name)=[\"']og:title[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
                        if (ogTitle.find()) {
                            title = ogTitle.group(1)
                        }
                    }

                    if (title.isNullOrBlank()) {
                        val pageTitle = Pattern.compile("<title>([^<]+)</title>", Pattern.CASE_INSENSITIVE).matcher(html)
                        if (pageTitle.find()) {
                            title = pageTitle.group(1)
                        }
                    }

                    val cleanTitle = (title ?: "Video Web")
                        .replace("&amp;", "&")
                        .replace("&#039;", "'")
                        .replace("&quot;", "\"")
                        .replace(Regex("(?i)\\s*-\\s*(?:XVIDEOS\\.COM|Pornhub\\.com|SpankBang|RedTube|YouPorn)"), "")
                        .trim()

                    // Miniatura
                    var thumbUrl: String? = null
                    val xvThumb = Pattern.compile("setThumbUrl169\\s*\\(\\s*['\"]([^'\"]+)['\"]").matcher(html)
                    if (xvThumb.find()) {
                        thumbUrl = xvThumb.group(1)
                    }
                    if (thumbUrl.isNullOrBlank()) {
                        val xvThumb2 = Pattern.compile("setThumbUrl\\s*\\(\\s*['\"]([^'\"]+)['\"]").matcher(html)
                        if (xvThumb2.find()) {
                            thumbUrl = xvThumb2.group(1)
                        }
                    }
                    if (thumbUrl.isNullOrBlank()) {
                        val ogImg = Pattern.compile("<meta\\s+(?:property|name)=[\"']og:image(?::url)?[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
                        if (ogImg.find()) {
                            thumbUrl = ogImg.group(1)
                        }
                    }

                    // Autor / Sitio
                    val domain = try {
                        java.net.URI(cleanUrl).host.removePrefix("www.")
                    } catch (_: Exception) {
                        platform.displayName
                    }

                    return MediaPreview(
                        title = cleanTitle,
                        author = domain,
                        thumbnailUrl = thumbUrl?.let { cleanStreamUrl(it) } ?: "",
                        platform = platform,
                        originalUrl = cleanUrl
                    )
                }
            }
        } catch (_: Exception) {}

        val og = parseOpenGraph(cleanUrl)
        val defaultName = og["title"] ?: cleanUrl.substringAfterLast("/").substringBefore("?").ifBlank { platform.displayName }
        return MediaPreview(
            title = defaultName,
            author = platform.displayName,
            thumbnailUrl = og["image"] ?: "",
            platform = platform,
            originalUrl = cleanUrl
        )
    }

    suspend fun extract(url: String, requestedFormat: MediaFormat): ExtractedMediaInfo = withContext(Dispatchers.IO) {
        val clean = UrlUtils.extractUrlFromText(url) ?: url
        val resolved = resolveRedirect(clean)
        val lower = resolved.lowercase()

        when {
            lower.contains("youtube.com") || lower.contains("youtu.be") -> extractYouTube(resolved, requestedFormat)
            lower.contains("tiktok.com") -> extractTikTok(resolved, requestedFormat)
            lower.contains("facebook.com") || lower.contains("fb.watch") || lower.contains("fb.me") -> extractFacebook(resolved, requestedFormat)
            lower.contains("twitter.com") || lower.contains("x.com") || lower.contains("instagram.com") -> extractSocial(resolved, requestedFormat)
            else -> extractUniversal(resolved, requestedFormat)
        }
    }

    /**
     * Extractor oficial de YouTube mediante la API InnerTube de Android con respaldo en Piped.
     * Para video y canciones: utiliza flujos progresivos MP4 oficiales sin cifrado ni errores 403.
     */
    private fun extractYouTube(url: String, requestedFormat: MediaFormat): ExtractedMediaInfo {
        val matcher = YT_VIDEO_ID_PATTERN.matcher(url)
        val videoId = if (matcher.find()) matcher.group(1) else null
            ?: throw Exception("No se pudo identificar el ID del video de YouTube en el enlace proporcionado")

        val thumbUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

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

                    val videoDetails = root.getAsJsonObject("videoDetails")
                    val rawTitle = videoDetails?.get("title")?.asString ?: "YouTube Media"
                    val title = com.omnivault.media.utils.FilenameStandardizer.standardizeTitle(rawTitle)
                    val author = videoDetails?.get("author")?.asString ?: "YouTube"
                    val lengthSec = videoDetails?.get("lengthSeconds")?.asLong ?: 0L

                    val streaming = root.getAsJsonObject("streamingData")

                    if (streaming != null) {
                        // Soporte para Video 1080p FHD y 720p HD mediante combinación de pistas DASH nativas
                        if (!requestedFormat.isAudio) {
                            val adaptive = streaming.getAsJsonArray("adaptiveFormats")
                            if (adaptive != null) {
                                val targetHeight = if (requestedFormat == MediaFormat.MP4_1080) 1080 else 720
                                var vTargetUrl: String? = null
                                var aUrl: String? = null

                                // 1. Buscar video AVC1 (H.264) que MediaMuxer soporte nativamente
                                for (elem in adaptive) {
                                    val obj = elem.asJsonObject
                                    val mime = if (obj.has("mimeType") && !obj.get("mimeType").isJsonNull) obj.get("mimeType").asString else ""
                                    val url = if (obj.has("url") && !obj.get("url").isJsonNull) obj.get("url").asString else null
                                    val height = if (obj.has("height") && !obj.get("height").isJsonNull) obj.get("height").asInt else 0
                                    val quality = if (obj.has("qualityLabel") && !obj.get("qualityLabel").isJsonNull) obj.get("qualityLabel").asString else ""

                                    if (url != null && vTargetUrl == null && mime.startsWith("video/mp4") && mime.contains("avc1")) {
                                        if (height >= targetHeight || quality.contains("$targetHeight")) {
                                            vTargetUrl = url
                                        }
                                    }

                                    if (url != null && aUrl == null && mime.startsWith("audio/mp4")) {
                                        aUrl = url
                                    }
                                }

                                // 2. Si no encontró AVC1 exacto, buscar cualquier video MP4 que coincida
                                if (vTargetUrl == null) {
                                    for (elem in adaptive) {
                                        val obj = elem.asJsonObject
                                        val mime = if (obj.has("mimeType") && !obj.get("mimeType").isJsonNull) obj.get("mimeType").asString else ""
                                        val url = if (obj.has("url") && !obj.get("url").isJsonNull) obj.get("url").asString else null
                                        val height = if (obj.has("height") && !obj.get("height").isJsonNull) obj.get("height").asInt else 0

                                        if (url != null && mime.startsWith("video/mp4") && height >= targetHeight) {
                                            vTargetUrl = url
                                            break
                                        }
                                    }
                                }

                                val finalVUrl = vTargetUrl
                                val finalAUrl = aUrl
                                if (finalVUrl != null && finalAUrl != null) {
                                    return ExtractedMediaInfo(
                                        title = title,
                                        directStreamUrl = finalVUrl,
                                        format = requestedFormat,
                                        durationSeconds = lengthSec,
                                        author = author,
                                        thumbnailUrl = thumbUrl,
                                        requestHeaders = mapOf("User-Agent" to YT_USER_AGENT),
                                        audioStreamUrl = finalAUrl
                                    )
                                }
                            }
                        }

                        // Para audio (M4A / MP3): seleccionar la mejor pista de audio AAC
                        if (requestedFormat.isAudio) {
                            val adaptive = streaming.getAsJsonArray("adaptiveFormats")
                            if (adaptive != null) {
                                for (elem in adaptive) {
                                    val obj = elem.asJsonObject
                                    val mime = if (obj.has("mimeType") && !obj.get("mimeType").isJsonNull) obj.get("mimeType").asString else ""
                                    val url = if (obj.has("url") && !obj.get("url").isJsonNull) obj.get("url").asString else null
                                    if (url != null && mime.startsWith("audio/mp4")) {
                                        return ExtractedMediaInfo(
                                            title = title,
                                            directStreamUrl = url,
                                            format = requestedFormat,
                                            durationSeconds = lengthSec,
                                            author = author,
                                            thumbnailUrl = thumbUrl,
                                            requestHeaders = mapOf("User-Agent" to YT_USER_AGENT)
                                        )
                                    }
                                }
                            }
                        }

                        // Los flujos progresivos (formats) contienen audio AAC + video MP4 sin restricciones ni errores 403
                        val formats = streaming.getAsJsonArray("formats")
                        if (formats != null && formats.size() > 0) {
                            for (elem in formats) {
                                val obj = elem.asJsonObject
                                if (obj.has("url")) {
                                    return ExtractedMediaInfo(
                                        title = title,
                                        directStreamUrl = obj.get("url").asString,
                                        format = requestedFormat,
                                        durationSeconds = lengthSec,
                                        author = author,
                                        thumbnailUrl = thumbUrl,
                                        requestHeaders = mapOf("User-Agent" to YT_USER_AGENT)
                                    )
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
                                        format = requestedFormat,
                                        thumbnailUrl = thumbUrl
                                    )
                                }
                            }
                        }

                        val videos = root.getAsJsonArray("videoStreams")
                        val audios = root.getAsJsonArray("audioStreams")

                        // Video DASH (1080p y 720p) con audio separado en Piped
                        if (!requestedFormat.isAudio && videos != null && audios != null && audios.size() > 0) {
                            val targetHeight = if (requestedFormat == MediaFormat.MP4_1080) 1080 else 720
                            var vTargetUrl: String? = null
                            for (elem in videos) {
                                val obj = elem.asJsonObject
                                val q = if (obj.has("quality") && !obj.get("quality").isJsonNull) obj.get("quality").asString else ""
                                val fmt = if (obj.has("format") && !obj.get("format").isJsonNull) obj.get("format").asString else ""
                                val sUrl = if (obj.has("url") && !obj.get("url").isJsonNull) obj.get("url").asString else ""
                                if ((q.contains("$targetHeight") || q.contains("${targetHeight}p")) && fmt.contains("mp4", ignoreCase = true) && sUrl.isNotBlank()) {
                                    vTargetUrl = sUrl
                                    break
                                }
                            }
                            val aUrl = if (audios[0].isJsonObject && audios[0].asJsonObject.has("url") && !audios[0].asJsonObject.get("url").isJsonNull) audios[0].asJsonObject.get("url").asString else null
                            if (vTargetUrl != null && aUrl != null) {
                                return ExtractedMediaInfo(
                                    title = title,
                                    directStreamUrl = vTargetUrl,
                                    format = requestedFormat,
                                    thumbnailUrl = thumbUrl,
                                    audioStreamUrl = aUrl
                                )
                            }
                        }

                        if (videos != null && videos.size() > 0) {
                            for (elem in videos) {
                                val obj = elem.asJsonObject
                                val sUrl = obj.get("url")?.asString ?: ""
                                if (sUrl.isNotBlank() && (sUrl.contains("proxy.piped") || sUrl.contains("googlevideo.com"))) {
                                    return ExtractedMediaInfo(
                                        title = title,
                                        directStreamUrl = sUrl,
                                        format = requestedFormat,
                                        thumbnailUrl = thumbUrl
                                    )
                                }
                            }
                            val fallbackUrl = videos[0].asJsonObject.get("url")?.asString
                            if (!fallbackUrl.isNullOrBlank()) {
                                return ExtractedMediaInfo(
                                    title = title,
                                    directStreamUrl = fallbackUrl,
                                    format = requestedFormat,
                                    thumbnailUrl = thumbUrl
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
     * Extractor universal de TikTok sin marcas de agua mediante la API TikWM con fallback OpenGraph.
     * Resuelve URLs acortadas (vm.tiktok.com, vt.tiktok.com) y entrega MP4 y MP3 genuinos con carátula.
     */
    private fun extractTikTok(url: String, requestedFormat: MediaFormat): ExtractedMediaInfo {
        // 1. Intentar TikWM vía GET
        try {
            val apiUrl = "https://www.tikwm.com/api/?url=${URLEncoder.encode(url, "UTF-8")}"
            val req = Request.Builder().url(apiUrl).header("User-Agent", BROWSER_USER_AGENT).build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val json = JsonParser.parseString(body).asJsonObject
                    if (json.get("code")?.asInt == 0) {
                        return parseTikWMResponse(json, requestedFormat)
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. Intentar TikWM vía POST
        try {
            val form = FormBody.Builder().add("url", url).add("hd", "1").build()
            val req = Request.Builder()
                .url("https://www.tikwm.com/api/")
                .post(form)
                .header("User-Agent", BROWSER_USER_AGENT)
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val json = JsonParser.parseString(body).asJsonObject
                    if (json.get("code")?.asInt == 0) {
                        return parseTikWMResponse(json, requestedFormat)
                    }
                }
            }
        } catch (_: Exception) {}

        // 3. Fallback a OpenGraph
        val og = parseOpenGraph(url)
        val videoUrl = og["video"] ?: og["stream"]
        if (!videoUrl.isNullOrBlank()) {
            return ExtractedMediaInfo(
                title = og["title"] ?: "TikTok Video",
                directStreamUrl = videoUrl,
                format = requestedFormat,
                author = "TikTok",
                thumbnailUrl = og["image"] ?: ""
            )
        }

        throw Exception("No se pudo procesar el enlace de TikTok. Asegúrate de que el video sea público.")
    }

    private fun parseTikWMResponse(json: com.google.gson.JsonObject, requestedFormat: MediaFormat): ExtractedMediaInfo {
        val data = json.getAsJsonObject("data") ?: throw Exception("Respuesta de TikTok inválida")
        val title = data.get("title")?.asString?.ifBlank { "TikTok Video" } ?: "TikTok Video"
        val author = data.getAsJsonObject("author")?.get("nickname")?.asString ?: "TikTok Creator"
        val cover = data.get("cover")?.asString ?: ""

        val streamUrl = if (requestedFormat.isAudio) {
            var music = data.get("music")?.asString
            if (music.isNullOrBlank()) music = data.get("play")?.asString
            music ?: throw Exception("No se encontró audio disponible en este video")
        } else {
            data.get("play")?.asString ?: throw Exception("No se encontró video disponible en este enlace")
        }

        val fullStreamUrl = if (streamUrl.startsWith("http")) streamUrl else "https://www.tikwm.com$streamUrl"
        val fullCoverUrl = if (cover.isNotBlank() && !cover.startsWith("http")) "https://www.tikwm.com$cover" else cover

        return ExtractedMediaInfo(
            title = title,
            directStreamUrl = fullStreamUrl,
            format = requestedFormat,
            author = author,
            thumbnailUrl = fullCoverUrl
        )
    }

    /**
     * Extractor dedicado de alta fidelidad para videos y Reels de Facebook.
     * Soporta enlaces de facebook.com, fb.watch, fb.me, reels y posts con video.
     */
    private fun extractFacebook(url: String, requestedFormat: MediaFormat): ExtractedMediaInfo {
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                .header("Sec-Fetch-Mode", "navigate")
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val html = resp.body?.string() ?: ""

                    var hdUrl: String? = null
                    var sdUrl: String? = null

                    val hdPatterns = listOf(
                        "\"browser_native_hd_url\"\\s*:\\s*\"([^\"]+)\"",
                        "\"playable_url_quality_hd\"\\s*:\\s*\"([^\"]+)\"",
                        "\"hd_src\"\\s*:\\s*\"([^\"]+)\"",
                        "\"hd_src_no_ratelimit\"\\s*:\\s*\"([^\"]+)\""
                    )

                    for (p in hdPatterns) {
                        val m = Pattern.compile(p).matcher(html)
                        if (m.find()) {
                            hdUrl = cleanFbUrl(m.group(1))
                            break
                        }
                    }

                    val sdPatterns = listOf(
                        "\"browser_native_sd_url\"\\s*:\\s*\"([^\"]+)\"",
                        "\"playable_url\"\\s*:\\s*\"([^\"]+)\"",
                        "\"sd_src\"\\s*:\\s*\"([^\"]+)\"",
                        "\"sd_src_no_ratelimit\"\\s*:\\s*\"([^\"]+)\"",
                        "\"progressive_url\"\\s*:\\s*\"([^\"]+)\"",
                        "<meta\\s+(?:property|name)=[\"']og:video(?::url)?[\"']\\s+content=[\"']([^\"']+)[\"']"
                    )

                    for (p in sdPatterns) {
                        val m = Pattern.compile(p, Pattern.CASE_INSENSITIVE).matcher(html)
                        if (m.find()) {
                            sdUrl = cleanFbUrl(m.group(1))
                            break
                        }
                    }

                    val streamUrl = if (requestedFormat == MediaFormat.MP4_720 && sdUrl != null) {
                        sdUrl
                    } else {
                        hdUrl ?: sdUrl
                    }

                    if (!streamUrl.isNullOrBlank()) {
                        var title = "Video de Facebook"
                        val titleMatcher = Pattern.compile("<meta\\s+(?:property|name)=[\"']og:title[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
                        if (titleMatcher.find()) {
                            title = titleMatcher.group(1)
                                .replace("&amp;", "&")
                                .replace("&#xb7;", "•")
                                .replace("&#039;", "'")
                                .replace("&quot;", "\"")
                                .replace(" | Facebook", "")
                                .trim()
                        }

                        var thumbUrl = ""
                        val imgMatcher = Pattern.compile("<meta\\s+(?:property|name)=[\"']og:image(?::url)?[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
                        if (imgMatcher.find()) {
                            thumbUrl = cleanFbUrl(imgMatcher.group(1))
                        }

                        var author = "Facebook Creator"
                        val authorMatcher = Pattern.compile("\"video_owner\":\\{\"name\":\"([^\"]+)\"").matcher(html)
                        if (authorMatcher.find()) {
                            author = authorMatcher.group(1)
                        }

                        return ExtractedMediaInfo(
                            title = title,
                            directStreamUrl = streamUrl,
                            format = requestedFormat,
                            author = author,
                            thumbnailUrl = thumbUrl,
                            requestHeaders = mapOf(
                                "User-Agent" to DESKTOP_USER_AGENT,
                                "Referer" to "https://www.facebook.com/"
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback: versión móvil
        try {
            val mobileUrl = if (url.contains("facebook.com")) {
                url.replace("www.facebook.com", "mbasic.facebook.com").replace("web.facebook.com", "mbasic.facebook.com")
            } else url

            val req = Request.Builder()
                .url(mobileUrl)
                .header("User-Agent", BROWSER_USER_AGENT)
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val html = resp.body?.string() ?: ""
                    val videoMatcher = Pattern.compile("/video_redirect/\\?src=([^\"&]+)").matcher(html)
                    if (videoMatcher.find()) {
                        val encodedStream = videoMatcher.group(1)
                        val stream = java.net.URLDecoder.decode(encodedStream, "UTF-8")
                        return ExtractedMediaInfo(
                            title = "Facebook Video",
                            directStreamUrl = stream,
                            format = requestedFormat,
                            author = "Facebook",
                            requestHeaders = mapOf("User-Agent" to BROWSER_USER_AGENT)
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        throw Exception("No se pudo extraer el video de Facebook. Verifica que el enlace sea un video o Reel público.")
    }

    /**
     * Extractor para Instagram, Facebook, Twitter / X y otras redes sociales.
     */
    private fun extractSocial(url: String, requestedFormat: MediaFormat): ExtractedMediaInfo {
        // 1. Probar TikWM (soporta Reels, TikTok, Twitter)
        try {
            val apiUrl = "https://www.tikwm.com/api/?url=${URLEncoder.encode(url, "UTF-8")}"
            val req = Request.Builder().url(apiUrl).header("User-Agent", BROWSER_USER_AGENT).build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val json = JsonParser.parseString(body).asJsonObject
                    if (json.get("code")?.asInt == 0) {
                        return parseTikWMResponse(json, requestedFormat)
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. Extractor OpenGraph con User-Agent de rastreador oficial (Facebook / Twitter)
        val og = parseOpenGraph(url)
        val stream = og["video"] ?: og["stream"]
        if (!stream.isNullOrBlank()) {
            val cleanTitle = og["title"] ?: "Social Media Video"
            return ExtractedMediaInfo(
                title = cleanTitle,
                directStreamUrl = stream,
                format = requestedFormat,
                author = "Social",
                thumbnailUrl = og["image"] ?: ""
            )
        }

        return extractUniversal(url, requestedFormat)
    }

    /**
     * Extractor profundo universal para sitios web, reproductores HTML5 y plataformas de contenido para adultos (+18).
     * Bypassa barreras de edad e inspecciona etiquetas de video progresivo y variables de reproductores.
     */
    private fun extractUniversal(url: String, requestedFormat: MediaFormat): ExtractedMediaInfo {
        val cleanUrl = url.trim()
        val lower = cleanUrl.lowercase()

        // 1. Si la URL ya apunta directamente a un archivo de video/audio
        val directExts = listOf(".mp4", ".mp3", ".m4a", ".webm", ".wav", ".aac", ".flv", ".mov", ".ogg", ".m3u8")
        if (directExts.any { lower.contains(it) }) {
            val fileName = cleanUrl.substringAfterLast("/").substringBefore("?").ifBlank { "Media_Download" }
            return ExtractedMediaInfo(
                title = fileName,
                directStreamUrl = cleanUrl,
                format = requestedFormat,
                author = "Direct Stream"
            )
        }

        // 2. Rastreo profundo de HTML con cookies para adultos (+18) y emulando navegador de escritorio
        try {
            val req = Request.Builder()
                .url(cleanUrl)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,video/*,*/*;q=0.8")
                .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                .header("Cookie", "age_verified=1; has_age_verified=1; accessAgeDisclaimerPH=1; platform=pc; consent=1; mature_content=1; over18=1; il=es")
                .header("Referer", cleanUrl)
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val html = resp.body?.string() ?: ""

                    // a. XVideos / XNXX (html5player.setVideoUrlHigh / Low)
                    var streamUrl: String? = null
                    val highMatcher = Pattern.compile("setVideoUrlHigh\\s*\\(\\s*['\"]([^'\"]+)['\"]").matcher(html)
                    if (highMatcher.find()) {
                        streamUrl = highMatcher.group(1)
                    }

                    if (streamUrl.isNullOrBlank()) {
                        val lowMatcher = Pattern.compile("setVideoUrlLow\\s*\\(\\s*['\"]([^'\"]+)['\"]").matcher(html)
                        if (lowMatcher.find()) {
                            streamUrl = lowMatcher.group(1)
                        }
                    }

                    // b. Pornhub / MindGeek / SpankBang (mediaDefinitions, videoUrl, video_url)
                    if (streamUrl.isNullOrBlank()) {
                        val videoUrlMatcher = Pattern.compile("['\"]videoUrl['\"]\\s*:\\s*['\"]([^'\"]+)['\"]").matcher(html)
                        if (videoUrlMatcher.find()) {
                            streamUrl = cleanStreamUrl(videoUrlMatcher.group(1))
                        }
                    }

                    if (streamUrl.isNullOrBlank()) {
                        val videoUrlMatcher2 = Pattern.compile("['\"]video_url['\"]\\s*:\\s*['\"]([^'\"]+)['\"]").matcher(html)
                        if (videoUrlMatcher2.find()) {
                            streamUrl = cleanStreamUrl(videoUrlMatcher2.group(1))
                        }
                    }

                    if (streamUrl.isNullOrBlank()) {
                        val streamUrlMatcher = Pattern.compile("['\"]stream_url['\"]\\s*:\\s*['\"]([^'\"]+)['\"]").matcher(html)
                        if (streamUrlMatcher.find()) {
                            streamUrl = cleanStreamUrl(streamUrlMatcher.group(1))
                        }
                    }

                    if (streamUrl.isNullOrBlank()) {
                        val qualityMatcher = Pattern.compile("['\"]quality_(?:1080p|720p|480p|best)['\"]\\s*:\\s*['\"]([^'\"]+)['\"]").matcher(html)
                        if (qualityMatcher.find()) {
                            streamUrl = cleanStreamUrl(qualityMatcher.group(1))
                        }
                    }

                    // c. Etiquetas HTML5 <video src="..."> o <source src="...">
                    if (streamUrl.isNullOrBlank()) {
                        val sourceMatcher = Pattern.compile("<source\\s+[^>]*src=['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE).matcher(html)
                        if (sourceMatcher.find()) {
                            streamUrl = sourceMatcher.group(1)
                        }
                    }

                    if (streamUrl.isNullOrBlank()) {
                        val videoTagMatcher = Pattern.compile("<video\\s+[^>]*src=['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE).matcher(html)
                        if (videoTagMatcher.find()) {
                            streamUrl = videoTagMatcher.group(1)
                        }
                    }

                    // d. Búsqueda de cualquier URL de video progresivo .mp4 incrustada en scripts
                    if (streamUrl.isNullOrBlank()) {
                        val progressiveMatcher = Pattern.compile("(https?:[/\\\\]{2}[a-zA-Z0-9_.-]+(?:[/\\\\][a-zA-Z0-9_.-]+)*\\.(?:mp4|webm)(?:\\?[^\"'\\s<>]*)?)").matcher(html)
                        while (progressiveMatcher.find()) {
                            val candidate = cleanStreamUrl(progressiveMatcher.group(1))
                            if (!candidate.contains("doubleclick") && !candidate.contains("googleads") && !candidate.contains("pixel")) {
                                streamUrl = candidate
                                break
                            }
                        }
                    }

                    // Si se encontró un flujo directo
                    if (!streamUrl.isNullOrBlank()) {
                        val cleanFinalStream = cleanStreamUrl(streamUrl)

                        // Título
                        var title: String? = null
                        val xvTitle = Pattern.compile("setVideoTitle\\s*\\(\\s*['\"]([^'\"]+)['\"]").matcher(html)
                        if (xvTitle.find()) title = xvTitle.group(1)

                        if (title.isNullOrBlank()) {
                            val ogTitle = Pattern.compile("<meta\\s+(?:property|name)=[\"']og:title[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
                            if (ogTitle.find()) title = ogTitle.group(1)
                        }

                        if (title.isNullOrBlank()) {
                            val pageTitle = Pattern.compile("<title>([^<]+)</title>", Pattern.CASE_INSENSITIVE).matcher(html)
                            if (pageTitle.find()) title = pageTitle.group(1)
                        }

                        val cleanTitle = (title ?: "Video Descargado")
                            .replace("&amp;", "&")
                            .replace("&#039;", "'")
                            .replace("&quot;", "\"")
                            .replace(Regex("(?i)\\s*-\\s*(?:XVIDEOS\\.COM|Pornhub\\.com|SpankBang|RedTube|YouPorn)"), "")
                            .trim()

                        // Miniatura
                        var thumbUrl: String? = null
                        val xvThumb = Pattern.compile("setThumbUrl169\\s*\\(\\s*['\"]([^'\"]+)['\"]").matcher(html)
                        if (xvThumb.find()) thumbUrl = xvThumb.group(1)
                        if (thumbUrl.isNullOrBlank()) {
                            val ogImg = Pattern.compile("<meta\\s+(?:property|name)=[\"']og:image(?::url)?[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
                            if (ogImg.find()) thumbUrl = ogImg.group(1)
                        }

                        val domain = try {
                            java.net.URI(cleanUrl).host.removePrefix("www.")
                        } catch (_: Exception) {
                            "Web"
                        }

                        return ExtractedMediaInfo(
                            title = cleanTitle,
                            directStreamUrl = cleanFinalStream,
                            format = requestedFormat,
                            author = domain,
                            thumbnailUrl = thumbUrl?.let { cleanStreamUrl(it) } ?: "",
                            requestHeaders = mapOf(
                                "User-Agent" to DESKTOP_USER_AGENT,
                                "Referer" to cleanUrl,
                                "Cookie" to "age_verified=1; platform=pc"
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback OpenGraph
        val og = parseOpenGraph(cleanUrl)
        val defaultName = og["title"] ?: cleanUrl.substringAfterLast("/").substringBefore("?").ifBlank { "Media_Download" }
        val stream = og["video"] ?: cleanUrl
        return ExtractedMediaInfo(
            title = defaultName,
            directStreamUrl = stream,
            format = requestedFormat,
            thumbnailUrl = og["image"] ?: "",
            requestHeaders = mapOf(
                "User-Agent" to DESKTOP_USER_AGENT,
                "Referer" to cleanUrl
            )
        )
    }

    /**
     * Rastrea metadatos OpenGraph estándar (og:video, og:image, og:title) de cualquier página web.
     */
    private fun parseOpenGraph(url: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", BOT_USER_AGENT)
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val html = resp.body?.string() ?: ""

                    // Regex para og:video
                    val videoMatcher = Pattern.compile("<meta\\s+(?:property|name)=[\"']og:video[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
                    if (videoMatcher.find()) result["video"] = videoMatcher.group(1).replace("&amp;", "&")

                    // Regex para og:image
                    val imageMatcher = Pattern.compile("<meta\\s+(?:property|name)=[\"']og:image[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
                    if (imageMatcher.find()) result["image"] = imageMatcher.group(1).replace("&amp;", "&")

                    // Regex para og:title
                    val titleMatcher = Pattern.compile("<meta\\s+(?:property|name)=[\"']og:title[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
                    if (titleMatcher.find()) result["title"] = titleMatcher.group(1).replace("&amp;", "&")
                }
            }
        } catch (_: Exception) {}
        return result
    }
}
