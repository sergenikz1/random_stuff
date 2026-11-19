package com.voicereader

import com.voicereader.tts.VoiceVolume
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceVolumeTest {
    @Test
    fun savedBoostSettingsAreResetToUnamplifiedPlayback() {
        for (oldBoost in listOf(1.5f, 2f, 5f, 10f)) {
            assertEquals(1f, VoiceVolume.clamp(oldBoost), 0f)
            assertEquals(1f, VoiceVolume.playbackGain(oldBoost), 0f)
        }
        for (volume in listOf(0f, .2f, .5f, 1f)) {
            assertEquals(volume, VoiceVolume.playbackGain(volume), 0f)
        }
    }

    @Test
    fun invalidSettingsCannotAmplifyOrInvertSpeech() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(1f, VoiceVolume.clamp(invalid), 0f)
        }
        assertEquals(0f, VoiceVolume.playbackGain(-2f), 0f)
    }
}
