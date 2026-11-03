package org.intergalactictv.client.streaming

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.CookieManager
import android.webkit.WebSettings
import androidx.fragment.app.FragmentActivity
import okhttp3.HttpUrl.Companion.toHttpUrl
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
class WebPlayerActivityTest {
    private fun intent(video: VideoItem) = Intent(RuntimeEnvironment.getApplication(), WebPlayerActivity::class.java)
        .putExtra("video", video)

    @Test fun `website opens inside app with restricted browser settings and stops in background`() {
        val video = WebVideoSearch.page("https://example.org/movie", "Фильм")
        val controller = Robolectric.buildActivity(WebPlayerActivity::class.java, intent(video)).setup()
        try {
            val activity = controller.get()
            assertEquals(video.pageUrl, shadowOf(activity.browser).lastLoadedUrl)
            assertTrue(activity.browser.settings.javaScriptEnabled)
            assertFalse(activity.browser.settings.allowFileAccess)
            assertFalse(activity.browser.settings.allowContentAccess)
            assertFalse(activity.browser.settings.javaScriptCanOpenWindowsAutomatically)
            assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, activity.browser.settings.mixedContentMode)
            assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)))
            controller.pause()
            assertEquals("about:blank", shadowOf(activity.browser).lastLoadedUrl)
            controller.resume()
            assertEquals(video.pageUrl, shadowOf(activity.browser).lastLoadedUrl)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `web playback never routes into Rutube or source chooser`() {
        val video = WebVideoSearch.page("https://example.org/movie", "Фильм")
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        try {
            StreamActions.play(controller.get(), video)
            val started = shadowOf(controller.get()).nextStartedActivity
            assertEquals(WebPlayerActivity::class.java.name, started.component!!.className)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `playback cookies stay scoped to the destination`() {
        val manager = CookieManager.getInstance()
        manager.setCookie("https://video.example.org/", "session=one; Secure; Path=/")
        val jar = WebPlaybackCookies()
        assertEquals("one", jar.loadForRequest("https://video.example.org/master.m3u8".toHttpUrl()).single().value)
        assertTrue(jar.loadForRequest("https://different.example.org/segment.ts".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("http://video.example.org/master.m3u8".toHttpUrl()).isEmpty())
    }

    @Test fun `recreation after background unload restores the current page instead of blank`() {
        val video = WebVideoSearch.page("https://example.org/first", "Фильм")
        val controller = Robolectric.buildActivity(WebPlayerActivity::class.java, intent(video)).setup()
        val activity = controller.get()
        val second = "https://example.org/second"
        activity.browser.loadUrl(second)
        activity.browser.webViewClient.onPageFinished(activity.browser, second)
        val saved = Bundle()
        controller.pause().saveInstanceState(saved).stop().destroy()
        val restored = Robolectric.buildActivity(WebPlayerActivity::class.java, intent(video))
            .create(saved).start().resume().visible()
        try {
            assertEquals(second, shadowOf(restored.get().browser).lastLoadedUrl)
            val history = StreamStore(restored.get()).videos("history")
            assertEquals(WebVideoSearch.page(second, "").id, history.first().id)
        } finally { restored.pause().stop().destroy() }
    }
}
