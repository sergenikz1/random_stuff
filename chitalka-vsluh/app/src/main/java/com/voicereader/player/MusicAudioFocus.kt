package com.voicereader.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import com.voicereader.data.MusicMode

/** Android-адаптер. Все события фокуса и отложенные попытки обрабатываются на main. */
class MusicAudioFocus(context: Context, pause: (Boolean) -> Unit, resume: () -> Unit) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private var foreground = false
    private var request: AudioFocusRequest? = null
    private var generation = 0
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null
    private var musicPlayers = emptySet<AudioPlaybackConfiguration>()
    private val controller = MusicFocusController(
        request = ::requestFocus,
        abandon = ::abandonFocus,
        schedule = { delay, action ->
            val task = Runnable(action)
            main.postDelayed(task, delay)
            CancelFocusTask { main.removeCallbacks(task) }
        },
        canRequest = { foreground },
        communicationActive = { audio.mode != AudioManager.MODE_NORMAL },
        pauseReading = pause,
        resumeReading = resume
    )

    fun update(playing: Boolean, mode: MusicMode) {
        observeMusic(playing && mode == MusicMode.DUCK)
        controller.update(playing, mode)
    }
    fun stop() {
        observeMusic(false)
        controller.stop()
    }
    fun foregroundChanged(ready: Boolean) {
        foreground = ready
        if (ready) controller.foregroundReady()
    }

    private fun activeMusic(configurations: List<AudioPlaybackConfiguration>) =
        // Без системных разрешений Android возвращает активные обезличенные потоки.
        configurations.filter {
            it.audioAttributes.usage == AudioAttributes.USAGE_MEDIA &&
                it.audioAttributes.contentType != AudioAttributes.CONTENT_TYPE_SPEECH
        }.toSet()

    private fun observeMusic(enabled: Boolean) {
        if (!enabled) {
            val previous = playbackCallback
            playbackCallback = null
            previous?.let { runCatching { audio.unregisterAudioPlaybackCallback(it) } }
            musicPlayers = emptySet()
        } else if (playbackCallback == null) {
            val callback = object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                    if (playbackCallback !== this) return
                    val current = activeMusic(configs)
                    val started = (current - musicPlayers).isNotEmpty()
                    musicPlayers = current
                    if (started) controller.musicPlaybackStarted()
                }
            }
            runCatching {
                musicPlayers = activeMusic(audio.activePlaybackConfigurations)
                playbackCallback = callback
                audio.registerAudioPlaybackCallback(callback, main)
            }.onFailure {
                playbackCallback = null
                musicPlayers = emptySet()
                runCatching { audio.unregisterAudioPlaybackCallback(callback) }
            }
        }
    }

    private fun requestFocus(mode: MusicMode): Boolean {
        abandonFocus()
        val ticket = generation
        val gain = if (mode == MusicMode.DUCK) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK else AudioManager.AUDIOFOCUS_GAIN
        val focus = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ event ->
                if (ticket == generation) {
                    val change = when (event) {
                        AudioManager.AUDIOFOCUS_GAIN -> FocusChange.GAIN
                        AudioManager.AUDIOFOCUS_LOSS -> FocusChange.LOSS
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> FocusChange.TRANSIENT_LOSS
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> FocusChange.CAN_DUCK
                        else -> null
                    }
                    change?.let(controller::onFocusChange)
                }
            }, main)
            .build()
        request = focus
        val granted = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) abandonFocus()
        return granted
    }

    private fun abandonFocus() {
        generation++
        val previous = request
        request = null
        previous?.let { audio.abandonAudioFocusRequest(it) }
    }
}
