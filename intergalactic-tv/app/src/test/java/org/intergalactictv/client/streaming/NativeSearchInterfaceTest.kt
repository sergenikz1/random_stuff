package org.intergalactictv.client.streaming

import android.Manifest
import android.content.Context
import android.content.DialogInterface
import android.os.Looper
import android.widget.TextView
import androidx.leanback.widget.ListRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import org.intergalactictv.client.R
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
import org.robolectric.shadows.ShadowAlertDialog
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class NativeSearchInterfaceTest {
    @After fun reset() {
        StreamSearchFragment.siteSearch = { NativeWebVideoSearch(it) }
        StreamSearchFragment.rutubeSearch = { query, page -> StreamRepository.search(query, page) }
    }

    @Test fun rutubeStaysAboveWebsiteResultsEvenWhenWebSearchFinishesFirst() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        app.getSharedPreferences("streaming", Context.MODE_PRIVATE).edit().clear().commit()
        val rutube = VideoItem("rutube-fixture", "Фильм на Rutube")
        val page = WebVideoSearch.page("https://example.org/movie", "Фильм")
        val extracted = page.copy(streamUrl = "https://cdn.example.org/movie.m3u8", streamMimeType = "application/x-mpegURL")
        val rutubeReady = CompletableDeferred<Unit>()
        StreamSearchFragment.rutubeSearch = { _, _ -> rutubeReady.await(); Page(listOf(rutube), false, 1) }
        StreamSearchFragment.siteSearch = { object : SiteVideoSearch {
            override suspend fun search(query: String, found: (VideoItem) -> Unit, progress: (Int, Int) -> Unit) {
                assertEquals("Игра престолов", query)
                found(page) // Pages without verified media must never appear as video cards.
                found(extracted); progress(6, 6)
            }
        } }
        val controller = Robolectric.buildActivity(StreamSearchActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            shadowOf(Looper.getMainLooper()).idle()
            val fragment = activity.supportFragmentManager.findFragmentById(R.id.fragment_container) as StreamSearchFragment
            fragment.onQueryTextSubmit("Игра престолов")
            shadowOf(Looper.getMainLooper()).idle()
            val rows = fragment.resultsAdapter
            assertEquals(2, rows.size())
            assertEquals(0, (rows[0] as ListRow).adapter.size())
            assertEquals(extracted, (rows[1] as ListRow).adapter[0])
            rutubeReady.complete(Unit)
            shadowOf(Looper.getMainLooper()).idle()
            val top = rows[0] as ListRow
            val bottom = rows[1] as ListRow
            assertTrue(top.headerItem.name.startsWith("Rutube"))
            assertTrue(bottom.headerItem.name.startsWith("Интернет"))
            assertEquals(1, top.adapter.size()); assertEquals(rutube, top.adapter[0])
            assertEquals(1, bottom.adapter.size()); assertEquals(extracted, bottom.adapter[0])
            assertFalse((rows[0] as ListRow).headerItem.name.contains("смотреть онлайн"))
            StreamActions.show(fragment, extracted)
            ShadowAlertDialog.getLatestAlertDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(StreamPlayerActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun changingQueryCancelsOldExtractionWithoutMixingResults() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        StreamSearchFragment.rutubeSearch = { _, _ -> Page(emptyList(), false, 1) }
        StreamSearchFragment.siteSearch = { object : SiteVideoSearch {
            override suspend fun search(query: String, found: (VideoItem) -> Unit, progress: (Int, Int) -> Unit) {
                delay(1000)
                found(WebVideoSearch.page("https://example.org/$query", query).copy(streamUrl = "https://cdn.example.org/$query.mp4"))
            }
        } }
        val controller = Robolectric.buildActivity(StreamSearchActivity::class.java).setup().visible()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val activity = controller.get()
            val fragment = activity.supportFragmentManager.findFragmentById(R.id.fragment_container) as StreamSearchFragment
            fragment.onQueryTextSubmit("old"); shadowOf(Looper.getMainLooper()).idle()
            fragment.onQueryTextSubmit("new"); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1200))
            assertEquals(0, (fragment.resultsAdapter[0] as ListRow).adapter.size())
            val cards = (fragment.resultsAdapter[1] as ListRow).adapter
            assertEquals(1, cards.size()); assertEquals("new", (cards[0] as VideoItem).title)
        } finally { controller.pause().stop().destroy() }
    }
}
