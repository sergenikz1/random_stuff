package com.voicereader.tts

import com.voicereader.AppText
import com.voicereader.R

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice

/**
 * Речь силами системного движка. Держит в очереди одно предложение вперёд,
 * чтобы между фразами не возникало паузы.
 */
class SystemTtsBackend(
    private val listener: SpeechListener,
    private val profile: VoiceProfile,
    private val language: String
) : SpeechBackend {

    override val supportsPitch = true

    private var sentences: List<String> = emptyList()
    /** Номер «поколения» очереди: колбэки от прошлых запусков игнорируются. */
    private var generation = 0
    private var enqueuedUpTo = -1
    private val main = Handler(Looper.getMainLooper())
    private var volume = VoiceVolume.DEFAULT
    private var volumeChanged = false

    override fun setVolume(volume: Float) {
        val gain = VoiceVolume.playbackGain(volume)
        if (this.volume != gain) {
            this.volume = gain
            volumeChanged = true
        }
    }

    override fun start(sentences: List<String>, fromIndex: Int) {
        this.sentences = sentences
        generation++
        val startingGeneration = generation
        volumeChanged = false
        enqueuedUpTo = fromIndex - 1
        val engine = SystemTts.engine()
        if (engine == null) {
            listener.onError(AppText.get(R.string.err_system_tts))
            return
        }
        listener.onPreparing(true)
        SystemTts.whenReady {
            if (generation != startingGeneration) return@whenReady
            SystemTts.applyLanguage(language)
            engine.setOnUtteranceProgressListener(progress)
            applyVoice(engine)
            listener.onPreparing(false)
            volumeChanged = false
            pump(2, flushFirst = true)
        }
    }

    override fun stop() {
        generation++
        runCatching { SystemTts.engine()?.stop() }
    }

    override fun release() {
        stop()
    }

    private fun applyVoice(engine: TextToSpeech) {
        engine.setPitch(profile.pitch.coerceIn(0.5f, 2.0f))
        engine.setSpeechRate(profile.rate.coerceIn(0.4f, 2.5f))
        val wanted = profile.systemVoiceName ?: return
        val voice: Voice? = runCatching { engine.voices }.getOrNull()?.firstOrNull { it.name == wanted }
        if (voice != null) runCatching { engine.voice = voice }
    }

    private fun pump(count: Int, flushFirst: Boolean = false) {
        val engine = SystemTts.engine() ?: return
        repeat(count) { i ->
            val next = enqueuedUpTo + 1
            if (next >= sentences.size) return
            val id = "$generation:$next"
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
            }
            val mode = if (flushFirst && i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            engine.speak(sentences[next], mode, params, id)
            enqueuedUpTo = next
        }
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            withCurrent(utteranceId) { listener.onSentenceStart(it) }
        }

        override fun onDone(utteranceId: String?) {
            withCurrent(utteranceId) { idx ->
                if (idx >= sentences.size - 1) listener.onFinished()
                else if (volumeChanged) {
                    // В очереди уже есть фраза со старой громкостью. Заменяем её на границе
                    // предложений, не перечитывая текущую фразу и не меняя аудиофокус.
                    volumeChanged = false
                    generation++
                    enqueuedUpTo = idx
                    pump(2, flushFirst = true)
                } else pump(1)
            }
        }

        @Deprecated("Требуется базовым классом")
        override fun onError(utteranceId: String?) = onError(utteranceId, -1)

        override fun onError(utteranceId: String?, errorCode: Int) {
            withCurrent(utteranceId) { listener.onError(AppText.get(R.string.err_system_tts_synthesis)) }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            withCurrent(utteranceId) { listener.onWordRange(it, start until end) }
        }

        // Параметры очереди и громкость меняются на одном потоке; поздние колбэки
        // очищенной очереди не могут передвинуть позицию или снова включить чтение.
        private fun withCurrent(id: String?, action: (Int) -> Unit) {
            main.post {
                val (gen, index) = parse(id) ?: return@post
                if (gen == generation) action(index)
            }
        }

        private fun parse(id: String?): Pair<Int, Int>? {
            val parts = id?.split(':') ?: return null
            if (parts.size != 2) return null
            val gen = parts[0].toIntOrNull() ?: return null
            val idx = parts[1].toIntOrNull() ?: return null
            return gen to idx
        }
    }
}
