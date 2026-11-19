package com.voicereader

import com.voicereader.data.MusicMode
import com.voicereader.player.CancelFocusTask
import com.voicereader.player.FocusChange
import com.voicereader.player.MusicFocusController
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class MusicFocusControllerTest {
    private class Task(val delay: Long, val action: () -> Unit) { var canceled = false }
    private class Rig {
        val requests = mutableListOf<MusicMode>()
        val pauses = mutableListOf<Boolean>()
        var resumes = 0
        var abandoned = 0
        var foreground = true
        var call = false
        val answers = ArrayDeque<Boolean>()
        val tasks = mutableListOf<Task>()
        val controller = MusicFocusController(
            request = { requests += it; if (answers.isEmpty()) true else answers.removeFirst() },
            abandon = { abandoned++ },
            schedule = { delay, action ->
                val task = Task(delay, action); tasks += task
                CancelFocusTask { task.canceled = true }
            },
            canRequest = { foreground },
            communicationActive = { call },
            pauseReading = { pauses += it },
            resumeReading = { resumes++ }
        )
        fun next(): Long {
            while (tasks.firstOrNull()?.canceled == true) tasks.removeAt(0)
            val task = tasks.removeAt(0)
            task.action()
            return task.delay
        }
        fun pending() = tasks.count { !it.canceled }
    }

    @Test
    fun duckingIsRestoredForEveryNewTrackWithoutRestartingReading() {
        val r = Rig(); r.controller.update(true, MusicMode.DUCK)
        repeat(3) {
            r.controller.onFocusChange(FocusChange.LOSS)
            assertEquals(350L, r.next())
            assertEquals(it + 2, r.requests.size)
        }
        assertTrue(r.requests.all { it == MusicMode.DUCK })
        assertTrue(r.pauses.isEmpty())
        assertEquals(0, r.resumes)
    }

    @Test
    fun rapidLossEventsCoalesceAndCanceledCallbacksCannotStealFocus() {
        val r = Rig(); r.controller.update(true, MusicMode.DUCK)
        r.controller.onFocusChange(FocusChange.LOSS)
        val obsolete = r.tasks.last()
        r.controller.onFocusChange(FocusChange.LOSS)
        obsolete.action()
        assertEquals(1, r.requests.size)
        assertEquals(1, r.pending())
        r.next()
        assertEquals(2, r.requests.size)
    }

    @Test
    fun focusGainBeforeRestoreStillReappliesDuckingToTheNewTrack() {
        val r = Rig(); r.controller.update(true, MusicMode.DUCK)
        r.controller.onFocusChange(FocusChange.LOSS)
        r.controller.onFocusChange(FocusChange.GAIN)
        assertEquals(1, r.pending())
        r.next()
        assertEquals(2, r.requests.size)
        assertTrue(r.pauses.isEmpty())
    }

    @Test
    fun newMusicStreamReappliesDuckingEvenWithoutFocusLossNotification() {
        val r = Rig(); r.controller.update(true, MusicMode.DUCK)
        r.controller.musicPlaybackStarted()
        assertEquals(350L, r.next())
        assertEquals(listOf(MusicMode.DUCK, MusicMode.DUCK), r.requests)
        assertTrue(r.pauses.isEmpty())
    }

    @Test
    fun anotherPlayerMayDuckRequestDoesNotLowerOrPauseTheReadingVoice() {
        val r = Rig(); r.controller.update(true, MusicMode.DUCK)
        r.controller.onFocusChange(FocusChange.CAN_DUCK)
        assertTrue(r.pauses.isEmpty())
        r.next()
        assertEquals(2, r.requests.size)
    }

    @Test
    fun newMusicStreamDoesNotInterruptCallsOrAStoppedReader() {
        val r = Rig(); r.controller.update(true, MusicMode.DUCK)
        r.call = true
        r.controller.musicPlaybackStarted()
        assertEquals(0, r.pending())
        r.call = false
        r.controller.stop()
        r.controller.musicPlaybackStarted()
        assertEquals(0, r.pending())
        assertEquals(1, r.requests.size)
    }

    @Test
    fun manualPauseCancelsRestoreAndIgnoresLateGain() {
        val r = Rig(); r.controller.update(true, MusicMode.DUCK)
        r.controller.onFocusChange(FocusChange.LOSS)
        r.controller.musicPlaybackStarted()
        val obsolete = r.tasks.last()
        val releasedBeforePause = r.abandoned
        r.controller.stop()
        assertTrue(r.abandoned > releasedBeforePause)
        obsolete.action()
        r.controller.onFocusChange(FocusChange.GAIN)
        assertEquals(1, r.requests.size)
        assertEquals(0, r.resumes)
        r.controller.update(true, MusicMode.DUCK)
        assertEquals(2, r.requests.size)
    }

    @Test
    fun mixModeCancelsPendingRestoreAndNeverRequestsFocus() {
        val r = Rig(); r.controller.update(true, MusicMode.DUCK)
        r.controller.onFocusChange(FocusChange.LOSS)
        val obsolete = r.tasks.last()
        val releasedBeforeMix = r.abandoned
        r.controller.update(true, MusicMode.MIX)
        assertTrue(r.abandoned > releasedBeforeMix)
        obsolete.action()
        r.controller.onFocusChange(FocusChange.LOSS)
        r.controller.musicPlaybackStarted()
        assertEquals(1, r.requests.size)
        assertEquals(0, r.pending())
        assertTrue(r.pauses.isEmpty())
    }

    @Test
    fun rejectedRestoreRetriesWithBackoffAndKeepsReading() {
        val r = Rig(); r.controller.update(true, MusicMode.DUCK)
        r.answers.addAll(listOf(false, false, true))
        r.controller.onFocusChange(FocusChange.LOSS)
        assertEquals(350L, r.next())
        assertEquals(1000L, r.next())
        assertEquals(2000L, r.next())
        assertEquals(4, r.requests.size)
        assertEquals(0, r.pending())
        assertTrue(r.pauses.isEmpty())
    }

    @Test
    fun backgroundFocusIsRequestedOnlyAfterForegroundServiceIsReady() {
        val r = Rig(); r.foreground = false
        r.controller.update(true, MusicMode.DUCK)
        assertTrue(r.requests.isEmpty())
        r.foreground = true
        r.controller.foregroundReady()
        r.controller.foregroundReady()
        assertEquals(listOf(MusicMode.DUCK), r.requests)
    }

    @Test
    fun temporaryInterruptionWaitsForGainAndResumesOnce() {
        val r = Rig(); r.controller.update(true, MusicMode.DUCK)
        r.call = true
        r.controller.onFocusChange(FocusChange.TRANSIENT_LOSS)
        r.controller.update(false, MusicMode.DUCK)
        assertEquals(listOf(true), r.pauses)
        assertEquals(0, r.pending())
        r.call = false
        r.controller.onFocusChange(FocusChange.GAIN)
        r.controller.update(true, MusicMode.DUCK)
        r.controller.onFocusChange(FocusChange.GAIN)
        assertEquals(1, r.resumes)
        assertEquals(2, r.requests.size)
    }

    @Test
    fun manualStopDuringCallPreventsAutomaticResume() {
        val r = Rig(); r.controller.update(true, MusicMode.DUCK)
        r.controller.onFocusChange(FocusChange.TRANSIENT_LOSS)
        r.controller.stop()
        r.controller.onFocusChange(FocusChange.GAIN)
        assertEquals(0, r.resumes)
        assertEquals(0, r.pending())
    }

    @Test
    fun pauseModePausesInsteadOfFightingOtherPlayers() {
        val r = Rig(); r.controller.update(true, MusicMode.PAUSE)
        r.controller.onFocusChange(FocusChange.LOSS)
        assertEquals(listOf(MusicMode.PAUSE), r.requests)
        assertEquals(listOf(false), r.pauses)
        assertEquals(0, r.pending())
    }

    @Test
    fun restoreDoesNotTakeFocusAwayFromACall() {
        val r = Rig(); r.controller.update(true, MusicMode.DUCK)
        r.controller.onFocusChange(FocusChange.LOSS)
        r.call = true
        r.next()
        assertEquals(1, r.requests.size)
        assertEquals(listOf(false), r.pauses)
        assertEquals(0, r.pending())
    }

    @Test
    fun changingModeDuringInterruptionCancelsAutomaticResume() {
        val r = Rig(); r.controller.update(true, MusicMode.DUCK)
        r.controller.onFocusChange(FocusChange.TRANSIENT_LOSS)
        r.controller.update(false, MusicMode.MIX)
        r.controller.onFocusChange(FocusChange.GAIN)
        assertEquals(listOf(true, false), r.pauses)
        assertEquals(0, r.resumes)
    }
}
