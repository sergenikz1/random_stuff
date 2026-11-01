package org.scryertv.client.streaming

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.AudioAttributes
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Size
import androidx.media3.ui.PlayerView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.scryertv.client.R
import java.io.IOException
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class StreamPlayerActivityTest {
    private val first = VideoItem("first", "Episode 1", streamUrl = "https://example.org/1.mp4")
    private val second = VideoItem("second", "Episode 2", streamUrl = "https://example.org/2.mp4")
    private lateinit var backend: Backend
    private var controller: ActivityController<StreamPlayerActivity>? = null
    private val activity get() = controller!!.get()

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("streaming", Context.MODE_PRIVATE).edit().clear().commit()
        StreamStore(app).putVideos("queue", listOf(first, second))
        backend = Backend()
        StreamPlayerActivity.services = backend
    }
    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        StreamPlayerActivity.services = DefaultPlaybackServices
    }
    private fun launch() {
        val app = RuntimeEnvironment.getApplication()
        controller = Robolectric.buildActivity(StreamPlayerActivity::class.java,
            Intent(app, StreamPlayerActivity::class.java).putExtra("video", first)).setup().visible()
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun elapse(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun key(code: Int) {
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    @Test fun timerAutomaticallyStartsNextEpisodeEvenWithoutPlayerEndEvent() {
        launch(); elapse(21_000)
        assertEquals(listOf("first", "second"), backend.started)
        assertEquals(0L, StreamStore(activity).position(first.id))
        backend.sessions.first().listener!!.onPlaybackStateChanged(Player.STATE_ENDED)
        assertEquals(2, backend.started.size)
    }
    @Test fun remoteArrowsReachCardAndOkStartsNextWithoutMouse() {
        launch(); elapse(250)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.next_panel).visibility)
        key(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue(activity.findViewById<Button>(R.id.play_next).hasFocus())
        key(KeyEvent.KEYCODE_BACK)
        assertFalse(activity.isFinishing)
        assertTrue(activity.findViewById<PlayerView>(R.id.player_view).hasFocus())
        key(KeyEvent.KEYCODE_DPAD_UP)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("first", "second"), backend.started)
    }
    @Test fun remoteCanCancelAutomaticNextAndReturnToPlayer() {
        launch(); elapse(250)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(activity.findViewById<Button>(R.id.play_next).hasFocus())
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(activity.findViewById<Button>(R.id.cancel_next).hasFocus())
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        elapse(21_000)
        assertEquals(listOf("first"), backend.started)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.next_panel).visibility)
        assertTrue(activity.findViewById<PlayerView>(R.id.player_view).hasFocus())
    }
    @Test fun temporaryStartupFailureRetriesWithoutPressingPlay() {
        launch()
        backend.sessions.last().listener!!.onPlayerError(PlaybackException("Temporary network failure", IOException(), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        elapse(500)
        assertEquals(listOf("first", "first"), backend.started)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.player_error_panel).visibility)
        assertEquals(2, backend.resolved.count { it == "first" })
    }
    @Test fun failingUrlResolutionRetriesTwiceThenStartsWithoutManualPlay() {
        backend.resolutionFailures = 2
        launch(); elapse(2000)
        assertEquals(listOf("first"), backend.started)
        assertEquals(3, backend.resolved.count { it == "first" })
        assertEquals(View.GONE, activity.findViewById<View>(R.id.player_error_panel).visibility)
    }
    @Test fun retryPreservesThePausedPlaybackIntent() {
        launch()
        backend.sessions.last().playing = false
        backend.sessions.last().listener!!.onPlayerError(PlaybackException("Timeout", IOException(), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        elapse(500)
        assertFalse(backend.sessions.last().playing)
    }
    @Test fun endEventWhilePausedDoesNotStartAnotherEpisode() {
        launch()
        backend.sessions.last().playing = false
        backend.sessions.last().listener!!.onPlaybackStateChanged(Player.STATE_ENDED)
        elapse(1000)
        assertEquals(listOf("first"), backend.started)
    }
    @Test fun decodingFailedAutomaticallyRetriesWithCompatibleDecoder() {
        launch()
        backend.sessions.last().listener!!.onPlayerError(PlaybackException("Decoding failed", IOException(), PlaybackException.ERROR_CODE_DECODING_FAILED))
        elapse(500)
        assertEquals(listOf(0, 1), backend.decoderModes)
        assertEquals(listOf("first", "first"), backend.started)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.player_error_panel).visibility)
    }
    @Test fun missingLastHlsSegmentStartsNextRatherThanRestartingFinishedEpisode() {
        launch()
        backend.sessions.last().position = 30_000L
        backend.sessions.last().listener!!.onPlayerError(PlaybackException("Missing last segment", IOException(), PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertEquals(listOf("first", "second"), backend.started)
    }
    @Test fun repeatedFailuresStopAndOfferManualRetry() {
        launch()
        repeat(3) { index ->
            backend.sessions.last().listener!!.onPlayerError(PlaybackException("Network failure", IOException(), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
            elapse(if (index == 0) 500 else 1500)
        }
        assertEquals(3, backend.started.size)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.player_error_panel).visibility)
        assertTrue(activity.findViewById<Button>(R.id.player_retry).hasFocus())
    }

    private class Session {
        var listener: Player.Listener? = null
        var playing = true
        var position = 10_000L
        val player: Player = mock(Player::class.java)
        init {
            doReturn(Looper.getMainLooper()).`when`(player).applicationLooper
            doReturn(Player.Commands.EMPTY).`when`(player).availableCommands
            doReturn(Timeline.EMPTY).`when`(player).currentTimeline
            doReturn(Tracks.EMPTY).`when`(player).currentTracks
            doReturn(MediaMetadata.EMPTY).`when`(player).mediaMetadata
            doReturn(MediaMetadata.EMPTY).`when`(player).playlistMetadata
            doReturn(PlaybackParameters.DEFAULT).`when`(player).playbackParameters
            doReturn(TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT).`when`(player).trackSelectionParameters
            doReturn(VideoSize.UNKNOWN).`when`(player).videoSize
            doReturn(Size.ZERO).`when`(player).surfaceSize
            doReturn(CueGroup.EMPTY_TIME_ZERO).`when`(player).currentCues
            doReturn(AudioAttributes.DEFAULT).`when`(player).audioAttributes
            doReturn(DeviceInfo.UNKNOWN).`when`(player).deviceInfo
            doReturn(false).`when`(player).isCommandAvailable(anyInt())
            doReturn(Player.STATE_READY).`when`(player).playbackState
            doReturn(30_000L).`when`(player).duration
            doAnswer { position }.`when`(player).currentPosition
            doAnswer { playing }.`when`(player).isPlaying
            doAnswer { playing }.`when`(player).playWhenReady
            doAnswer { playing = it.getArgument(0); null }.`when`(player).setPlayWhenReady(anyBoolean())
            doAnswer { listener = it.getArgument(0); null }.`when`(player).addListener(any(Player.Listener::class.java))
            doAnswer { listener?.onPlaybackStateChanged(Player.STATE_READY); null }.`when`(player).prepare()
            doAnswer { playing = false; null }.`when`(player).pause()
            doAnswer { playing = true; null }.`when`(player).play()
        }
    }
    private class Backend : PlaybackServices {
        var resolutionFailures = 0
        val started = mutableListOf<String>()
        val resolved = mutableListOf<String>()
        val sessions = mutableListOf<Session>()
        val decoderModes = mutableListOf<Int>()
        override suspend fun resolve(video: VideoItem): PlaybackInfo {
            resolved += video.id
            if (video.id == "first" && resolutionFailures > 0) { resolutionFailures--; throw IOException("Temporary resolution error") }
            return PlaybackInfo(video.streamUrl!!, false, video.title)
        }
        override suspend fun episodes(video: VideoItem) = listOf(video)
        override fun player(context: Context, video: VideoItem, maxHeight: Int, decoderMode: Int): Player {
            started += video.id
            decoderModes += decoderMode
            return Session().also { sessions += it }.player
        }
    }
}
