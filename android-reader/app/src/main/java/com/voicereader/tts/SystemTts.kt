package com.voicereader.tts

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

enum class TtsStatus { INITIALIZING, READY, NO_ENGINE, NO_VOICE_DATA }

/**
 * Держатель системного движка синтеза речи.
 * Нужен и как запасной вариант к нейросетевым голосам, и как единственный
 * вариант для книг на языках, для которых встроенных моделей нет.
 */
object SystemTts {

    private val _status = MutableStateFlow(TtsStatus.INITIALIZING)
    val status: StateFlow<TtsStatus> = _status

    private val _voices = MutableStateFlow<List<InstalledVoice>>(emptyList())
    val voices: StateFlow<List<InstalledVoice>> = _voices

    private var tts: TextToSpeech? = null
    private var onReady: (() -> Unit)? = null

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                _status.value = TtsStatus.READY
                refreshVoices()
                onReady?.invoke()
                onReady = null
            } else {
                _status.value = TtsStatus.NO_ENGINE
            }
        }
    }

    fun engine(): TextToSpeech? = tts?.takeIf { _status.value != TtsStatus.NO_ENGINE }

    fun whenReady(action: () -> Unit) {
        if (_status.value == TtsStatus.READY) action() else onReady = action
    }

    fun refreshVoices() {
        val engine = tts ?: return
        val offline = runCatching { engine.voices }.getOrNull().orEmpty()
            .filter { v ->
                !v.isNetworkConnectionRequired &&
                    v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
            }
        val titles = SystemVoiceNames.assign(offline.map { it.name to it.locale.toLanguageTag() })
        _voices.value = offline
            .map { InstalledVoice(it.name, it.locale.toLanguageTag(), it.quality, titles[it.name] ?: it.name) }
            .sortedWith(compareByDescending<InstalledVoice> { it.quality }.thenBy { it.name })
    }

    /** Подпись голоса в интерфейсе: у системных — их имя, даже если слот сохранён до появления имён. */
    fun labelOf(profile: VoiceProfile): String {
        if (profile.kind == VoiceKind.NEURAL)
            return VoiceCatalog.byId(profile.neuralVoiceId)?.let { com.voicereader.AppText.get(it.titleRes) } ?: profile.label
        return _voices.value.firstOrNull { it.name == profile.systemVoiceName }?.title ?: profile.label
    }

    fun applyLanguage(language: String) {
        val engine = tts ?: return
        val result = engine.setLanguage(Locale.forLanguageTag(language))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            _status.value = TtsStatus.NO_VOICE_DATA
        } else if (_status.value == TtsStatus.NO_VOICE_DATA) {
            _status.value = TtsStatus.READY
        }
    }

    fun voicesFor(language: String): List<InstalledVoice> {
        val all = _voices.value
        return all.filter { it.locale.startsWith(language, ignoreCase = true) }.ifEmpty { all }
    }

    /** Системные настройки синтеза речи: выбор движка и загрузка офлайн-голосов. */
    fun settingsIntent(): Intent = Intent("com.android.settings.TTS_SETTINGS")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun shutdown() {
        runCatching { tts?.shutdown() }
        tts = null
    }
}
