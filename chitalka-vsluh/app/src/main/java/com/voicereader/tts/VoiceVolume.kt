package com.voicereader.tts

import kotlin.math.abs

/** Независимая от системной громкости регулировка речи: 1 — исходный сигнал. */
object VoiceVolume {
    const val DEFAULT = 1f
    const val MAX = 2f
    const val SYSTEM_MAX = 1f

    fun clamp(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, MAX) else DEFAULT

    /** AudioTrack и системный TTS принимают громкость только в диапазоне 0–1. */
    fun playbackGain(value: Float): Float = clamp(value).coerceAtMost(SYSTEM_MAX)

    /** Усиливаем тихие участки PCM, плавно удерживая пики в пределах ±1. */
    fun boostSample(sample: Float, volume: Float): Float {
        if (!sample.isFinite()) return 0f
        val gain = clamp(volume).coerceAtLeast(1f)
        if (gain == 1f) return sample
        val bounded = sample.coerceIn(-1f, 1f)
        return bounded * gain / (1f + (gain - 1f) * abs(bounded))
    }
}
