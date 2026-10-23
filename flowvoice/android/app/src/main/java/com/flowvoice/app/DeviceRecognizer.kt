package com.flowvoice.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Free, key-less fallback built on Android's own recogniser.
 *
 * Quality is below Whisper's, but it needs no account and — where the user has
 * downloaded the offline language pack — no network either.
 * Must be created and driven from the main thread.
 */
class DeviceRecognizer(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var active = false

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(
        language: String,
        onLevel: (Float) -> Unit,
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (active) return
        if (!isAvailable()) {
            onError("unavailable")
            return
        }

        val locale = when (language) {
            "ru" -> "ru-RU"
            "en" -> "en-US"
            else -> Locale.getDefault().toLanguageTag()
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr
        active = true

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {
                // The API reports roughly -2..10 dB; map it onto 0..1.
                onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            }

            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                active = false
                release()
                onError(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "empty"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "empty"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission"
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network"
                        else -> "error-$error"
                    }
                )
            }

            override fun onResults(results: Bundle?) {
                active = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                    .trim()
                release()
                if (text.isEmpty()) onError("empty") else onResult(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.let { onPartial(it) }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        try {
            sr.startListening(intent)
        } catch (e: Exception) {
            active = false
            release()
            onError(e.message ?: "start-failed")
        }
    }

    fun stop() {
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
        }
    }

    fun cancel() {
        active = false
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
        }
        release()
    }

    fun release() {
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }
}
