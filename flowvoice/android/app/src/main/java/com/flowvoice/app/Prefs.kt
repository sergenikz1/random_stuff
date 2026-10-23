package com.flowvoice.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Every user-visible setting lives here so the activity and the keyboard
 * service always read the same values.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("flowvoice", Context.MODE_PRIVATE)

    var apiBaseUrl: String
        get() = sp.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = sp.edit().putString(KEY_BASE_URL, value.trim()).apply()

    var apiKey: String
        get() = sp.getString(KEY_API_KEY, "") ?: ""
        set(value) = sp.edit().putString(KEY_API_KEY, value.trim()).apply()

    var sttModel: String
        get() = sp.getString(KEY_STT_MODEL, DEFAULT_STT_MODEL) ?: DEFAULT_STT_MODEL
        set(value) = sp.edit().putString(KEY_STT_MODEL, value.trim()).apply()

    var cleanupModel: String
        get() = sp.getString(KEY_CLEANUP_MODEL, DEFAULT_CLEANUP_MODEL) ?: DEFAULT_CLEANUP_MODEL
        set(value) = sp.edit().putString(KEY_CLEANUP_MODEL, value.trim()).apply()

    /** "auto", "ru" or "en". */
    var language: String
        get() = sp.getString(KEY_LANGUAGE, "auto") ?: "auto"
        set(value) = sp.edit().putString(KEY_LANGUAGE, value).apply()

    /** "cloud" uses the Whisper-compatible API, "device" uses Android's recogniser. */
    var engine: String
        get() = sp.getString(KEY_ENGINE, ENGINE_CLOUD) ?: ENGINE_CLOUD
        set(value) = sp.edit().putString(KEY_ENGINE, value).apply()

    var cleanup: Boolean
        get() = sp.getBoolean(KEY_CLEANUP, true)
        set(value) = sp.edit().putBoolean(KEY_CLEANUP, value).apply()

    var autoStop: Boolean
        get() = sp.getBoolean(KEY_AUTO_STOP, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_STOP, value).apply()

    var trailingSpace: Boolean
        get() = sp.getBoolean(KEY_TRAILING_SPACE, true)
        set(value) = sp.edit().putBoolean(KEY_TRAILING_SPACE, value).apply()

    var silenceMs: Int
        get() = sp.getInt(KEY_SILENCE_MS, 1600)
        set(value) = sp.edit().putInt(KEY_SILENCE_MS, value).apply()

    var maxSeconds: Int
        get() = sp.getInt(KEY_MAX_SECONDS, 120)
        set(value) = sp.edit().putInt(KEY_MAX_SECONDS, value).apply()

    var dictionary: List<String>
        get() = (sp.getString(KEY_DICTIONARY, "") ?: "")
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        set(value) = sp.edit().putString(KEY_DICTIONARY, value.joinToString("\n")).apply()

    fun isConfigured(): Boolean = engine == ENGINE_DEVICE || apiKey.isNotBlank()

    companion object {
        const val ENGINE_CLOUD = "cloud"
        const val ENGINE_DEVICE = "device"

        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_STT_MODEL = "whisper-1"
        const val DEFAULT_CLEANUP_MODEL = "gpt-4o-mini"

        private const val KEY_BASE_URL = "apiBaseUrl"
        private const val KEY_API_KEY = "apiKey"
        private const val KEY_STT_MODEL = "sttModel"
        private const val KEY_CLEANUP_MODEL = "cleanupModel"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_ENGINE = "engine"
        private const val KEY_CLEANUP = "cleanup"
        private const val KEY_AUTO_STOP = "autoStop"
        private const val KEY_TRAILING_SPACE = "trailingSpace"
        private const val KEY_SILENCE_MS = "silenceMs"
        private const val KEY_MAX_SECONDS = "maxSeconds"
        private const val KEY_DICTIONARY = "dictionary"
    }
}
