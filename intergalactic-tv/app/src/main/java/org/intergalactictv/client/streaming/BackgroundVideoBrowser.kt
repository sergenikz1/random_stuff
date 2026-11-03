package org.intergalactictv.client.streaming

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.RenderProcessGoneDetail
import android.widget.FrameLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.coroutines.resume

/** Invisible, bounded browser session; it never replaces the app's native interface. */
internal class BackgroundVideoBrowser(
    private val activity: Activity,
    private val timeoutMs: Long = 14_000L,
    private val documentStartSupported: () -> Boolean = {
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    }
) {
    private val handler = Handler(Looper.getMainLooper())
    private var host: FrameLayout? = null
    private val views = mutableSetOf<WebView>()

    internal data class Discovery(val page: VideoItem, val media: List<BrowserMedia>)

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun scan(input: VideoItem): Discovery = withContext(Dispatchers.Main.immediate) {
        val script = activity.assets.open("background-video-probe.js").bufferedReader().use { it.readText() }
        val stage = host ?: FrameLayout(activity).apply {
            tag = "background-video-extractor"
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            isFocusable = false; isClickable = false
            (activity.findViewById<View>(android.R.id.content) as ViewGroup).addView(this, 0,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            host = this
        }
        var view: WebView? = null
        try {
            suspendCancellableCoroutine { continuation ->
                var page = input
                var done = false
                val candidates = linkedMapOf<String, BrowserMedia>()
                val protectedFrames = mutableSetOf<String>()
                val frames = linkedSetOf<String>()
                var navigatedFrame = false
                val started = SystemClock.uptimeMillis()
                val browser = object : WebView(activity) {
                    override fun onTouchEvent(event: MotionEvent): Boolean = false
                }
                view = browser; views.add(browser)
                val modern = documentStartSupported()
                browser.apply {
                    isFocusable = false; isFocusableInTouchMode = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    settings.apply {
                        javaScriptEnabled = true; domStorageEnabled = true
                        allowFileAccess = false; allowContentAccess = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        javaScriptCanOpenWindowsAutomatically = false; setSupportMultipleWindows(true)
                        // Document-start hooks mute every frame before enabling automatic playback.
                        mediaPlaybackRequiresUserGesture = !modern
                        userAgentString = WebVideoSearch.USER_AGENT
                        useWideViewPort = true; loadWithOverviewMode = true
                    }
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(browser, true)
                fun finish() {
                    if (done) return
                    done = true
                    if (continuation.isActive) continuation.resume(Discovery(page,
                        candidates.values.filter { it.referer !in protectedFrames }.toList()))
                }
                val deadline = Runnable { finish() }
                var settle: Runnable? = null
                fun candidate(media: BrowserMedia) {
                    if (done || !continuation.isActive || candidates.size >= 30 || WebVideoSearch.webUrl(media.url) == null ||
                        BrowserMediaProbe.isAdvertisement(media.url)) return
                    candidates[media.url]?.let { old ->
                        candidates[media.url] = media.copy(title = media.title.ifBlank { old.title },
                            poster = media.poster ?: old.poster, durationSec = media.durationSec.takeIf { it > 0 } ?: old.durationSec,
                            mime = media.mime?.takeIf { it.isNotBlank() } ?: old.mime)
                        return
                    }
                    candidates[media.url] = media
                    settle?.let(handler::removeCallbacks)
                    settle = Runnable { finish() }.also {
                        handler.postDelayed(it, maxOf(2200L, 3500L - (SystemClock.uptimeMillis() - started)))
                    }
                }
                fun message(raw: String, origin: String, main: Boolean) {
                    if (done || raw.length > 64 * 1024) return
                    runCatching {
                        val json = JSONObject(raw)
                        val source = json.optString("frame").toHttpUrlOrNull()
                        val trustedOrigin = origin.toHttpUrlOrNull()
                        val referer = source?.takeIf { trustedOrigin != null && it.scheme == trustedOrigin.scheme &&
                            it.host == trustedOrigin.host && it.port == trustedOrigin.port }?.toString() ?: origin
                        when (json.optString("kind")) {
                            "metadata" -> if (main) page = page.copy(
                                title = json.optString("title").take(240).ifBlank { page.title },
                                thumbnail = WebVideoSearch.webUrl(json.optString("poster")) ?: page.thumbnail)
                            "drm" -> protectedFrames.add(referer)
                            "frame" -> WebVideoSearch.webUrl(json.optString("url"))?.let { if (frames.size < 3) frames.add(it) }
                            "media" -> candidate(BrowserMedia(json.optString("url"), referer,
                                if (main) json.optString("title").take(240).ifBlank { page.title } else page.title,
                                WebVideoSearch.webUrl(json.optString("poster")) ?: page.thumbnail,
                                json.optDouble("duration").takeIf { it.isFinite() }?.toInt() ?: 0,
                                json.optString("mime").take(100)))
                        }
                    }
                }
                if (modern) {
                    WebViewCompat.addWebMessageListener(browser, "IntergalacticVideoProbe", setOf("*")) { _, data, source, main, _ ->
                        runCatching { message(data.data.orEmpty(), source.toString(), main) }
                    }
                    WebViewCompat.addDocumentStartJavaScript(browser, script, setOf("*"))
                }
                browser.webChromeClient = object : WebChromeClient() {
                    override fun onShowCustomView(view: View, callback: CustomViewCallback) { callback.onCustomViewHidden() }
                }
                browser.webViewClient = object : WebViewClient() {
                    @androidx.annotation.RequiresApi(26)
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        finish(); return true
                    }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) finish()
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        WebVideoSearch.webUrl(request.url.toString()) == null
                    @Suppress("DEPRECATION")
                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = WebVideoSearch.webUrl(url) == null
                    override fun onPageFinished(view: WebView, url: String) {
                        if (done || WebVideoSearch.webUrl(url) == null) return
                        if (!modern) {
                            // Older providers still expose DOM sources. No native JavaScript bridge is installed.
                            val wrapper = "(function(){var a=[];window.IntergalacticVideoProbe={postMessage:function(v){a.push(v)}};" +
                                script + ";return JSON.stringify(a)})()"
                            view.evaluateJavascript(wrapper) { result ->
                                runCatching {
                                    val array = org.json.JSONArray(JSONTokener(result).nextValue() as String)
                                    for (i in 0 until array.length()) message(array.getString(i), url, !navigatedFrame)
                                }
                                if (candidates.isEmpty() && !navigatedFrame && frames.isNotEmpty()) {
                                    navigatedFrame = true; view.loadUrl(frames.first())
                                }
                            }
                        }
                    }
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val url = request.url.toString()
                        if (request.method == "GET" && WebVideoSearch.isMediaUrl(url)) {
                            val referer = request.requestHeaders.entries.firstOrNull { it.key.equals("Referer", true) }?.value ?: input.pageUrl
                            handler.post { candidate(BrowserMedia(url, referer)) }
                        }
                        return null
                    }
                }
                stage.addView(browser, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                handler.postDelayed(deadline, timeoutMs)
                continuation.invokeOnCancellation { handler.post { done = true; if (browser in views) browser.stopLoading() } }
                browser.loadUrl(input.pageUrl)
            }
        } finally {
            view?.let(::dispose)
        }
    }

    fun close() {
        handler.removeCallbacksAndMessages(null)
        views.toList().forEach(::dispose)
        host?.let { (it.parent as? ViewGroup)?.removeView(it) }; host = null
    }

    private fun dispose(view: WebView) {
        if (!views.remove(view)) return
        view.stopLoading(); (view.parent as? ViewGroup)?.removeView(view); view.destroy()
    }
}
