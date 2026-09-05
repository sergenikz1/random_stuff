package com.voicereader.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Речь встроенной нейросетевой моделью (VITS из проекта Piper, запуск через sherpa-onnx).
 *
 * Модель синтезирует предложение целиком, поэтому одно предложение готовится заранее,
 * пока звучит предыдущее — из-за этого между фразами нет пауз.
 * Позиция слова берётся из позиции воспроизведения AudioTrack: модель не сообщает
 * границы слов, но длительность известна, и этого хватает для подсветки по тексту.
 */
class NeuralTtsBackend(
    private val context: Context,
    private val voice: NeuralVoice,
    private val listener: SpeechListener,
    private val speed: Float
) : SpeechBackend {

    override val supportsPitch = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Синтез — тяжёлая нативная операция, разрешаем ровно одну одновременно. */
    private val synthLock = Mutex()

    private var job: Job? = null
    private var tts: OfflineTts? = null
    @Volatile private var activeTrack: AudioTrack? = null

    private class Piece(val index: Int, val samples: FloatArray)

    override fun start(sentences: List<String>, fromIndex: Int) {
        stop()
        job = scope.launch {
            var track: AudioTrack? = null
            try {
                listener.onPreparing(true)
                val engine = synthLock.withLock { ensureEngine() }
                if (engine == null) {
                    listener.onError("Не удалось загрузить голос «${voice.title}». Попробуйте распаковать его заново в разделе «Голоса».")
                    return@launch
                }

                val sampleRate = engine.sampleRate()
                track = createTrack(sampleRate)
                activeTrack = track

                val channel = Channel<Piece>(capacity = 1)
                val producer = launch {
                    for (i in fromIndex until sentences.size) {
                        coroutineContext.ensureActive()
                        val samples = synthLock.withLock {
                            coroutineContext.ensureActive()
                            engine.generate(sentences[i], 0, speed).samples
                        }
                        channel.send(Piece(i, samples))
                    }
                    channel.close()
                }

                track.play()
                var framesWritten = 0L
                var firstPiece = true
                for (piece in channel) {
                    coroutineContext.ensureActive()
                    if (firstPiece) {
                        listener.onPreparing(false)
                        firstPiece = false
                    }
                    listener.onSentenceStart(piece.index)
                    playPiece(track, piece, sentences.getOrElse(piece.index) { "" }, framesWritten)
                    framesWritten += piece.samples.size
                }
                producer.join()
                awaitPlayback(track, framesWritten)
                listener.onFinished()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                listener.onError("Ошибка синтеза речи: ${e.message ?: e::class.java.simpleName}")
            } finally {
                listener.onPreparing(false)
                // Обнуляем ссылку только если она всё ещё указывает на наш трек:
                // за это время мог стартовать следующий запуск со своим треком.
                if (activeTrack === track) activeTrack = null
                runCatching { track?.pause() }
                runCatching { track?.flush() }
                runCatching { track?.release() }
            }
        }
    }

    override fun stop() {
        val running = job
        job = null
        // pause+flush разблокирует поток, застрявший в AudioTrack.write().
        runCatching { activeTrack?.pause() }
        runCatching { activeTrack?.flush() }
        running?.cancel()
    }

    override fun release() {
        stop()
        scope.cancel()
        runCatching { tts?.release() }
        tts = null
    }

    private fun ensureEngine(): OfflineTts? {
        tts?.let { return it }
        val voiceDir = VoiceInstaller.dirFor(context, voice.dir)
        val espeakDir = VoiceInstaller.dirFor(context, VoiceCatalog.ESPEAK_DIR)
        val model = File(voiceDir, voice.modelFile)
        val tokens = File(voiceDir, "tokens.txt")
        if (!model.exists() || !tokens.exists() || !espeakDir.exists()) return null

        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = model.absolutePath,
                    tokens = tokens.absolutePath,
                    dataDir = espeakDir.absolutePath
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )
        )
        return runCatching { OfflineTts(config = config) }.getOrNull()?.also { tts = it }
    }

    private fun createTrack(sampleRate: Int): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(4096)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private suspend fun playPiece(track: AudioTrack, piece: Piece, text: String, startFrame: Long) {
        val words = wordRanges(text)
        val total = piece.samples.size
        if (total == 0) return
        var offset = 0
        val chunk = 2048
        while (offset < total) {
            coroutineContext.ensureActive()
            val count = minOf(chunk, total - offset)
            val written = track.write(piece.samples, offset, count, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) break
            offset += written
            emitWord(track, piece.index, words, text.length, startFrame, total)
        }
    }

    /** Ждём, пока прозвучит уже записанный в трек хвост. */
    private suspend fun awaitPlayback(track: AudioTrack, totalFrames: Long) {
        while (coroutineContext.isActive && headPosition(track) < totalFrames) {
            delay(40)
        }
    }

    private fun emitWord(
        track: AudioTrack,
        index: Int,
        words: List<IntRange>,
        textLength: Int,
        startFrame: Long,
        pieceFrames: Int
    ) {
        if (words.isEmpty() || textLength == 0) return
        val played = (headPosition(track) - startFrame).coerceIn(0L, pieceFrames.toLong())
        val progress = played.toFloat() / pieceFrames
        val charIndex = (progress * textLength).toInt().coerceIn(0, textLength - 1)
        val word = words.firstOrNull { charIndex <= it.last } ?: words.last()
        listener.onWordRange(index, word)
    }

    /** playbackHeadPosition — 32-битный счётчик кадров, разворачиваем его в положительное число. */
    private fun headPosition(track: AudioTrack): Long =
        track.playbackHeadPosition.toLong() and 0xFFFFFFFFL

    private fun wordRanges(text: String): List<IntRange> {
        val result = ArrayList<IntRange>()
        var start = -1
        text.forEachIndexed { i, ch ->
            if (ch.isLetterOrDigit()) {
                if (start < 0) start = i
            } else if (start >= 0) {
                result += start until i
                start = -1
            }
        }
        if (start >= 0) result += start until text.length
        return result
    }
}
