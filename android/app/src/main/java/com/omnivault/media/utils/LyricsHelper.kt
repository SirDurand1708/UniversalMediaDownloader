package com.omnivault.media.utils

import android.util.Log
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object LyricsHelper {
    private const val TAG = "LyricsHelper"

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /**
     * Limpia el título del video/canción quitando sufijos comunes de YouTube/TikTok
     * para maximizar la coincidencia en las bases de datos de letras.
     */
    private fun sanitizeTitleForSearch(title: String): String {
        return title
            .replace(Regex("(?i)\\[.*?(official|video|audio|lyrics|hd|4k|remix|visualizer).*?\\]"), "")
            .replace(Regex("(?i)\\(.*?(official|video|audio|lyrics|hd|4k|remix|visualizer).*?\\)"), "")
            .replace(Regex("(?i)\\b(official\\s+(music\\s+)?video|audio|lyrics)\\b"), "")
            .replace(Regex("[_\\-–—|]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Consulta la API abierta LRCLIB para obtener letras sincronizadas (LRC) o letras en texto plano.
     */
    fun fetchLyrics(title: String, artist: String = ""): String? {
        val cleanTitle = sanitizeTitleForSearch(title)
        if (cleanTitle.isBlank()) return null

        val query = if (artist.isNotBlank() && !artist.equals("OmniVault", ignoreCase = true) && !artist.equals("YouTube", ignoreCase = true)) {
            "$cleanTitle $artist"
        } else {
            cleanTitle
        }

        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://lrclib.net/api/search?q=$encodedQuery"

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "OmniVault-Android-Music/2.0")
                .header("Accept", "application/json")
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return null
                    val jsonArray = JsonParser.parseString(body).asJsonArray
                    if (jsonArray.size() > 0) {
                        for (elem in jsonArray) {
                            val obj = elem.asJsonObject
                            // Priorizar letras sincronizadas con marcas de tiempo [mm:ss.xx]
                            val synced = obj.get("syncedLyrics")?.asString
                            if (!synced.isNullOrBlank()) {
                                Log.i(TAG, "Letras sincronizadas encontradas para: $cleanTitle")
                                return synced
                            }
                        }
                        // Fallback: letra plana si no hay sincronizada
                        val plain = jsonArray[0].asJsonObject.get("plainLyrics")?.asString
                        if (!plain.isNullOrBlank()) {
                            return plain
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo consultar letras para $title: ${e.message}")
        }
        return null
    }

    /**
     * Guarda el archivo complementario .lrc junto a la canción para Lark Player y reproductores Android.
     */
    fun saveLrcFile(targetDir: File, baseFilename: String, lyricsContent: String): File? {
        if (lyricsContent.isBlank()) return null
        return try {
            val lrcFile = File(targetDir, "${baseFilename}.lrc")
            lrcFile.writeText(lyricsContent, Charsets.UTF_8)
            Log.i(TAG, "Archivo de letras Karaoke guardado: ${lrcFile.absolutePath}")
            lrcFile
        } catch (e: Exception) {
            Log.e(TAG, "Error al guardar archivo .lrc", e)
            null
        }
    }
}
