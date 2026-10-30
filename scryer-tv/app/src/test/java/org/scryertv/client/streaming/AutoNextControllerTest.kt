package org.scryertv.client.streaming

import org.junit.Assert.*
import org.junit.Test

class AutoNextControllerTest {
    private fun AutoNextController.sample(time: Long, position: Long = 10_000, duration: Long = 30_000,
        playing: Boolean = true, ended: Boolean = false, live: Boolean = false, hasNext: Boolean = true,
        autoplay: Boolean = true, fallback: Long = 0) = tick(time, position, duration, fallback, playing, ended, live, hasNext, autoplay)

    @Test fun automaticallyAdvancesAtZeroWithoutEndEventOrPositionProgress() {
        val clock = AutoNextController()
        assertEquals(20L, clock.sample(0).seconds)
        assertTrue(clock.sample(20_000).advance)
        assertFalse(clock.sample(21_000).advance)
    }
    @Test fun growingHlsTimelineDoesNotExtendDisplayedCountdown() {
        val clock = AutoNextController()
        clock.sample(0)
        assertEquals(10L, clock.sample(10_000, duration = 40_000).seconds)
        assertTrue(clock.sample(20_000, duration = 50_000).advance)
    }
    @Test fun pausesAndBufferingDoNotConsumeCountdown() {
        val clock = AutoNextController()
        clock.sample(0)
        clock.sample(1000, playing = false)
        assertEquals(20L, clock.sample(60_000, playing = false).seconds)
        clock.sample(61_000)
        assertTrue(clock.sample(81_000).advance)
    }
    @Test fun normalEndAndCountdownCannotSwitchTwice() {
        val clock = AutoNextController()
        assertTrue(clock.sample(0, ended = true, playing = false).advance)
        assertFalse(clock.sample(1000, ended = true).advance)
    }
    @Test fun cancellationAndDisabledAutoplayKeepManualControl() {
        val clock = AutoNextController()
        assertFalse(clock.sample(0, ended = true, autoplay = false).advance)
        clock.cancelled = true
        assertFalse(clock.sample(20_000, ended = true).advance)
        assertFalse(clock.sample(21_000).show)
    }
    @Test fun seekingBackResetsCountdown() {
        val clock = AutoNextController()
        clock.sample(0); clock.sample(10_000)
        clock.seek()
        assertFalse(clock.sample(30_000, position = 0, duration = 300_000).show)
        assertFalse(clock.sample(31_000, position = 0, duration = 300_000).advance)
    }
    @Test fun lateNextDiscoveryStillAdvancesAnAlreadyEndedEpisode() {
        val clock = AutoNextController()
        assertFalse(clock.sample(0, ended = true, hasNext = false).advance)
        assertTrue(clock.sample(2000, ended = true).advance)
    }
    @Test fun unknownDurationUsesMetadataAndNeverTreatsUnsetAsNegativeTime() {
        val clock = AutoNextController()
        assertFalse(clock.sample(0, duration = -9223372036854775807L).show)
        assertEquals(20L, clock.sample(1000, duration = -9223372036854775807L, fallback = 30_000).seconds)
        assertTrue(clock.sample(21_000, duration = -9223372036854775807L, fallback = 30_000).advance)
    }
    @Test fun liveStreamsNeverCountDownOrAutomaticallyAdvance() {
        val clock = AutoNextController()
        assertFalse(clock.sample(0, live = true).show)
        assertFalse(clock.sample(100_000, live = true, ended = true).advance)
    }
    @Test fun timeNeverGoesNegativeWhenThePlayerOverrunsDuration() {
        val clock = AutoNextController()
        val result = clock.sample(0, position = 60_000)
        assertEquals(0L, result.seconds)
        assertTrue(result.advance)
    }
    @Test fun bufferingAtTheActualEndDoesNotPreventTransition() {
        val clock = AutoNextController()
        assertTrue(clock.sample(0, position = 30_000, playing = false).advance)
        assertFalse(AutoNextController().sample(0, position = 30_000, playing = false, autoplay = false).advance)
    }
}
