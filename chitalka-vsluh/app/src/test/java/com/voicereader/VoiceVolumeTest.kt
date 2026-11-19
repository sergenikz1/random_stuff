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
        assertEquals(1f, VoiceVolume.playbackGain(10f), 0f)
    }

    @Test
    fun boostMakesQuietSpeechLouderWithoutExceedingPcmLimits() {
        for (i in -1000..1000) {
            val sample = i / 1000f
            val boosted = VoiceVolume.boostSample(sample, 10f)
            assertTrue(abs(boosted) <= 1f)
            assertTrue(abs(boosted) >= abs(sample))
            if (i != 0 && abs(i) != 1000) assertTrue(abs(boosted) > abs(sample))
            assertEquals(-boosted, VoiceVolume.boostSample(-sample, 10f), .000001f)
        }
        assertEquals(0f, VoiceVolume.boostSample(0f, 10f), 0f)
    }

    @Test
    fun raisingTheSliderDoesNotLowerLoudPeaksOrQuietSpeech() {
        for (sample in listOf(.01f, .1f, .5f, .9f, 1f)) {
            var previous = sample
            for (percent in 100..1000) {
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
        assertEquals(5f, VoiceVolume.clamp(5f), 0f)
        assertEquals(10f, VoiceVolume.clamp(10f), 0f)
        assertEquals(10f, VoiceVolume.clamp(15f), 0f)
        assertEquals(0f, VoiceVolume.boostSample(Float.NaN, 2f), 0f)
        assertEquals(0f, VoiceVolume.boostSample(Float.NEGATIVE_INFINITY, 2f), 0f)
    }

    @Test
    fun maximumGainBoostsWhispersAndExistingVolumeSettingsRemainUnchanged() {
        assertTrue(VoiceVolume.boostSample(.001f, 10f) > .0095f)
        for (savedVolume in listOf(0f, .5f, 1f, 1.5f, 2f, 5f, 10f)) {
            assertEquals(savedVolume, VoiceVolume.clamp(savedVolume), 0f)
        }
    }
}
