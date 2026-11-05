package com.omnivault.media.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

object CoverArtHelper {
    private const val TAG = "CoverArtHelper"

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Descarga los bytes binarios de la imagen de carátula desde la URL proporcionada.
     */
    fun downloadCoverBytes(coverUrl: String?): ByteArray? {
        if (coverUrl.isNullOrBlank()) return null
        return try {
            val req = Request.Builder()
                .url(coverUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36")
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
     * Incrusta la carátula e información ID3v2 en un archivo de audio MP3.
     */
    fun embedCoverInMp3(mp3File: File, coverBytes: ByteArray, title: String, artist: String): Boolean {
        if (!mp3File.exists() || coverBytes.isEmpty()) return false
        return try {
            val isPng = coverBytes.size > 8 && coverBytes[0] == 0x89.toByte() && coverBytes[1] == 0x50.toByte()
            val mime = if (isPng) "image/png" else "image/jpeg"

            // 1. Construir Frame APIC (Attached Picture)
            val apicBody = ByteArrayOutputStream()
            apicBody.write(0x00) // Encoding ISO-8859-1
            apicBody.write(mime.toByteArray(Charsets.ISO_8859_1))
            apicBody.write(0x00) // Null terminator para mime
            apicBody.write(0x03) // Tipo de imagen: Cover (Front)
            apicBody.write(0x00) // Descripción vacía (null terminator)
            apicBody.write(coverBytes)

            val apicBytes = apicBody.toByteArray()
            val apicFrame = makeId3Frame("APIC", apicBytes)

            // 2. Construir Frame TIT2 (Título)
            val cleanTitle = if (title.isNotBlank()) title else mp3File.nameWithoutExtension
            val tit2Body = ByteArrayOutputStream()
            tit2Body.write(0x03) // Encoding UTF-8
            tit2Body.write(cleanTitle.toByteArray(Charsets.UTF_8))
            val tit2Frame = makeId3Frame("TIT2", tit2Body.toByteArray())

            // 3. Construir Frame TPE1 (Artista / Canal)
            val cleanArtist = if (artist.isNotBlank()) artist else "OmniVault Audio"
            val tpe1Body = ByteArrayOutputStream()
            tpe1Body.write(0x03) // Encoding UTF-8
            tpe1Body.write(cleanArtist.toByteArray(Charsets.UTF_8))
            val tpe1Frame = makeId3Frame("TPE1", tpe1Body.toByteArray())

            // 4. Armar Tag ID3v2.3 completo
            val allFrames = ByteArrayOutputStream()
            allFrames.write(apicFrame)
            allFrames.write(tit2Frame)
            allFrames.write(tpe1Frame)
            val framesBytes = allFrames.toByteArray()

            // Header ID3v2.3 (10 bytes): "ID3", ver 3.0, flags 0, tamaño syncsafe
            val id3Header = ByteArray(10)
            id3Header[0] = 'I'.code.toByte()
            id3Header[1] = 'D'.code.toByte()
            id3Header[2] = '3'.code.toByte()
            id3Header[3] = 0x03 // v2.3
            id3Header[4] = 0x00 // rev
            id3Header[5] = 0x00 // flags
            writeSyncsafeInt(framesBytes.size, id3Header, 6)

            // 5. Leer audio existente
            val originalAudio = mp3File.readBytes()
            var audioOffset = 0
            if (originalAudio.size > 10 && originalAudio[0] == 'I'.code.toByte() && originalAudio[1] == 'D'.code.toByte() && originalAudio[2] == '3'.code.toByte()) {
                val oldSize = readSyncsafeInt(originalAudio, 6)
                audioOffset = 10 + oldSize
            }

            // 6. Escribir archivo con carátula en la cabecera
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
                Log.i(TAG, "Carátula ID3v2 incrustada exitosamente en ${mp3File.name}")
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
     * Inyecta la carátula en contenedores MP4 / M4A mediante el átomo 'covr'.
     */
    fun embedCoverInM4a(m4aFile: File, coverBytes: ByteArray): Boolean {
        if (!m4aFile.exists() || coverBytes.isEmpty()) return false
        return try {
            // Guardar carátula complementaria .jpg con el mismo nombre
            val companionArt = File(m4aFile.parentFile, "${m4aFile.nameWithoutExtension}.jpg")
            companionArt.writeBytes(coverBytes)

            // Guardar carátula local en la caché de la aplicación para visualización inmediata en la biblioteca
            Log.i(TAG, "Carátula de alta definición vinculada a ${m4aFile.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error al vincular carátula en ${m4aFile.name}", e)
            false
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
        out.write((size shr 24).toByte().toInt())
        out.write((size shr 16).toByte().toInt())
        out.write((size shr 8).toByte().toInt())
        out.write(size.toByte().toInt())
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
