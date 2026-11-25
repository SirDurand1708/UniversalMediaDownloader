package com.omnivault.media.utils

/**
 * Proyección Kotlin pura (sin tipos del SDK de Android) para deduplicación de biblioteca
 * y reconciliación de exclusiones (`deleted_song_paths` vs `hidden_song_ids`),
 * permitiendo pruebas unitarias deterministas en JVM.
 */
data class SongKey(
    val id: Long,
    val title: String,
    val path: String,
    val fileSize: Long = 0L
)

data class ExclusionReconcileResult(
    val cleanedDeletedPaths: Set<String>,
    val cleanedHiddenIds: Set<Long>,
    val changed: Boolean
)

object LibraryExclusionHelper {

    private val CALL_REC_PATH_MARKERS = listOf(
        "/call/",
        "/call_rec/",
        "/callrecordings/",
        "/call recording/",
        "/callrecorder/",
        "/acrcalls/",
        "/cubecallrecorder/",
        "/sound_recorder/",
        "/voice_recorder/",
        "/voice recorder/",
        "/sound recorder/"
    )

    private val MESSAGING_OR_REC_PATH_MARKERS = listOf(
        "/recordings/",
        "/whatsapp/",
        "/whatsapp audio/",
        "/whatsapp voice notes/",
        "/whatsapp business audio/",
        "/telegram/"
    )

    private val RECORDING_ALBUMS = setOf(
        "call",
        "call_rec",
        "call recordings",
        "grabaciones",
        "grabaciones de llamadas",
        "sound_recorder",
        "sound recorder",
        "voice recorder",
        "whatsapp audio",
        "whatsapp voice notes"
    )

    private val RECORDING_PREFIX_REGEX = Regex(
        "^(llamada[ _]|call[ _]recording|call@|call-\\d{8}|grabaci[oó]n de llamada|ptt-|aud-).*"
    )

    /**
     * Detecta grabaciones de llamadas telefónicas y notas de voz (WhatsApp/Telegram/grabadoras OEM)
     * evitando falsos positivos en canciones reales guardadas en `/Music/` o `/OmniVault/`.
     */
    fun isCallOrVoiceRecording(
        path: String,
        title: String,
        album: String,
        artist: String
    ): Boolean {
        val normPath = path.trim().lowercase().replace('\\', '/')
        if (normPath.contains("/omnivault/")) return false

        val normArtist = artist.trim().lowercase()
        val isUnknownArtist = normArtist.isBlank() ||
            normArtist == "<unknown>" ||
            normArtist == "unknown" ||
            normArtist == "unknown artist" ||
            normArtist == "artista desconocido" ||
            normArtist == "desconocido"

        if (CALL_REC_PATH_MARKERS.any { normPath.contains(it) }) {
            return true
        }

        val fileName = if (normPath.startsWith("content://")) "" else normPath.substringAfterLast('/')
        val normTitle = title.trim().lowercase()
        val normAlbum = album.trim().lowercase()
        val isVoiceExt = fileName.endsWith(".amr") || fileName.endsWith(".3gp") || fileName.endsWith(".opus")

        if (MESSAGING_OR_REC_PATH_MARKERS.any { normPath.contains(it) }) {
            if (isUnknownArtist || isVoiceExt || RECORDING_PREFIX_REGEX.matches(normTitle) || RECORDING_PREFIX_REGEX.matches(fileName)) {
                return true
            }
        }

        if (normAlbum in RECORDING_ALBUMS && isUnknownArtist) {
            return true
        }

        val isInsideMusicFolder = normPath.contains("/music/")
        if (!isInsideMusicFolder && isUnknownArtist &&
            (RECORDING_PREFIX_REGEX.matches(normTitle) || RECORDING_PREFIX_REGEX.matches(fileName))
        ) {
            return true
        }

        return false
    }

    /**
     * Reconcilia `deleted_song_paths` y `hidden_song_ids` cuando una canción previamente eliminada
     * vuelve a aparecer en `downloadedSongs`.
     *
     * Regla clave: solo se remueve de `hiddenIds` un ID si su ruta estaba presente en `deletedPaths`
     * (es decir, fue marcada como eliminada y re-descargada). Las canciones ocultadas manualmente
     * con `hideSong` (cuya ruta NO está en `deletedPaths`) permanecen ocultas.
     */
    fun reconcileRedownloadedExclusions(
        mergedSongs: List<SongKey>,
        downloadedSongs: List<SongKey>,
        deletedPaths: Set<String>,
        hiddenIds: Set<Long>
    ): ExclusionReconcileResult {
        if (deletedPaths.isEmpty() || downloadedSongs.isEmpty()) {
            return ExclusionReconcileResult(deletedPaths, hiddenIds, changed = false)
        }

        val mutableDeleted = deletedPaths.toMutableSet()
        val mutableHidden = hiddenIds.toMutableSet()
        val deletedNorm = deletedPaths.mapTo(HashSet()) { it.trim().lowercase() }

        val downloadedIds = downloadedSongs.mapTo(HashSet()) { it.id }
        val downloadedPathsNorm = downloadedSongs.mapNotNullTo(HashSet()) {
            it.path.trim().takeIf { p -> p.isNotBlank() }?.lowercase()
        }
        val downloadedContentMediaIds = downloadedSongs.mapNotNullTo(HashSet()) { s ->
            s.path.takeIf { it.startsWith("content://") }?.substringAfterLast("/")?.toLongOrNull()
        }
        val downloadedNameSizes = downloadedSongs.mapNotNullTo(HashSet()) { s ->
            if (s.fileSize > 0L) s.title.lowercase() to s.fileSize else null
        }

        var changed = false
        for (song in mergedSongs) {
            val songPathNorm = song.path.trim().lowercase()
            if (songPathNorm.isBlank() || !deletedNorm.contains(songPathNorm)) continue

            val isInDownloads = downloadedIds.contains(song.id) ||
                downloadedContentMediaIds.contains(song.id) ||
                downloadedPathsNorm.contains(songPathNorm) ||
                (song.fileSize > 0L && downloadedNameSizes.contains(song.title.lowercase() to song.fileSize))

            if (isInDownloads) {
                if (mutableDeleted.removeAll { it.trim().equals(song.path.trim(), ignoreCase = true) }) {
                    changed = true
                }
                if (mutableHidden.remove(song.id)) {
                    changed = true
                }
            }
        }

        return if (changed) {
            ExclusionReconcileResult(mutableDeleted.toSet(), mutableHidden.toSet(), changed = true)
        } else {
            ExclusionReconcileResult(deletedPaths, hiddenIds, changed = false)
        }
    }

    /**
     * Deduplica `localSongs` (prioridad) + `downloadedSongs` usando ruta normalizada, URI de MediaStore,
     * ruta resuelta desde `content://` y par `(nombreArchivo, fileSize)`.
     */
    fun <T> mergeSongsDeduplicated(
        localSongs: List<T>,
        downloadedSongs: List<T>,
        keySelector: (T) -> SongKey,
        contentUriResolver: (String) -> Pair<String?, String?> = { null to null }
    ): List<T> {
        val result = ArrayList<T>(localSongs.size + downloadedSongs.size)
        val seenPaths = HashSet<String>(localSongs.size * 2)
        val seenMediaStoreIds = HashSet<Long>(localSongs.size * 2)
        val seenNameAndSize = HashSet<Pair<String, Long>>(localSongs.size * 2)

        for (item in localSongs) {
            val key = keySelector(item)
            val normPath = key.path.trim().lowercase()
            if (normPath.isNotEmpty() && !seenPaths.add(normPath)) continue

            if (key.id > 0L) {
                seenMediaStoreIds.add(key.id)
            }
            if (key.fileSize > 0L && key.path.startsWith("/")) {
                val fileName = key.path.substringAfterLast('/').lowercase()
                if (fileName.isNotEmpty()) {
                    seenNameAndSize.add(fileName to key.fileSize)
                }
            }
            result.add(item)
        }

        for (item in downloadedSongs) {
            val key = keySelector(item)
            val rawPath = key.path.trim()
            val normPath = rawPath.lowercase()
            if (normPath.isNotEmpty() && seenPaths.contains(normPath)) continue

            var isDuplicate = false
            if (rawPath.startsWith("content://")) {
                val mediaId = rawPath.substringAfterLast('/').toLongOrNull()
                if (mediaId != null && seenMediaStoreIds.contains(mediaId)) {
                    isDuplicate = true
                }
                if (!isDuplicate) {
                    val (resolvedDataPath, resolvedDisplayName) = contentUriResolver(rawPath)
                    val normResolved = resolvedDataPath?.trim()?.lowercase()
                    if (!normResolved.isNullOrEmpty() && seenPaths.contains(normResolved)) {
                        isDuplicate = true
                    }
                    if (!isDuplicate && key.fileSize > 0L && !resolvedDisplayName.isNullOrEmpty()) {
                        if (seenNameAndSize.contains(resolvedDisplayName.trim().lowercase() to key.fileSize)) {
                            isDuplicate = true
                        }
                    }
                }
            } else if (key.fileSize > 0L && rawPath.startsWith("/")) {
                val fileName = rawPath.substringAfterLast('/').lowercase()
                if (fileName.isNotEmpty() && seenNameAndSize.contains(fileName to key.fileSize)) {
                    isDuplicate = true
                }
            }

            if (!isDuplicate) {
                if (normPath.isNotEmpty()) seenPaths.add(normPath)
                result.add(item)
            }
        }

        return result
    }
}
