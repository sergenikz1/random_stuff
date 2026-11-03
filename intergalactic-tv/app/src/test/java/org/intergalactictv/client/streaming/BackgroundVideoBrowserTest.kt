package org.intergalactictv.client.streaming

import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class BackgroundVideoBrowserTest {
    @Test fun `background browser is invisible and releases its view when search is cancelled`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = controller.get()
        val browser = BackgroundVideoBrowser(activity, documentStartSupported = { false })
        val job = CoroutineScope(Dispatchers.Main.immediate).launch { browser.scan(WebVideoSearch.page("https://example.org/movie", "Фильм")) }
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val host = root.findViewWithTag<ViewGroup>("background-video-extractor")
        assertNotNull(host)
        assertEquals(0f, host.alpha, 0f)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, host.importantForAccessibility)
        val web = host.getChildAt(0) as WebView
        assertFalse(web.isFocusable)
        assertFalse(web.settings.allowFileAccess)
        assertFalse(web.settings.allowContentAccess)
        job.cancel(); shadowOf(Looper.getMainLooper()).idle(); browser.close()
        assertEquals(0, host.childCount)
        assertNull(root.findViewWithTag<View>("background-video-extractor"))
        controller.pause().stop().destroy()
    }

    @Test fun `media requests inside iframe keep referer while page URL stays out of media list`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = controller.get()
        val browser = BackgroundVideoBrowser(activity, documentStartSupported = { false })
        var result: BackgroundVideoBrowser.Discovery? = null
        val job = CoroutineScope(Dispatchers.Main.immediate).launch {
            result = browser.scan(WebVideoSearch.page("https://example.org/movie", "Фильм"))
        }
        shadowOf(Looper.getMainLooper()).idle()
        val host = activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<ViewGroup>("background-video-extractor")
        val web = host.getChildAt(0) as WebView
        val request = mock(WebResourceRequest::class.java)
        `when`(request.url).thenReturn(Uri.parse("https://cdn.example.org/movie/master.m3u8"))
        `when`(request.method).thenReturn("GET")
        `when`(request.requestHeaders).thenReturn(mapOf("Referer" to "https://player.example.org/embed/123"))
        web.webViewClient.shouldInterceptRequest(web, request)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertTrue(job.isCompleted)
        assertEquals("https://player.example.org/embed/123", result!!.media.single().referer)
        assertEquals("https://cdn.example.org/movie/master.m3u8", result!!.media.single().url)
        assertEquals(0, host.childCount)
        browser.close(); controller.pause().stop().destroy()
    }
}
