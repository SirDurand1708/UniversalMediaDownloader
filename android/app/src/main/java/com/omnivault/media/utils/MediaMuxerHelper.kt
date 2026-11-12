package com.omnivault.media.utils

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

object MediaMuxerHelper {
    private const val TAG = "MediaMuxerHelper"

    /**
     * Combina de forma nativa un archivo de video DASH (1080p) y un archivo de audio (AAC/M4A)
     * en un único contenedor MP4 Full HD utilizando las APIs nativas de Android (MediaExtractor + MediaMuxer).
     * No requiere binarios externos ni FFmpeg, no consume batería en recodificación y no aumenta el peso del APK.
     */
    fun muxVideoAndAudio(
        videoFile: File,
        audioFile: File,
        outputFile: File
    ): Boolean {
        if (!videoFile.exists() || videoFile.length() == 0L) {
            Log.e(TAG, "Archivo de video inválido: ${videoFile.absolutePath}")
            return false
        }
        if (!audioFile.exists() || audioFile.length() == 0L) {
            Log.e(TAG, "Archivo de audio inválido: ${audioFile.absolutePath}")
            return false
        }

        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        try {
            videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
            audioExtractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }

            // 1. Encontrar pista de video
            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = format
                    break
                }
            }

            // 2. Encontrar pista de audio
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (videoTrackIndex == -1 || videoFormat == null) {
                Log.e(TAG, "No se encontró pista de video en ${videoFile.name}")
                return false
            }
            if (audioTrackIndex == -1 || audioFormat == null) {
                Log.e(TAG, "No se encontró pista de audio en ${audioFile.name}")
                return false
            }

            videoExtractor.selectTrack(videoTrackIndex)
            audioExtractor.selectTrack(audioTrackIndex)

            // 3. Iniciar MediaMuxer
            if (outputFile.exists()) outputFile.delete()
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerVideoTrack = muxer.addTrack(videoFormat)
            val muxerAudioTrack = muxer.addTrack(audioFormat)
            muxer.start()

            // 4. Escribir muestras de video
            val videoBufSize = if (videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(256 * 1024)
            } else {
                512 * 1024
            }
            val videoBuffer = ByteBuffer.allocate(videoBufSize)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = videoExtractor.readSampleData(videoBuffer, 0)
                if (bufferInfo.size < 0) break

                bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                bufferInfo.flags = videoExtractor.sampleFlags
                muxer.writeSampleData(muxerVideoTrack, videoBuffer, bufferInfo)
                videoExtractor.advance()
            }

            // 5. Escribir muestras de audio
            val audioBufSize = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(64 * 1024)
            } else {
                128 * 1024
            }
            val audioBuffer = ByteBuffer.allocate(audioBufSize)

            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = audioExtractor.readSampleData(audioBuffer, 0)
                if (bufferInfo.size < 0) break

                bufferInfo.presentationTimeUs = audioExtractor.sampleTime
                bufferInfo.flags = audioExtractor.sampleFlags
                muxer.writeSampleData(muxerAudioTrack, audioBuffer, bufferInfo)
                audioExtractor.advance()
            }

            Log.i(TAG, "Remuxing exitoso de 1080p FHD: ${outputFile.name} (${outputFile.length() / 1024} KB)")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Error durante remuxing de pistas DASH: ${e.message}", e)
            return false
        } finally {
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { videoExtractor?.release() } catch (_: Exception) {}
            try { audioExtractor?.release() } catch (_: Exception) {}
        }
    }
}
