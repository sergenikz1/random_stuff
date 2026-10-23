package com.flowvoice.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Records 16 kHz mono PCM and hands back a complete WAV file.
 *
 * Whisper-compatible endpoints accept WAV directly, so no transcoding is
 * needed and the app stays free of native media dependencies.
 */
class WavRecorder(
    private val silenceMs: Int,
    private val autoStop: Boolean,
    private val maxSeconds: Int,
    private val onLevel: (Float) -> Unit,
    private val onFinished: (ByteArray?, Int, String?) -> Unit
) {

    @Volatile private var running = false
    private val main = Handler(Looper.getMainLooper())
    private var record: AudioRecord? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true

        thread(name = "flowvoice-rec") {
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            if (minBuffer <= 0) {
                finish(null, 0, "audio-init")
                return@thread
            }
            val bufferSize = maxOf(minBuffer * 2, SAMPLE_RATE / 2)

            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, CHANNEL, ENCODING, bufferSize
                )
            } catch (e: Exception) {
                finish(null, 0, e.message ?: "audio-init")
                return@thread
            }

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                finish(null, 0, "audio-init")
                return@thread
            }

            record = recorder
            val pcm = ByteArrayOutputStream()
            val buffer = ShortArray(bufferSize / 2)
            var sawSpeech = false
            var lastVoiceAt = System.currentTimeMillis()
            val startedAt = System.currentTimeMillis()

            try {
                recorder.startRecording()
                while (running) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read <= 0) continue

                    var peak = 0
                    var sum = 0L
                    for (i in 0 until read) {
                        val v = abs(buffer[i].toInt())
                        if (v > peak) peak = v
                        sum += v.toLong()
                        val s = buffer[i].toInt()
                        pcm.write(s and 0xFF)
                        pcm.write((s shr 8) and 0xFF)
                    }

                    val average = (sum / read).toFloat() / Short.MAX_VALUE
                    val level = (peak.toFloat() / Short.MAX_VALUE).coerceIn(0f, 1f)
                    main.post { onLevel(level) }

                    val now = System.currentTimeMillis()
                    if (average > SPEECH_THRESHOLD) {
                        sawSpeech = true
                        lastVoiceAt = now
                    }
                    if (autoStop && sawSpeech && now - lastVoiceAt > silenceMs) break
                    if (now - startedAt > maxSeconds * 1000L) break
                }
            } catch (e: Exception) {
                running = false
                releaseRecorder()
                finish(null, 0, e.message ?: "audio-read")
                return@thread
            }

            running = false
            releaseRecorder()

            val bytes = pcm.toByteArray()
            val durationMs = (bytes.size / 2) * 1000 / SAMPLE_RATE
            if (!sawSpeech || durationMs < 350) {
                finish(null, durationMs, null)
            } else {
                finish(wrapWav(bytes), durationMs, null)
            }
        }
    }

    fun stop() {
        running = false
    }

    fun cancel() {
        running = false
        releaseRecorder()
    }

    private fun releaseRecorder() {
        val r = record ?: return
        record = null
        try {
            if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop()
        } catch (_: Exception) {
        }
        try {
            r.release()
        } catch (_: Exception) {
        }
    }

    private fun finish(data: ByteArray?, durationMs: Int, error: String?) {
        main.post { onFinished(data, durationMs, error) }
    }

    private fun wrapWav(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(pcm.size + 44)
        val byteRate = SAMPLE_RATE * 2
        fun i32(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }
        fun i16(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
        }

        out.write("RIFF".toByteArray())
        i32(36 + pcm.size)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        i32(16)          // PCM header size
        i16(1)           // PCM format
        i16(1)           // mono
        i32(SAMPLE_RATE)
        i32(byteRate)
        i16(2)           // block align
        i16(16)          // bits per sample
        out.write("data".toByteArray())
        i32(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }

    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val SPEECH_THRESHOLD = 0.012f
    }
}
