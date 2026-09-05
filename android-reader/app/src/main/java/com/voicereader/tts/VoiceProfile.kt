package com.voicereader.tts

/**
 * Один из трёх пользовательских голосов.
 * Голос = системный голос движка + собственные высота тона и темп,
 * поэтому три разных варианта доступны даже там, где движок предлагает один голос.
 */
data class VoiceProfile(
    val label: String,
    /** Имя системного голоса (Voice.getName()), null — голос по умолчанию для языка. */
    val voiceName: String?,
    val pitch: Float,
    val rate: Float
) {
    companion object {
        val DEFAULTS = listOf(
            VoiceProfile("Голос 1 · ровный", null, 1.00f, 1.00f),
            VoiceProfile("Голос 2 · ниже и мягче", null, 0.88f, 0.95f),
            VoiceProfile("Голос 3 · выше и живее", null, 1.12f, 1.06f)
        )
    }
}

/** Системный голос, доступный офлайн. */
data class InstalledVoice(
    val name: String,
    val locale: String,
    val quality: Int,
    val offline: Boolean
) {
    val displayName: String
        get() {
            val short = name.removePrefix("$locale-").removePrefix("${locale.lowercase()}-")
            return short.replace('_', ' ').replace("-", " ").trim().ifBlank { name }
        }
}
