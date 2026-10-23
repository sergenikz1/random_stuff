package com.flowvoice.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TranscriptionException(message: String, val kind: Kind) : Exception(message) {
    enum class Kind { NO_KEY, NETWORK, HTTP, EMPTY }
}

data class Transcript(val text: String, val language: String?)

/**
 * Talks to any OpenAI-compatible /audio/transcriptions endpoint, then
 * optionally runs the result through a chat model to fix punctuation.
 */
class Transcriber(private val prefs: Prefs) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Blocking — call from a background dispatcher. */
    fun transcribe(wav: ByteArray): Transcript {
        val key = prefs.apiKey
        if (key.isBlank()) throw TranscriptionException("no api key", TranscriptionException.Kind.NO_KEY)

        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", "speech.wav",
                wav.toRequestBody("audio/wav".toMediaType(), 0, wav.size)
            )
            .addFormDataPart("model", prefs.sttModel)
            .addFormDataPart("response_format", "verbose_json")

        if (prefs.language != "auto") builder.addFormDataPart("language", prefs.language)

        val terms = prefs.dictionary.joinToString(", ")
        if (terms.isNotBlank()) builder.addFormDataPart("prompt", terms.take(900))

        val request = Request.Builder()
            .url(endpoint("/audio/transcriptions"))
            .header("Authorization", "Bearer $key")
            .post(builder.build())
            .build()

        val body: String
        val code: Int
        try {
            client.newCall(request).execute().use { response ->
                code = response.code
                body = response.body?.string().orEmpty()
            }
        } catch (e: Exception) {
            throw TranscriptionException(e.message ?: "network", TranscriptionException.Kind.NETWORK)
        }

        if (code !in 200..299) {
            throw TranscriptionException("HTTP $code: ${body.take(200)}", TranscriptionException.Kind.HTTP)
        }

        val text: String
        var language: String? = null
        try {
            val json = JSONObject(body)
            text = json.optString("text").trim()
            if (json.has("language")) language = json.optString("language")
        } catch (_: Exception) {
            return Transcript(body.trim(), null)
        }

        if (text.isEmpty()) throw TranscriptionException("empty", TranscriptionException.Kind.EMPTY)
        return Transcript(text, language)
    }

    /** Never throws: a failed clean-up must not lose the user's dictation. */
    fun cleanup(text: String, detectedLanguage: String?): String {
        if (!prefs.cleanup || text.isBlank() || prefs.apiKey.isBlank()) return text

        val target = if (prefs.language != "auto") prefs.language else detectedLanguage?.take(2)?.lowercase()
        val languageName = when (target) {
            "ru" -> "Russian"
            "en" -> "English"
            else -> "the same language as the input"
        }
        val terms = prefs.dictionary

        val system = buildString {
            appendLine("You clean up raw speech-to-text output so it can be typed straight into a text field.")
            appendLine("Reply in $languageName — always the language the user actually spoke. Never translate.")
            appendLine("Fix punctuation, capitalisation and obvious recognition slips.")
            appendLine("Remove filler words (\"uh\", \"um\", \"эээ\", \"ну\", \"как бы\") and false starts.")
            appendLine("Keep the speaker's wording, tone and meaning. Do not summarise, answer, or add anything.")
            if (terms.isNotEmpty()) appendLine("Spell these terms exactly like this when they occur: ${terms.joinToString(", ")}.")
            append("Output only the cleaned text, with no quotes and no commentary.")
        }

        val payload = JSONObject()
            .put("model", prefs.cleanupModel)
            .put("temperature", 0)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", text))
            )

        return try {
            val request = Request.Builder()
                .url(endpoint("/chat/completions"))
                .header("Authorization", "Bearer ${prefs.apiKey}")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return text
                val json = JSONObject(response.body?.string().orEmpty())
                val content = json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .optString("content")
                    .trim()
                if (content.isEmpty()) text else content
            }
        } catch (_: Exception) {
            text
        }
    }

    /** Cheap reachability probe used by the "Test" button. */
    fun testConnection(): String? {
        if (prefs.apiKey.isBlank()) return "no-key"
        return try {
            val request = Request.Builder()
                .url(endpoint("/models"))
                .header("Authorization", "Bearer ${prefs.apiKey}")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) null else "HTTP ${response.code}"
            }
        } catch (e: Exception) {
            e.message ?: "network"
        }
    }

    private fun endpoint(path: String): String = prefs.apiBaseUrl.trimEnd('/') + path
}
