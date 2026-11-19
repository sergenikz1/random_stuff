package com.voicereader.player

import com.voicereader.data.MusicMode

fun interface CancelFocusTask { fun cancel() }
enum class FocusChange { GAIN, LOSS, TRANSIENT_LOSS, CAN_DUCK }

/** Политика аудиофокуса без Android: восстановление ducking не перезапускает голос. */
class MusicFocusController(
    private val request: (MusicMode) -> Boolean,
    private val abandon: () -> Unit,
    private val schedule: (Long, () -> Unit) -> CancelFocusTask,
    private val canRequest: () -> Boolean = { true },
    private val communicationActive: () -> Boolean = { false },
    private val pauseReading: (Boolean) -> Unit,
    private val resumeReading: () -> Unit
) {
    private var playing = false
    private var mode = MusicMode.MIX
    private var held = false
    private var waitingForGain = false
    private var retry: CancelFocusTask? = null
    private var epoch = 0
    private var failures = 0

    fun update(playing: Boolean, mode: MusicMode) {
        if (this.playing == playing && this.mode == mode) return
        val changedMode = this.mode != mode
        val canceledInterruption = changedMode && waitingForGain
        this.playing = playing
        this.mode = mode
        cancelRetry()
        if (changedMode) {
            waitingForGain = false
            held = false
            failures = 0
            abandon()
        }
        if (canceledInterruption) pauseReading(false)
        if (!playing) {
            if (!waitingForGain) { held = false; abandon() }
            return
        }
        waitingForGain = false
        if (mode == MusicMode.MIX) { held = false; abandon(); return }
        if (!held) requestNow()
    }

    /** Вызывается после startForeground: Android 15 разрешает запрос в фоне только тогда. */
    fun foregroundReady() {
        if (playing && mode != MusicMode.MIX && !held && !waitingForGain) {
            cancelRetry()
            requestNow()
        }
    }

    fun stop() {
        playing = false
        waitingForGain = false
        held = false
        failures = 0
        cancelRetry()
        abandon()
    }

    /** Некоторые плееры создают новый AudioTrack без нового уведомления о потере фокуса. */
    fun musicPlaybackStarted() {
        if (playing && mode == MusicMode.DUCK && !waitingForGain && !communicationActive()) {
            held = false
            failures = 0
            restoreAfter(350)
        }
    }

    fun onFocusChange(change: FocusChange) {
        if (mode == MusicMode.MIX) return
        if (change == FocusChange.GAIN) {
            if (!playing && !waitingForGain) return
            val recovering = !held
            held = true
            cancelRetry()
            if (waitingForGain) {
                waitingForGain = false
                resumeReading()
                if (mode == MusicMode.DUCK) {
                    held = false
                    restoreAfter(350)
                }
            } else if (recovering && playing && mode == MusicMode.DUCK) {
                // Новый трек мог уже стартовать: заново применяем duck к его плееру.
                held = false
                restoreAfter(350)
            }
            return
        }
        if (!playing) return
        held = false
        cancelRetry()
        if (change != FocusChange.TRANSIENT_LOSS && mode == MusicMode.DUCK && !communicationActive()) {
            failures = 0
            restoreAfter(350)
        } else if (change != FocusChange.LOSS) {
            waitingForGain = true
            playing = false
            pauseReading(true)
        } else {
            stop()
            pauseReading(false)
        }
    }

    private fun requestNow() {
        if (!playing || mode == MusicMode.MIX || !canRequest()) return
        if (communicationActive()) { stop(); pauseReading(false); return }
        held = request(mode)
        if (held) failures = 0
        else if (mode == MusicMode.DUCK) {
            failures++
            restoreAfter(minOf(5000L, 1000L shl (failures - 1).coerceAtMost(3)))
        } else {
            stop()
            pauseReading(false)
        }
    }

    private fun restoreAfter(delayMs: Long) {
        cancelRetry()
        val ticket = epoch
        retry = schedule(delayMs) {
            if (ticket == epoch && playing && mode == MusicMode.DUCK && !waitingForGain) {
                retry = null
                requestNow()
            }
        }
    }

    private fun cancelRetry() {
        epoch++
        retry?.cancel()
        retry = null
    }
}
