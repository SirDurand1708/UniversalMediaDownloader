package com.omnivault.media.utils

import com.omnivault.media.model.DownloadItem
import java.io.File

object FilenameStandardizer {

    private val JUNK_PATTERNS = listOf(
        Regex("(?i)^y2mate\\.com\\s*-\\s*"),
        Regex("(?i)^yt1s\\.com\\s*-\\s*"),
        Regex("(?i)^ytmp3\\.cc\\s*-\\s*"),
        Regex("(?i)^snapsave\\.app\\s*-\\s*"),
        Regex("(?i)\\[official\\s*(music\\s*)?video\\]"),
        Regex("(?i)\\((official\\s*)?(music\\s*)?video\\)"),
        Regex("(?i)\\[video\\s*oficial\\]"),
        Regex("(?i)\\(video\\s*oficial\\)"),
        Regex("(?i)\\[audio\\s*oficial\\]"),
        Regex("(?i)\\(audio\\s*oficial\\)"),
        Regex("(?i)\\[official\\s*audio\\]"),
        Regex("(?i)\\(official\\s*audio\\)"),
        Regex("(?i)\\[music\\s*video\\]"),
        Regex("(?i)\\(music\\s*video\\)"),
        Regex("(?i)\\[lyric\\s*(video)?\\]"),
        Regex("(?i)\\(lyric\\s*(video)?\\)"),
        Regex("(?i)\\[video\\s*con\\s*letra\\]"),
        Regex("(?i)\\(video\\s*con\\s*letra\\)"),
        Regex("(?i)\\[letra\\]"),
        Regex("(?i)\\(letra\\)"),
        Regex("(?i)\\[con\\s*letra\\]"),
        Regex("(?i)\\(con\\s*letra\\)"),
        Regex("(?i)\\[visualizer\\]"),
        Regex("(?i)\\(visualizer\\)"),
        Regex("(?i)\\[clip\\s*officiel\\]"),
        Regex("(?i)\\(clip\\s*officiel\\)"),
        Regex("(?i)\\[4k(\\s*uhd)?\\]"),
        Regex("(?i)\\(4k(\\s*uhd)?\\)"),
        Regex("(?i)\\[1080p(\\s*fhd)?\\]"),
        Regex("(?i)\\(1080p(\\s*fhd)?\\)"),
        Regex("(?i)\\[720p(\\s*hd)?\\]"),
        Regex("(?i)\\(720p(\\s*hd)?\\)"),
        Regex("(?i)\\[hd\\]"),
        Regex("(?i)\\(hd\\)"),
        Regex("(?i)\\[hq\\]"),
        Regex("(?i)\\(hq\\)"),
        Regex("(?i)\\[mp3(\\s*320\\s*kbps)?\\]"),
        Regex("(?i)\\(320\\s*kbps\\)"),
        Regex("(?i)\\[remastered(\\s*\\d{4})?\\]"),
        Regex("(?i)\\(remastered(\\s*\\d{4})?\\)"),
        Regex("(?i)\\[en\\s*vivo\\]"),
        Regex("(?i)\\(en\\s*vivo\\)"),
        Regex("(?i)\\[live(\\s*\\d{4})?\\]"),
        Regex("(?i)\\(live(\\s*\\d{4})?\\)"),
        Regex("(?i)\\[audio\\s*hq\\]"),
        Regex("(?i)\\(audio\\s*hq\\)")
    )

    private val MESSAGING_OR_CAMERA_FILE_REGEX = Regex("(?i)^(VID|AUD|PTT|IMG|PXL|VOICE)[-_]\\d{8}.*")
    private val ILLEGAL_FILENAME_CHARS = Regex("[\\\\/:*?\"<>|]")

    /**
     * Limpia un título de canción o video eliminando etiquetas como [Official Video], (4K), etc.
     */
    fun standardizeTitle(raw: String): String {
        var clean = raw
        for (pattern in JUNK_PATTERNS) {
            clean = clean.replace(pattern, "")
        }
        // Quitar espacios repetidos y guiones sueltos en los extremos
        clean = clean.replace(Regex("\\s+"), " ")
        clean = clean.replace(Regex("^\\s*[-_—|•]+\\s*"), "")
        clean = clean.replace(Regex("\\s*[-_—|•]+\\s*$"), "")
        clean = clean.trim()
        return if (clean.isNotBlank()) clean else raw.trim()
    }

    fun cleanArtistName(artist: String?): String {
        if (artist.isNullOrBlank()) return ""
        return artist
            .replace(" - Topic", "", ignoreCase = true)
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun isValidArtist(artist: String?): Boolean {
        val cleaned = cleanArtistName(artist)
        if (cleaned.isBlank()) return false
        val lower = cleaned.lowercase()
        return lower != "<unknown>" &&
            lower != "unknown" &&
            lower != "unknown artist" &&
            lower != "artista desconocido" &&
            lower != "desconocido" &&
            lower != "omnivault" &&
            lower != "aura player"
    }

    /**
     * Formatea el nombre estándar aplicando limpieza de etiquetas (Regla A) y
     * formato uniforme "Artista - Título" cuando hay un artista válido (Regla B),
     * evitando duplicar el artista si el título ya lo incluye.
     */
    fun formatStandardName(rawTitle: String, artist: String? = null): String {
        val cleanTitle = standardizeTitle(rawTitle)
        val cleanArtist = cleanArtistName(artist)
        val combined = if (isValidArtist(cleanArtist) &&
            !cleanTitle.startsWith(cleanArtist, ignoreCase = true) &&
            !cleanTitle.contains("$cleanArtist -", ignoreCase = true)
        ) {
            "$cleanArtist - $cleanTitle"
        } else {
            cleanTitle
        }
        val sanitized = combined
            .replace(ILLEGAL_FILENAME_CHARS, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (sanitized.isNotBlank()) sanitized else standardizeTitle(rawTitle)
    }

    /**
     * Excluye notas de voz, archivos de WhatsApp/Telegram y grabaciones de cámara
     * del renombrado masivo automático.
     */
    fun isExcludedFromBulkStandardize(path: String, fileName: String): Boolean {
        val lowerPath = path.lowercase()
        if (lowerPath.contains("/whatsapp/") ||
            lowerPath.contains("whatsapp audio") ||
            lowerPath.contains("whatsapp video") ||
            lowerPath.contains("/dcim/camera/") ||
            lowerPath.contains("/telegram/") ||
            lowerPath.contains("/recordings/")
        ) {
            return true
        }
        return MESSAGING_OR_CAMERA_FILE_REGEX.matches(fileName.trim())
    }

    /**
     * Estandariza un archivo en el almacenamiento:
     * Renombra el archivo en el disco y actualiza el DownloadItem.
     */
    fun standardizeItemFile(item: DownloadItem): Boolean {
        val path = item.localFilePath ?: return false
        val file = File(path)
        if (!file.exists()) return false

        val cleanName = standardizeTitle(file.nameWithoutExtension)
        if (cleanName == file.nameWithoutExtension) return false // No requirió cambios

        val targetFile = File(file.parentFile, "$cleanName.${file.extension}")
        if (targetFile.exists() && targetFile.absolutePath != file.absolutePath) return false
        val renamed = file.renameTo(targetFile)
        if (renamed) {
            item.title = cleanName
            item.localFilePath = targetFile.absolutePath

            // Si existe archivo de letras .lrc asociado, renombrarlo también
            val oldLrc = File(file.parentFile, "${file.nameWithoutExtension}.lrc")
            if (oldLrc.exists()) {
                val newLrc = File(file.parentFile, "$cleanName.lrc")
                oldLrc.renameTo(newLrc)
            }

            // Si existe carátula .jpg asociada, renombrarla también
            val oldJpg = File(file.parentFile, "${file.nameWithoutExtension}.jpg")
            if (oldJpg.exists()) {
                val newJpg = File(file.parentFile, "$cleanName.jpg")
                oldJpg.renameTo(newJpg)
            }

            return true
        }
        return false
    }
}
