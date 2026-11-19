package com.voicereader.tts

/** Только ослабление речи: исходный PCM не усиливается и не искажается. */
object VoiceVolume {
    const val DEFAULT = 1f
    const val MAX = 1f
    fun clamp(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, MAX) else DEFAULT
    fun playbackGain(value: Float): Float = clamp(value)
}
