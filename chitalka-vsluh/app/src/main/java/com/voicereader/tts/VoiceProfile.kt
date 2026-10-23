package com.voicereader.tts

enum class VoiceKind { NEURAL, SYSTEM }

/**
 * Один из трёх пользовательских голосов.
 * NEURAL — встроенная нейросетевая модель (звучит естественно, работает офлайн).
 * SYSTEM — голос системного движка Android, запасной вариант и языки помимо русского.
 */
data class VoiceProfile(
    val label: String,
    val kind: VoiceKind,
    /** id встроенного голоса из [VoiceCatalog], если kind == NEURAL. */
    val neuralVoiceId: String?,
    /** Voice.getName() системного движка, если kind == SYSTEM. */
    val systemVoiceName: String?,
    /** Высота тона — только для системных голосов, нейросетевая модель её не поддерживает. */
    val pitch: Float,
    /** Темп речи, множитель к нормальной скорости. */
    val rate: Float
) {
    companion object {
        val DEFAULTS = listOf(
            VoiceProfile("Ирина", VoiceKind.NEURAL, "irina", null, 1.0f, 1.0f),
            VoiceProfile("Денис", VoiceKind.NEURAL, "denis", null, 1.0f, 1.0f),
            VoiceProfile("Руслан", VoiceKind.NEURAL, "ruslan", null, 1.0f, 1.0f)
        )
    }
}

/** Системный голос, доступный офлайн. */
data class InstalledVoice(
    val name: String,
    val locale: String,
    val quality: Int,
    /** Имя для интерфейса, см. [SystemVoiceNames]. */
    val title: String = name
) {
    val displayName: String
        get() {
            val short = name.removePrefix("$locale-").removePrefix("${locale.lowercase()}-")
            return short.replace('_', ' ').replace("-", " ").trim().ifBlank { name }
        }
}
