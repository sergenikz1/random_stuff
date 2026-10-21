package com.voicereader.data

import android.content.Context
import android.content.SharedPreferences
import com.voicereader.tts.VoiceKind
import com.voicereader.tts.VoiceProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Сколько текста показывать вокруг озвучиваемого предложения. */
enum class TextWindow(val label: String, val before: Int, val after: Int) {
    MINIMAL("Минимум", 1, 3),
    COMPACT("Компактно", 3, 8),
    NORMAL("Обычно", 8, 20),
    WIDE("Много", 25, 60),
    ALL("Вся книга", Int.MAX_VALUE, Int.MAX_VALUE)
}

/** Как вести себя со сторонней музыкой, пока читает голос. */
enum class MusicMode(val label: String, val hint: String) {
    PAUSE("Ставить на паузу", "Другие плееры замолкают, пока звучит книга"),
    DUCK("Приглушать", "Музыка играет тише, голос слышно лучше"),
    MIX("Не трогать", "Музыка играет как обычно, вместе с голосом")
}

data class SettingsState(
    val profiles: List<VoiceProfile> = VoiceProfile.DEFAULTS,
    val activeVoice: Int = 0,
    val fontSizeSp: Float = 20f,
    val lineHeightMult: Float = 1.5f,
    val textWindow: TextWindow = TextWindow.NORMAL,
    val keepScreenOn: Boolean = true,
    val autoScroll: Boolean = true,
    val musicMode: MusicMode = MusicMode.MIX,
    /** Режим «только текст»: без панелей и системных полос. */
    val focusMode: Boolean = false,
    /** Быстрое чтение: слов в минуту и плавный разгон до этой скорости. */
    val speedWpm: Int = 300,
    val speedRamp: Boolean = true
) {
    val activeProfile: VoiceProfile get() = profiles.getOrElse(activeVoice) { VoiceProfile.DEFAULTS[0] }
}

/** Настройки в SharedPreferences + StateFlow для Compose. */
object Settings {

    const val SPEED_MIN = 100
    const val SPEED_MAX = 1000

    private lateinit var prefs: SharedPreferences
    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _state.value = read()
    }

    private fun read(): SettingsState {
        val profiles = VoiceProfile.DEFAULTS.mapIndexed { i, def ->
            VoiceProfile(
                label = prefs.getString("voice_${i}_label", def.label) ?: def.label,
                kind = runCatching { VoiceKind.valueOf(prefs.getString("voice_${i}_kind", def.kind.name)!!) }
                    .getOrDefault(def.kind),
                neuralVoiceId = prefs.getString("voice_${i}_neural", def.neuralVoiceId),
                systemVoiceName = prefs.getString("voice_${i}_system", null),
                pitch = prefs.getFloat("voice_${i}_pitch", def.pitch),
                rate = prefs.getFloat("voice_${i}_rate", def.rate)
            )
        }
        return SettingsState(
            profiles = profiles,
            activeVoice = prefs.getInt("active_voice", 0).coerceIn(0, 2),
            fontSizeSp = prefs.getFloat("font_size", 20f),
            lineHeightMult = prefs.getFloat("line_height", 1.5f),
            textWindow = runCatching { TextWindow.valueOf(prefs.getString("text_window", "NORMAL")!!) }
                .getOrDefault(TextWindow.NORMAL),
            keepScreenOn = prefs.getBoolean("keep_screen_on", true),
            autoScroll = prefs.getBoolean("auto_scroll", true),
            musicMode = runCatching { MusicMode.valueOf(prefs.getString("music_mode", "MIX")!!) }
                .getOrDefault(MusicMode.MIX),
            focusMode = prefs.getBoolean("focus_mode", false),
            speedWpm = prefs.getInt("speed_wpm", 300).coerceIn(SPEED_MIN, SPEED_MAX),
            speedRamp = prefs.getBoolean("speed_ramp", true)
        )
    }

    private fun write(state: SettingsState) {
        prefs.edit().apply {
            state.profiles.forEachIndexed { i, p ->
                putString("voice_${i}_label", p.label)
                putString("voice_${i}_kind", p.kind.name)
                putString("voice_${i}_neural", p.neuralVoiceId)
                putString("voice_${i}_system", p.systemVoiceName)
                putFloat("voice_${i}_pitch", p.pitch)
                putFloat("voice_${i}_rate", p.rate)
            }
            putInt("active_voice", state.activeVoice)
            putFloat("font_size", state.fontSizeSp)
            putFloat("line_height", state.lineHeightMult)
            putString("text_window", state.textWindow.name)
            putBoolean("keep_screen_on", state.keepScreenOn)
            putBoolean("auto_scroll", state.autoScroll)
            putString("music_mode", state.musicMode.name)
            putBoolean("focus_mode", state.focusMode)
            putInt("speed_wpm", state.speedWpm)
            putBoolean("speed_ramp", state.speedRamp)
        }.apply()
    }

    fun update(transform: (SettingsState) -> SettingsState) {
        val next = transform(_state.value)
        _state.value = next
        write(next)
    }

    fun updateProfile(slot: Int, transform: (VoiceProfile) -> VoiceProfile) = update { s ->
        s.copy(profiles = s.profiles.mapIndexed { i, p -> if (i == slot) transform(p) else p })
    }

}
