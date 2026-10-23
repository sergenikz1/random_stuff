package com.flowvoice.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * One dictation pipeline shared by the app screen and the keyboard.
 *
 * Cloud mode: record WAV -> Whisper-compatible API -> optional LLM clean-up.
 * Device mode: hand the microphone to Android's own recogniser.
 */
class DictationController(context: Context, private val prefs: Prefs) {

    enum class State { IDLE, RECORDING, PROCESSING }

    interface Listener {
        fun onState(state: State)
        fun onLevel(level: Float)
        fun onPartial(text: String)
        fun onResult(text: String, language: String?)
        /** [code] is one of: no-key, network, http, empty, permission, mic, unavailable. */
        fun onError(code: String, detail: String?)
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val transcriber = Transcriber(prefs)
    private val history = HistoryStore(app)

    private var recorder: WavRecorder? = null
    private var device: DeviceRecognizer? = null

    var listener: Listener? = null
    var state: State = State.IDLE
        private set

    val isBusy: Boolean get() = state != State.IDLE

    fun toggle() {
        when (state) {
            State.IDLE -> start()
            State.RECORDING -> stop()
            State.PROCESSING -> Unit
        }
    }

    fun start() {
        if (state != State.IDLE) return
        setState(State.RECORDING)
        if (prefs.engine == Prefs.ENGINE_DEVICE) startDevice() else startCloud()
    }

    fun stop() {
        when (prefs.engine) {
            Prefs.ENGINE_DEVICE -> device?.stop()
            else -> recorder?.stop()
        }
    }

    fun cancel() {
        recorder?.cancel()
        recorder = null
        device?.cancel()
        device = null
        setState(State.IDLE)
    }

    fun release() {
        cancel()
        worker.shutdownNow()
    }

    /* ------------------------------------------------------------- device */

    private fun startDevice() {
        val recognizer = DeviceRecognizer(app)
        device = recognizer
        recognizer.start(
            language = prefs.language,
            onLevel = { listener?.onLevel(it) },
            onPartial = { listener?.onPartial(it) },
            onResult = { text ->
                device = null
                setState(State.IDLE)
                history.add(text, prefs.language)
                listener?.onResult(text, prefs.language)
            },
            onError = { code ->
                device = null
                setState(State.IDLE)
                listener?.onError(code, null)
            }
        )
    }

    /* -------------------------------------------------------------- cloud */

    private fun startCloud() {
        if (prefs.apiKey.isBlank()) {
            setState(State.IDLE)
            listener?.onError("no-key", null)
            return
        }

        val rec = WavRecorder(
            silenceMs = prefs.silenceMs,
            autoStop = prefs.autoStop,
            maxSeconds = prefs.maxSeconds,
            onLevel = { listener?.onLevel(it) },
            onFinished = { wav, _, error ->
                recorder = null
                when {
                    error != null -> {
                        setState(State.IDLE)
                        listener?.onError("mic", error)
                    }
                    wav == null -> {
                        setState(State.IDLE)
                        listener?.onError("empty", null)
                    }
                    else -> upload(wav)
                }
            }
        )
        recorder = rec
        rec.start()
    }

    private fun upload(wav: ByteArray) {
        setState(State.PROCESSING)
        worker.execute {
            try {
                val transcript = transcriber.transcribe(wav)
                val polished = transcriber.cleanup(transcript.text, transcript.language)
                history.add(polished, transcript.language)
                main.post {
                    setState(State.IDLE)
                    listener?.onResult(polished, transcript.language)
                }
            } catch (e: TranscriptionException) {
                val code = when (e.kind) {
                    TranscriptionException.Kind.NO_KEY -> "no-key"
                    TranscriptionException.Kind.NETWORK -> "network"
                    TranscriptionException.Kind.HTTP -> "http"
                    TranscriptionException.Kind.EMPTY -> "empty"
                }
                main.post {
                    setState(State.IDLE)
                    listener?.onError(code, e.message)
                }
            } catch (e: Exception) {
                main.post {
                    setState(State.IDLE)
                    listener?.onError("network", e.message)
                }
            }
        }
    }

    private fun setState(next: State) {
        state = next
        main.post { listener?.onState(next) }
    }
}
