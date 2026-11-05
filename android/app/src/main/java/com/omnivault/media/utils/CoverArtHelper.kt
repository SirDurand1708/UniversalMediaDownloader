package com.omnivault.media.utils

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object CoverArtHelper {
    private const val TAG = "CoverArtHelper"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Descarga los bytes binarios de la imagen de carátula desde la URL proporcionada.
     */
    fun downloadCoverBytes(coverUrl: String?): ByteArray? {
        if (coverUrl.isNullOrBlank()) return null
        return try {
            val req = Request.Builder()
                .url(coverUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    resp.body?.bytes()
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo descargar la carátula desde $coverUrl: ${e.message}")
            null
        }
    }

    /**
     * Incrusta la carátula e información ID3v2.3 en un archivo de audio MP3.
     */
    fun embedCoverInMp3(mp3File: File, coverBytes: ByteArray, title: String, artist: String): Boolean {
        if (!mp3File.exists() || coverBytes.isEmpty()) return false
        return try {
            val isPng = coverBytes.size > 8 && coverBytes[0] == 0x89.toByte() && coverBytes[1] == 0x50.toByte()
            val mime = if (isPng) "image/png" else "image/jpeg"

            // 1. Frame APIC (Front Cover)
            val apicBody = ByteArrayOutputStream()
            apicBody.write(0x00) // Encoding ISO-8859-1 para el MIME
            apicBody.write(mime.toByteArray(Charsets.ISO_8859_1))
            apicBody.write(0x00) // Null terminator MIME
            apicBody.write(0x03) // Tipo de imagen: Cover (Front)
            apicBody.write(0x00) // Descripción vacía (null terminator)
            apicBody.write(coverBytes)
            val apicFrame = makeId3Frame("APIC", apicBody.toByteArray())

            // 2. Frame TIT2 (Título con UTF-16LE + BOM para máxima compatibilidad Android)
            val cleanTitle = if (title.isNotBlank()) title else mp3File.nameWithoutExtension
            val tit2Body = ByteArrayOutputStream()
            tit2Body.write(0x01) // Encoding UTF-16 con BOM
            tit2Body.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
            tit2Body.write(cleanTitle.toByteArray(Charsets.UTF_16LE))
            val tit2Frame = makeId3Frame("TIT2", tit2Body.toByteArray())

            // 3. Frame TPE1 (Artista con UTF-16LE + BOM)
            val cleanArtist = if (artist.isNotBlank()) artist else "OmniVault Audio"
            val tpe1Body = ByteArrayOutputStream()
            tpe1Body.write(0x01) // Encoding UTF-16 con BOM
            tpe1Body.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
            tpe1Body.write(cleanArtist.toByteArray(Charsets.UTF_16LE))
            val tpe1Frame = makeId3Frame("TPE1", tpe1Body.toByteArray())

            // 4. Frame TALB (Álbum: evita que Android lo catalogue como 'Álbum desconocido')
            val albumName = if (artist.isNotBlank()) "$artist - Single" else cleanTitle
            val talbBody = ByteArrayOutputStream()
            talbBody.write(0x01)
            talbBody.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
            talbBody.write(albumName.toByteArray(Charsets.UTF_16LE))
            val talbFrame = makeId3Frame("TALB", talbBody.toByteArray())

            // 5. Frame TPE2 (Artista del Álbum)
            val tpe2Body = ByteArrayOutputStream()
            tpe2Body.write(0x01)
            tpe2Body.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
            tpe2Body.write(cleanArtist.toByteArray(Charsets.UTF_16LE))
            val tpe2Frame = makeId3Frame("TPE2", tpe2Body.toByteArray())

            // 6. Agrupar frames
            val allFrames = ByteArrayOutputStream()
            allFrames.write(apicFrame)
            allFrames.write(tit2Frame)
            allFrames.write(tpe1Frame)
            allFrames.write(talbFrame)
            allFrames.write(tpe2Frame)
            val framesBytes = allFrames.toByteArray()

            // Header ID3v2.3 (10 bytes): "ID3", ver 3.0, flags 0, syncsafe size
            val id3Header = ByteArray(10)
            id3Header[0] = 'I'.code.toByte()
            id3Header[1] = 'D'.code.toByte()
            id3Header[2] = '3'.code.toByte()
            id3Header[3] = 0x03 // v2.3
            id3Header[4] = 0x00
            id3Header[5] = 0x00
            writeSyncsafeInt(framesBytes.size, id3Header, 6)

            // 5. Leer audio existente omitiendo ID3 antiguo si existía
            val originalAudio = mp3File.readBytes()
            var audioOffset = 0
            if (originalAudio.size > 10 && originalAudio[0] == 'I'.code.toByte() && originalAudio[1] == 'D'.code.toByte() && originalAudio[2] == '3'.code.toByte()) {
                val oldSize = readSyncsafeInt(originalAudio, 6)
                audioOffset = 10 + oldSize
            }

            // 6. Escribir archivo final etiquetado
            val tempOutput = File(mp3File.parentFile, "${mp3File.name}.tagged")
            FileOutputStream(tempOutput).use { fos ->
                fos.write(id3Header)
                fos.write(framesBytes)
                if (audioOffset < originalAudio.size) {
                    fos.write(originalAudio, audioOffset, originalAudio.size - audioOffset)
                }
            }

            if (tempOutput.exists() && tempOutput.length() > mp3File.length() / 2) {
                mp3File.delete()
                tempOutput.renameTo(mp3File)
                Log.i(TAG, "Carátula ID3v2.3 incrustada exitosamente en ${mp3File.name}")
                true
            } else {
                tempOutput.delete()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al incrustar carátula ID3 en ${mp3File.name}", e)
            false
        }
    }

    /**
     * Incrusta la carátula y metadatos en contenedores M4A / MP4 utilizando el átomo QuickTime estándar 'covr'.
     * Compatible al 100% con reproductores de música de Android, pantalla de bloqueo y Android MediaStore.
     */
    fun embedCoverInM4a(mediaFile: File, coverBytes: ByteArray, title: String, artist: String): Boolean {
        if (!mediaFile.exists() || coverBytes.isEmpty()) return false
        return try {
            val isPng = coverBytes.size > 8 && coverBytes[0] == 0x89.toByte() && coverBytes[1] == 0x50.toByte()
            val fileBytes = mediaFile.readBytes()

            // 1. Construir caja 'udta' con 'ilst' conteniendo 'covr', '©nam' y '©ART'
            val udtaBox = buildUdtaBox(title, artist, coverBytes, isPng)

            // 2. Localizar cajas 'moov' y 'mdat'
            var idx = 0
            var moovPos = -1
            var moovSize = 0L
            var mdatPos = -1

            while (idx + 8 <= fileBytes.size) {
                var size = readUInt32(fileBytes, idx)
                val type = String(fileBytes, idx + 4, 4, Charsets.ISO_8859_1)

                if (size == 1L && idx + 16 <= fileBytes.size) {
                    size = readUInt64(fileBytes, idx + 8)
                } else if (size == 0L) {
                    size = (fileBytes.size - idx).toLong()
                }

                if (type == "moov") {
                    moovPos = idx
                    moovSize = size
                } else if (type == "mdat") {
                    mdatPos = idx
                }

                if (size <= 0) break
                idx += size.toInt()
            }

            if (moovPos == -1 || moovSize <= 8) {
                Log.w(TAG, "No se encontró caja moov en ${mediaFile.name}, guardando carátula complementaria")
                val companionArt = File(mediaFile.parentFile, "${mediaFile.nameWithoutExtension}.jpg")
                companionArt.writeBytes(coverBytes)
                return true
            }

            // 3. Insertar 'udta' dentro de la caja 'moov' (al final de moov)
            val moovEnd = (moovPos + moovSize).toInt()
            val newFileBytes = ByteArray(fileBytes.size + udtaBox.size)

            System.arraycopy(fileBytes, 0, newFileBytes, 0, moovEnd)
            System.arraycopy(udtaBox, 0, newFileBytes, moovEnd, udtaBox.size)
            System.arraycopy(fileBytes, moovEnd, newFileBytes, moovEnd + udtaBox.size, fileBytes.size - moovEnd)

            // 4. Actualizar tamaño de la caja 'moov'
            val newMoovSize = moovSize + udtaBox.size
            writeUInt32(newMoovSize, newFileBytes, moovPos)

            // 5. Si moov estaba antes de mdat, ajustar tablas de offsets 'stco' y 'co64'
            if (moovPos < mdatPos) {
                val delta = udtaBox.size.toLong()
                adjustChunkOffsets(newFileBytes, moovPos, newMoovSize.toInt(), delta)
            }

            // 6. Escribir archivo temporal y reemplazar atómicamente
            val tempFile = File(mediaFile.parentFile, "${mediaFile.name}.tagged")
            tempFile.writeBytes(newFileBytes)

            if (tempFile.exists() && tempFile.length() > mediaFile.length()) {
                mediaFile.delete()
                tempFile.renameTo(mediaFile)
                Log.i(TAG, "Carátula 'covr' incrustada con éxito en ${mediaFile.name}")
            } else {
                tempFile.delete()
            }

            // Guardar también carátula complementaria .jpg para visores de archivos locales
            try {
                val companionArt = File(mediaFile.parentFile, "${mediaFile.nameWithoutExtension}.jpg")
                companionArt.writeBytes(coverBytes)
            } catch (_: Exception) {}

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error al incrustar átomo covr en ${mediaFile.name}", e)
            false
        }
    }

    private fun adjustChunkOffsets(data: ByteArray, moovStart: Int, moovLength: Int, delta: Long) {
        val moovEnd = moovStart + moovLength
        var i = moovStart + 8
        while (i + 8 <= moovEnd) {
            if (data[i] == 's'.code.toByte() && data[i + 1] == 't'.code.toByte() &&
                data[i + 2] == 'c'.code.toByte() && data[i + 3] == 'o'.code.toByte()) {
                val stcoBoxStart = i - 4
                if (stcoBoxStart >= moovStart && stcoBoxStart + 16 <= moovEnd) {
                    val count = readUInt32(data, stcoBoxStart + 12).toInt()
                    val entriesStart = stcoBoxStart + 16
                    for (c in 0 until count) {
                        val pos = entriesStart + c * 4
                        if (pos + 4 <= moovEnd) {
                            val oldOff = readUInt32(data, pos)
                            writeUInt32(oldOff + delta, data, pos)
                        }
                    }
                }
            } else if (data[i] == 'c'.code.toByte() && data[i + 1] == 'o'.code.toByte() &&
                       data[i + 2] == '6'.code.toByte() && data[i + 3] == '4'.code.toByte()) {
                val co64BoxStart = i - 4
                if (co64BoxStart >= moovStart && co64BoxStart + 16 <= moovEnd) {
                    val count = readUInt32(data, co64BoxStart + 12).toInt()
                    val entriesStart = co64BoxStart + 16
                    for (c in 0 until count) {
                        val pos = entriesStart + c * 8
                        if (pos + 8 <= moovEnd) {
                            val oldOff = readUInt64(data, pos)
                            writeUInt64(oldOff + delta, data, pos)
                        }
                    }
                }
            }
            i++
        }
    }

    private fun buildUdtaBox(title: String, artist: String, coverBytes: ByteArray, isPng: Boolean): ByteArray {
        val ilstBody = ByteArrayOutputStream()

        if (title.isNotBlank()) {
            val titleData = buildDataAtom(1, title.toByteArray(Charsets.UTF_8))
            ilstBody.write(buildAtom("\u00a9nam", titleData))
        }

        if (artist.isNotBlank()) {
            val artistData = buildDataAtom(1, artist.toByteArray(Charsets.UTF_8))
            ilstBody.write(buildAtom("\u00a9ART", artistData))
            ilstBody.write(buildAtom("aART", artistData))
        }

        val albumName = if (artist.isNotBlank()) "$artist - Single" else if (title.isNotBlank()) title else "OmniVault Audio"
        val albumData = buildDataAtom(1, albumName.toByteArray(Charsets.UTF_8))
        ilstBody.write(buildAtom("\u00a9alb", albumData))

        if (coverBytes.isNotEmpty()) {
            val typeFlag = if (isPng) 14 else 13
            val coverData = buildDataAtom(typeFlag, coverBytes)
            ilstBody.write(buildAtom("covr", coverData))
        }

        val ilstAtom = buildAtom("ilst", ilstBody.toByteArray())

        // hdlr box: 33 bytes
        val hdlrBody = ByteArrayOutputStream()
        hdlrBody.write(byteArrayOf(0, 0, 0, 0)) // version/flags
        hdlrBody.write(byteArrayOf(0, 0, 0, 0)) // pre-defined
        hdlrBody.write("mdir".toByteArray(Charsets.ISO_8859_1))
        hdlrBody.write("appl".toByteArray(Charsets.ISO_8859_1))
        hdlrBody.write(ByteArray(8)) // reserved
        hdlrBody.write(0) // name null-terminator
        val hdlrAtom = buildAtom("hdlr", hdlrBody.toByteArray())

        // meta FullBox: 4 bytes version/flags (0) + hdlr + ilst
        val metaBody = ByteArrayOutputStream()
        metaBody.write(byteArrayOf(0, 0, 0, 0))
        metaBody.write(hdlrAtom)
        metaBody.write(ilstAtom)
        val metaAtom = buildAtom("meta", metaBody.toByteArray())

        return buildAtom("udta", metaAtom)
    }

    private fun buildDataAtom(typeFlag: Int, data: ByteArray): ByteArray {
        val payload = ByteArrayOutputStream()
        payload.write((typeFlag shr 24) and 0xFF)
        payload.write((typeFlag shr 16) and 0xFF)
        payload.write((typeFlag shr 8) and 0xFF)
        payload.write(typeFlag and 0xFF)
        payload.write(byteArrayOf(0, 0, 0, 0)) // locale
        payload.write(data)
        return buildAtom("data", payload.toByteArray())
    }

    private fun buildAtom(type: String, payload: ByteArray): ByteArray {
        val totalSize = payload.size + 8
        val out = ByteArrayOutputStream()
        out.write((totalSize shr 24) and 0xFF)
        out.write((totalSize shr 16) and 0xFF)
        out.write((totalSize shr 8) and 0xFF)
        out.write(totalSize and 0xFF)
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(payload)
        return out.toByteArray()
    }

    private fun readUInt32(buf: ByteArray, offset: Int): Long {
        return ((buf[offset].toLong() and 0xFF) shl 24) or
                ((buf[offset + 1].toLong() and 0xFF) shl 16) or
                ((buf[offset + 2].toLong() and 0xFF) shl 8) or
                (buf[offset + 3].toLong() and 0xFF)
    }

    private fun writeUInt32(value: Long, buf: ByteArray, offset: Int) {
        buf[offset] = ((value shr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }

    private fun readUInt64(buf: ByteArray, offset: Int): Long {
        var res = 0L
        for (i in 0 until 8) {
            res = (res shl 8) or (buf[offset + i].toLong() and 0xFF)
        }
        return res
    }

    private fun writeUInt64(value: Long, buf: ByteArray, offset: Int) {
        for (i in 7 downTo 0) {
            buf[offset + i] = ((value shr ((7 - i) * 8)) and 0xFF).toByte()
        }
    }

    /**
     * Guarda la carátula en la caché para mostrarla de inmediato en la lista del historial.
     */
    fun cacheCoverLocally(context: Context, itemId: String, coverBytes: ByteArray): File? {
        return try {
            val cacheDir = File(context.cacheDir, "covers")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val coverFile = File(cacheDir, "cover_${itemId}.jpg")
            coverFile.writeBytes(coverBytes)
            coverFile
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Obtiene la referencia al archivo de carátula en caché para un elemento específico.
     */
    fun getCachedCoverFile(context: Context, itemId: String): File {
        val cacheDir = File(context.cacheDir, "covers")
        return File(cacheDir, "cover_${itemId}.jpg")
    }

    private fun makeId3Frame(id: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        val size = body.size
        out.write((size shr 24) and 0xFF)
        out.write((size shr 16) and 0xFF)
        out.write((size shr 8) and 0xFF)
        out.write(size and 0xFF)
        out.write(0x00) // Flags
        out.write(0x00)
        out.write(body)
        return out.toByteArray()
    }

    private fun writeSyncsafeInt(value: Int, buffer: ByteArray, offset: Int) {
        buffer[offset] = ((value shr 21) and 0x7F).toByte()
        buffer[offset + 1] = ((value shr 14) and 0x7F).toByte()
        buffer[offset + 2] = ((value shr 7) and 0x7F).toByte()
        buffer[offset + 3] = (value and 0x7F).toByte()
    }

    private fun readSyncsafeInt(buffer: ByteArray, offset: Int): Int {
        return ((buffer[offset].toInt() and 0x7F) shl 21) or
                ((buffer[offset + 1].toInt() and 0x7F) shl 14) or
                ((buffer[offset + 2].toInt() and 0x7F) shl 7) or
                (buffer[offset + 3].toInt() and 0x7F)
    }
}
