package com.voicereader

import com.voicereader.tts.VoiceVolume
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class VoiceVolumeTest {
    @Test
    fun ordinaryVolumePreservesTheSignalAndLowVolumeAttenuatesPlayback() {
        for (sample in listOf(-1f, -.5f, 0f, .25f, 1f)) {
            assertEquals(sample, VoiceVolume.boostSample(sample, 1f), 0f)
        }
        assertEquals(0f, VoiceVolume.playbackGain(0f), 0f)
        assertEquals(.35f, VoiceVolume.playbackGain(.35f), 0f)
        assertEquals(1f, VoiceVolume.playbackGain(2f), 0f)
    }

    @Test
    fun boostMakesQuietSpeechLouderWithoutExceedingPcmLimits() {
        for (i in -1000..1000) {
            val sample = i / 1000f
            val boosted = VoiceVolume.boostSample(sample, 2f)
            assertTrue(abs(boosted) <= 1f)
            assertTrue(abs(boosted) >= abs(sample))
            if (i != 0 && abs(i) != 1000) assertTrue(abs(boosted) > abs(sample))
            assertEquals(-boosted, VoiceVolume.boostSample(-sample, 2f), .000001f)
        }
        assertEquals(0f, VoiceVolume.boostSample(0f, 2f), 0f)
    }

    @Test
    fun raisingTheSliderDoesNotLowerLoudPeaksOrQuietSpeech() {
        for (sample in listOf(.01f, .1f, .5f, .9f, 1f)) {
            var previous = sample
            for (percent in 100..200) {
                val next = VoiceVolume.boostSample(sample, percent / 100f)
                assertTrue(next >= previous - .000001f)
                previous = next
            }
        }
    }

    @Test
    fun invalidSettingsAndNonfiniteSamplesDoNotReachThePlayer() {
        assertEquals(1f, VoiceVolume.clamp(Float.NaN), 0f)
        assertEquals(1f, VoiceVolume.clamp(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0f, VoiceVolume.clamp(-1f), 0f)
        assertEquals(2f, VoiceVolume.clamp(5f), 0f)
        assertEquals(0f, VoiceVolume.boostSample(Float.NaN, 2f), 0f)
        assertEquals(0f, VoiceVolume.boostSample(Float.NEGATIVE_INFINITY, 2f), 0f)
    }
}
