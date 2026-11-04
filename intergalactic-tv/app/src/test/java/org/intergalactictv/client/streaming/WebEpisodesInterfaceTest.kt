package org.intergalactictv.client.streaming

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class WebEpisodesInterfaceTest {
    @After fun reset() {
        WebEpisodesActivity.search = { activity, show, cached, found, progress ->
            WebEpisodeSearch(activity).search(show, cached, found, progress)
        }
    }

    @Test fun discoveredEpisodesRemainSelectableWhileSearchRunsAndStopCancels() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("streaming", Context.MODE_PRIVATE).edit().clear().commit()
        val show = WebVideoSearch.page("https://example.org/show", "Сериал")
        val target = WebEpisodeTarget(show.pageUrl, "https://embed.example.org/player", 2, 3,
            "Сезон 2 · Серия 3", "2", "3")
        val episode = target.video(show)
        val gate = CompletableDeferred<Unit>()
        var cancelled = false
        WebEpisodesActivity.search = { _, _, _, found, progress ->
            found(episode); progress("Ищу ещё…")
            try { gate.await() } finally { cancelled = true }
        }
        val controller = Robolectric.buildActivity(WebEpisodesActivity::class.java,
            Intent(app, WebEpisodesActivity::class.java).putExtra("video", show)).setup().visible()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val activity = controller.get()
            val views = allViews(activity.findViewById(android.R.id.content))
            val spinner = views.filterIsInstance<ProgressBar>().single()
            val list = views.filterIsInstance<ListView>().single()
            val stop = views.filterIsInstance<Button>().single()
            assertEquals(View.VISIBLE, spinner.visibility)
            assertEquals(episode, list.adapter.getItem(0))
            list.performItemClick(null, 0, 0)
            val play = shadowOf(activity).nextStartedActivity
            assertEquals(StreamPlayerActivity::class.java.name, play.component!!.className)
            @Suppress("DEPRECATION")
            assertEquals(target, (play.getSerializableExtra("video") as VideoItem).webEpisodeTarget)
            assertEquals(target, StreamStore(activity).videos("web-episodes:${show.id}").single().webEpisodeTarget)
            stop.performClick(); shadowOf(Looper.getMainLooper()).idle()
            assertTrue(cancelled); assertEquals(View.GONE, spinner.visibility)
            assertEquals(1, list.adapter.count)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun episodeTargetRoundTripsAndRejectsForeignOrNonWebLinks() {
        val target = WebEpisodeTarget("https://example.org/show", "https://embed.example.org/player?token=old", 3, 4,
            "Сезон 3 · Серия 4", "season-three", "episode-four")
        val show = WebVideoSearch.page(target.pageUrl, "Сериал")
        assertEquals(target, VideoItem.fromJson(target.video(show).toJson()).webEpisodeTarget)
        assertNull(WebEpisodeTarget.fromJson(target.toJson().put("href", "https://other.org/episode")))
        assertNull(WebEpisodeTarget.fromJson(target.toJson().put("href", "javascript:alert(1)")))
        assertEquals(WebEpisodeTarget.frameKey(target.frameUrl), WebEpisodeTarget.frameKey("https://embed.example.org/player?token=new"))
    }

    private fun allViews(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { allViews(view.getChildAt(it)) } else emptyList()
}
