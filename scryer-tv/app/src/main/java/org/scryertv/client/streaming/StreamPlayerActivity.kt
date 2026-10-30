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
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
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
    private var player: ExoPlayer? = null
    private var current: VideoItem? = null
    private var next: VideoItem? = null
    private var queue: List<VideoItem> = emptyList()
    private var loadJob: Job? = null
    private var nextJob: Job? = null
    private var tickJob: Job? = null
    private var cachedNext: Pair<PlaybackInfo, Long>? = null
    private var preloadAfter = 0L
    private var cancelledNext = false
    private var foreground = false
    private var resumePlaying = true
    private var ended = false

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
        current = (savedInstanceState?.getSerializable("current") ?: intent.getSerializableExtra("video")) as? VideoItem
        cancelledNext = savedInstanceState?.getBoolean("cancelled") ?: false
        resumePlaying = savedInstanceState?.getBoolean("playing", true) ?: true
        queue = store.videos("queue")
        findViewById<Button>(R.id.player_retry).setOnClickListener { prepare() }
        nextButton.setOnClickListener { advance() }
        findViewById<Button>(R.id.cancel_next).setOnClickListener {
            cancelledNext = true; nextPanel.visibility = View.GONE; view.requestFocus()
        }
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility -> title.visibility = visibility })
    }

    override fun onStart() {
        super.onStart(); foreground = true
        if (current == null) showError("Видео не выбрано") else prepare()
    }

    private fun prepare(info: PlaybackInfo? = null) {
        val video = current ?: return
        loadJob?.cancel(); nextJob?.cancel(); tickJob?.cancel()
        savePosition(); release()
        next = null; cachedNext = null; ended = false; preloadAfter = 0L
        nextPanel.visibility = View.GONE
        title.text = video.title
        progress.visibility = View.VISIBLE; errorPanel.visibility = View.GONE
        loadJob = lifecycleScope.launch {
            try {
                val resolved = info ?: StreamRepository.resolve(video)
                if (!foreground) return@launch
                startPlayer(resolved)
                store.remember(video)
                discoverNext(video)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { showError(e.message ?: "Не удалось получить поток") }
        }
    }

    private fun startPlayer(info: PlaybackInfo) {
        val headers = if (current?.streamUrl == null) mapOf("Referer" to RutubeApi.REFERER) else emptyMap()
        // This HTTP client is separate from ScryerApi: server credentials never go to video sites.
        val source = OkHttpDataSource.Factory(RutubeApi.http)
            .setUserAgent(RutubeApi.USER_AGENT).setDefaultRequestProperties(headers)
        val tracks = DefaultTrackSelector(this).apply {
            if (store.maxHeight > 0) setParameters(buildUponParameters().setMaxVideoSize(Int.MAX_VALUE, store.maxHeight))
        }
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(source)).setTrackSelector(tracks).build()
        player = exo; view.player = exo
        exo.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        exo.setHandleAudioBecomingNoisy(true)
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                progress.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (state == Player.STATE_ENDED) {
                    ended = true
                    current?.let { store.savePosition(it.id, 0, 0) }
                    if (store.autoplay && !cancelledNext) advance()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                showError("Ошибка воспроизведения: ${error.errorCodeName}\nПовторить — заново получить адрес потока.")
            }
        })
        exo.setMediaItem(MediaItem.fromUri(info.url))
        if (!info.isLive) exo.seekTo(store.position(current!!.id))
        exo.playWhenReady = resumePlaying
        exo.prepare(); view.requestFocus()
        tickJob = lifecycleScope.launch {
            var ticks = 0
            while (true) {
                delay(1000)
                val remaining = exo.duration - exo.currentPosition
                if (++ticks % 5 == 0) savePosition()
                if (next != null && remaining in 1..60_000 && cachedNext == null && nextJob?.isActive != true && SystemClock.elapsedRealtime() >= preloadAfter) preloadNext()
                if (next != null && !cancelledNext && store.autoplay && remaining in 1..20_000) {
                    nextButton.text = "Следующая серия через ${(remaining + 999) / 1000} с\n${next!!.title}"
                    nextPanel.visibility = View.VISIBLE
                } else nextPanel.visibility = View.GONE
            }
        }
    }

    private fun discoverNext(video: VideoItem) {
        nextJob = lifecycleScope.launch {
            try {
                if (video.streamUrl != null) {
                    val index = queue.indexOfFirst { it.id == video.id }
                    next = if (index >= 0) queue.getOrNull(index + 1) else null
                } else {
                    queue = StreamRepository.episodes(video)
                    next = EpisodeOrder.next(video, queue)
                }
                if (ended && store.autoplay && !cancelledNext) advance()
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                title.text = "${video.title} · Не удалось найти следующую серию"
            }
        }
    }

    private fun preloadNext() {
        val video = next ?: return
        preloadAfter = SystemClock.elapsedRealtime() + 15_000
        nextJob = lifecycleScope.launch {
            try { cachedNext = StreamRepository.resolve(video) to SystemClock.elapsedRealtime()
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { /* Retry with a fresh URL when the episode ends. */ }
        }
    }

    private fun advance() {
        val video = next ?: return
        val resolved = cachedNext?.takeIf { SystemClock.elapsedRealtime() - it.second < 120_000 }?.first
        savePosition(); release() // Save under the old episode ID before switching.
        current = video; cancelledNext = false; resumePlaying = true
        prepare(resolved)
    }

    private fun savePosition() {
        val exo = player ?: return
        val video = current ?: return
        if (!ended && !exo.isCurrentMediaItemLive) store.savePosition(video.id, exo.currentPosition, exo.duration)
    }

    private fun release() {
        player?.release(); player = null; view.player = null
    }

    private fun showError(message: String) {
        progress.visibility = View.GONE; errorPanel.visibility = View.VISIBLE
        findViewById<TextView>(R.id.player_error).text = message
        findViewById<Button>(R.id.player_retry).requestFocus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        savePosition()
        outState.putSerializable("current", current)
        outState.putBoolean("cancelled", cancelledNext)
        outState.putBoolean("playing", player?.playWhenReady ?: resumePlaying)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        foreground = false
        resumePlaying = player?.playWhenReady ?: resumePlaying
        loadJob?.cancel(); nextJob?.cancel(); tickJob?.cancel()
        savePosition(); release()
        super.onStop()
    }

    // ComponentActivity's public dispatchKeyEvent override is incorrectly flagged by old Leanback lint metadata.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val exo = player
        if (exo != null && errorPanel.visibility != View.VISIBLE && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> { advance(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (exo.isPlaying) exo.pause() else exo.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { exo.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { exo.pause(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (!view.isControllerFullyVisible && !nextPanel.hasFocus()) {
                    val offset = if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -10_000 else 10_000
                    val target = (exo.currentPosition + offset).coerceAtLeast(0)
                    exo.seekTo(if (exo.duration > 0) target.coerceAtMost(exo.duration) else target)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER -> if (!view.isControllerFullyVisible && !nextPanel.hasFocus()) {
                    if (exo.isPlaying) exo.pause() else exo.play(); view.showController(); return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
