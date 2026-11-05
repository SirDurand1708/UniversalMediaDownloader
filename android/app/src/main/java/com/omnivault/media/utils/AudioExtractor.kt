package com.omnivault.media.utils

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

object AudioExtractor {
    private const val TAG = "AudioExtractor"

    /**
     * Extrae de forma limpia y sin pérdida el flujo de audio de un archivo de video (MP4/MKV),
     * empaquetándolo en un contenedor de audio nativo (.m4a / AAC) en una fracción de segundo.
     */
    fun extractAudio(sourceFile: File, destFile: File): Boolean {
        if (!sourceFile.exists() || sourceFile.length() == 0L) {
            Log.e(TAG, "Archivo de origen inválido o vacío: ${sourceFile.absolutePath}")
            return false
        }

        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        return try {
            extractor = MediaExtractor()
            extractor.setDataSource(sourceFile.absolutePath)

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || audioFormat == null) {
                Log.w(TAG, "No se encontró pista de audio en ${sourceFile.name}")
                return false
            }

            extractor.selectTrack(audioTrackIndex)

            // Empaquetar exclusivamente la pista de audio en formato MPEG-4 (M4A)
            muxer = MediaMuxer(destFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrackIndex = muxer.addTrack(audioFormat)
            muxer.start()

            val maxBufferSize = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(64 * 1024)
            } else {
                128 * 1024
            }

            val buffer = ByteBuffer.allocate(maxBufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) {
                    break
                }
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags
                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                extractor.advance()
            }

            muxer.stop()
            Log.i(TAG, "Pista de audio extraída con éxito: ${destFile.name} (${destFile.length()} bytes)")
            destFile.exists() && destFile.length() > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error extrayendo pista de audio de ${sourceFile.name}", e)
            try {
                if (destFile.exists()) destFile.delete()
            } catch (_: Exception) {}
            false
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }
}
