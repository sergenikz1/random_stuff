package org.scryertv.client.streaming

import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.media3.datasource.HttpDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.scryertv.client.R

@OptIn(markerClass = [UnstableApi::class])
class StreamPlayerActivity : FragmentActivity() {
    private lateinit var store: StreamStore
    private lateinit var view: PlayerView
    private lateinit var progress: ProgressBar
    private lateinit var errorPanel: View
    private lateinit var title: TextView
    private lateinit var nextPanel: View
    private lateinit var nextButton: Button
    private lateinit var cancelNextButton: Button
    private lateinit var nextLabel: TextView
    private var player: Player? = null
    private var current: VideoItem? = null
    private var next: VideoItem? = null
    private var queue: List<VideoItem> = emptyList()
    private var loadJob: Job? = null
    private var retryJob: Job? = null
    private var discoveryJob: Job? = null
    private var preloadJob: Job? = null
    private var tickJob: Job? = null
    private var cachedNext: Pair<PlaybackInfo, Long>? = null
    private var preloadAfter = 0L
    private var countdown = AutoNextController()
    private val recovery = PlaybackRecovery()
    private var foreground = false
    private var resumePlaying = true
    private var ended = false
    private var live = false
    private var ready = false
    private var transitioning = false
    private var recovering = false
    private var lastPosition = 0L
    private var generation = 0
    private var decoderMode = 0
    private var handledBack = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stream_player)
        store = StreamStore(this)
        view = findViewById(R.id.player_view)
        progress = findViewById(R.id.player_progress)
        errorPanel = findViewById(R.id.player_error_panel)
        title = findViewById(R.id.player_title)
        nextPanel = findViewById(R.id.next_panel)
        nextButton = findViewById(R.id.play_next)
        cancelNextButton = findViewById(R.id.cancel_next)
        nextLabel = findViewById(R.id.next_label)
        @Suppress("DEPRECATION")
        current = (savedInstanceState?.getSerializable("current") ?: intent.getSerializableExtra("video")) as? VideoItem
        countdown.cancelled = savedInstanceState?.getBoolean("cancelled") ?: false
        resumePlaying = savedInstanceState?.getBoolean("playing", true) ?: true
        lastPosition = savedInstanceState?.getLong("position") ?: current?.let { store.position(it.id) } ?: 0L
        decoderMode = savedInstanceState?.getInt("decoder_mode") ?: 0
        queue = store.videos("queue")
        findViewById<Button>(R.id.player_retry).setOnClickListener {
            recovery.reset(); transitioning = false; recovering = false
            prepare()
        }
        nextButton.setOnClickListener { advance() }
        cancelNextButton.setOnClickListener {
            countdown.cancelled = true; hideNextPanel()
        }
        view.setControllerVisibilityListener(androidx.media3.ui.PlayerView.ControllerVisibilityListener { visibility -> title.visibility = visibility })
    }

    override fun onStart() {
        super.onStart(); foreground = true; recovery.reset()
        if (current == null) showError("Видео не выбрано") else prepare()
    }

    private fun prepare(info: PlaybackInfo? = null) {
        val video = current ?: return
        val request = ++generation
        loadJob?.cancel(); retryJob?.cancel(); discoveryJob?.cancel(); preloadJob?.cancel(); tickJob?.cancel()
        savePosition(); release()
        recovering = false
        next = null; cachedNext = null; ended = false; ready = false; live = false; preloadAfter = 0L
        val cancelled = countdown.cancelled
        countdown = AutoNextController().also { it.cancelled = cancelled }
        hideNextPanel()
        title.text = video.title
        progress.visibility = View.VISIBLE; errorPanel.visibility = View.GONE
        loadJob = lifecycleScope.launch {
            try {
                val resolved = info ?: services.resolve(video)
                if (!foreground || request != generation) return@launch
                recovering = false
                startPlayer(resolved)
                if (player == null || recovering) return@launch
                store.remember(video)
                discoverNext(video)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { if (request == generation) retryOrShow(e) }
        }
    }

    private fun startPlayer(info: PlaybackInfo) {
        val video = current ?: return
        val exo = services.player(this, video, store.maxHeight, decoderMode)
        live = info.isLive || video.isLive
        player = exo; view.player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (player !== exo || !foreground) return
                progress.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (state == Player.STATE_READY) { ready = true; transitioning = false }
                if (state == Player.STATE_ENDED) complete()
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (player !== exo) return
                if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                    countdown.seek(); lastPosition = newPosition.positionMs; ended = false
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (player === exo && foreground) {
                    // An unfinished HLS playlist may fail on its last segment instead of sending ENDED.
                    updateNext()
                    if (player === exo) retryOrShow(error)
                }
            }
        })
        val item = MediaItem.Builder().setUri(info.url).apply { info.mimeType?.let { setMimeType(it) } }.build()
        exo.setMediaItem(item)
        if (!live && lastPosition > 0) exo.seekTo(lastPosition)
        exo.playWhenReady = resumePlaying
        exo.prepare(); view.requestFocus()
        if (player !== exo) return
        tickJob = lifecycleScope.launch {
            var ticks = 0
            while (true) {
                delay(250)
                if (player !== exo || recovering) continue
                if (ready && !ended) lastPosition = exo.currentPosition
                if (++ticks % 20 == 0) savePosition()
                val duration = exo.duration.takeIf { it > 0 } ?: (current?.durationSec ?: 0) * 1000L
                val remaining = duration - exo.currentPosition
                if (next != null && !live && remaining <= 60_000 && (cachedNext == null || SystemClock.elapsedRealtime() - cachedNext!!.second >= 15_000) && preloadJob?.isActive != true && SystemClock.elapsedRealtime() >= preloadAfter) preloadNext()
                updateNext()
            }
        }
    }

    private fun complete() {
        if (!ended) {
            ended = true; lastPosition = 0
            current?.let { store.savePosition(it.id, 0, 0) }
        }
        updateNext()
    }

    private fun updateNext() {
        val exo = player ?: return
        if (recovering || errorPanel.visibility == View.VISIBLE) return
        val decision = countdown.tick(SystemClock.elapsedRealtime(), exo.currentPosition, exo.duration,
            (current?.durationSec ?: 0) * 1000L, exo.isPlaying, ended, live, next != null, store.autoplay && exo.playWhenReady)
        if (decision.advance) {
            ended = true; lastPosition = 0
            current?.let { store.savePosition(it.id, 0, 0) }
            advance()
            return
        }
        if (decision.show) {
            nextLabel.text = if (store.autoplay) getString(R.string.player_next_countdown, decision.seconds, next!!.title)
                else getString(R.string.player_next_manual, next!!.title)
            nextPanel.visibility = View.VISIBLE
        } else hideNextPanel()
    }

    private fun discoverNext(video: VideoItem) {
        val request = generation
        discoveryJob = lifecycleScope.launch {
            try {
                if (video.streamUrl != null) {
                    val index = queue.indexOfFirst { it.id == video.id }
                    next = if (index >= 0) queue.getOrNull(index + 1) else null
                } else {
                    // An explicitly selected episode list remains usable when search is temporarily unavailable.
                    next = EpisodeOrder.next(video, queue)
                    if (next == null) {
                        val candidates = services.episodes(video)
                        if (request != generation) return@launch
                        queue = candidates
                        next = EpisodeOrder.next(video, queue)
                    }
                }
                if (request == generation) updateNext()
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                if (request == generation) title.text = getString(R.string.player_next_not_found, video.title)
            }
        }
    }

    private fun preloadNext() {
        val video = next ?: return
        val request = generation
        preloadAfter = SystemClock.elapsedRealtime() + 15_000
        preloadJob = lifecycleScope.launch {
            try {
                val resolved = services.resolve(video)
                if (request == generation && next?.id == video.id) cachedNext = resolved to SystemClock.elapsedRealtime()
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { /* Resolve a fresh address when the transition actually occurs. */ }
        }
    }

    private fun advance() {
        val video = next ?: return
        if (transitioning) return
        transitioning = true
        val resolved = cachedNext?.takeIf { SystemClock.elapsedRealtime() - it.second < 15_000 }?.first
        savePosition(); release()
        current = video; countdown = AutoNextController(); recovery.reset()
        lastPosition = 0; resumePlaying = true; recovering = false
        prepare(resolved)
    }

    private fun retryOrShow(error: Exception) {
        if (recovering || !foreground) return
        val playbackError = error as? PlaybackException
        var cause: Throwable? = error
        var status: Int? = null
        repeat(8) {
            if (cause is HttpDataSource.InvalidResponseCodeException) status = (cause as HttpDataSource.InvalidResponseCodeException).responseCode
            cause = cause?.cause
        }
        val wait = recovery.nextDelayMs(playbackError?.errorCode, status)
        if (playbackError?.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            playbackError?.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) {
            decoderMode = (decoderMode + 1).coerceAtMost(2)
        }
        resumePlaying = player?.playWhenReady ?: resumePlaying
        savePosition(); tickJob?.cancel(); discoveryJob?.cancel(); preloadJob?.cancel()
        if (wait == null) {
            release(); transitioning = false
            val detail = playbackError?.errorCodeName ?: error.message.orEmpty()
            showError(getString(R.string.player_failed, detail + (status?.let { " · HTTP $it" } ?: "")))
            return
        }
        recovering = true; transitioning = false
        // Keep the resume position and pause/play intent while fetching a fresh URL and rebuilding the player.
        release(); hideNextPanel()
        errorPanel.visibility = View.GONE; progress.visibility = View.VISIBLE
        title.setText(R.string.player_recovering); title.visibility = View.VISIBLE
        val failedGeneration = generation
        retryJob = lifecycleScope.launch {
            delay(wait)
            if (foreground && recovering && generation == failedGeneration) prepare()
        }
    }

    private fun savePosition() {
        val exo = player ?: return
        val video = current ?: return
        if (!ended && !live && ready) {
            if (exo.currentPosition > 0) lastPosition = exo.currentPosition
            store.savePosition(video.id, lastPosition, exo.duration)
        }
    }

    private fun release() {
        val old = player
        player = null; view.player = null
        old?.release()
    }

    private fun hideNextPanel() {
        val focused = nextPanel.hasFocus()
        nextPanel.visibility = View.GONE
        if (focused) view.requestFocus()
    }

    private fun showError(message: String) {
        hideNextPanel(); progress.visibility = View.GONE; errorPanel.visibility = View.VISIBLE
        findViewById<TextView>(R.id.player_error).text = message
        findViewById<Button>(R.id.player_retry).requestFocus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        savePosition()
        outState.putSerializable("current", current)
        outState.putBoolean("cancelled", countdown.cancelled)
        outState.putBoolean("playing", player?.playWhenReady ?: resumePlaying)
        outState.putLong("position", lastPosition)
        outState.putInt("decoder_mode", decoderMode)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        foreground = false; generation++
        resumePlaying = player?.playWhenReady ?: resumePlaying
        recovering = false
        loadJob?.cancel(); retryJob?.cancel(); discoveryJob?.cancel(); preloadJob?.cancel(); tickJob?.cancel()
        savePosition(); release()
        super.onStop()
    }

    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && handledBack) {
            handledBack = false; return true
        }
        val exo = player
        if (exo != null && errorPanel.visibility != View.VISIBLE && event.action == KeyEvent.ACTION_DOWN) {
            if (nextPanel.visibility == View.VISIBLE) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        view.hideController()
                        when {
                            !nextPanel.hasFocus() -> nextButton.requestFocus()
                            event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && nextButton.hasFocus() -> cancelNextButton.requestFocus()
                            event.keyCode == KeyEvent.KEYCODE_DPAD_UP && cancelNextButton.hasFocus() -> nextButton.requestFocus()
                            else -> view.requestFocus()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (nextPanel.hasFocus()) {
                        if (event.keyCode == KeyEvent.KEYCODE_BACK) handledBack = true
                        view.requestFocus(); return true
                    }
                }
            }
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> { advance(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (exo.isPlaying) exo.pause() else exo.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { exo.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { exo.pause(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (!view.isControllerFullyVisible && !nextPanel.hasFocus()) {
                    countdown.seek()
                    val offset = if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -10_000 else 10_000
                    val target = (exo.currentPosition + offset).coerceAtLeast(0)
                    exo.seekTo(if (exo.duration > 0) target.coerceAtMost(exo.duration) else target)
                    lastPosition = target; return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (!view.isControllerFullyVisible && !nextPanel.hasFocus()) {
                    if (exo.isPlaying) exo.pause() else exo.play(); view.showController(); return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        internal var services: PlaybackServices = DefaultPlaybackServices
    }
}
