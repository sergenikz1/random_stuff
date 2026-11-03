package org.intergalactictv.client.streaming

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONTokener

/** Web content has no JavaScript bridge, file access, external intents or popup windows. */
class WebPlayerActivity : FragmentActivity() {
    internal lateinit var browser: WebView
        private set
    private lateinit var frame: FrameLayout
    private lateinit var status: TextView
    private lateinit var cursor: TextView
    private lateinit var nativeButton: Button
    private lateinit var video: VideoItem
    private var fullscreen: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var resumeUrl: String? = null
    private var pointer = false
    private var x = 0f
    private var y = 0f
    @Volatile private var pageGeneration = 0
    private val media = linkedSetOf<String>()
    private val handler = Handler(Looper.getMainLooper())
    private val scan = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                collectMedia()
                handler.postDelayed(this, 2000)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val selected = (savedInstanceState?.getSerializable("web_video") ?: intent.getSerializableExtra("video")) as? VideoItem
        if (selected?.webPageUrl?.let(WebVideoSearch::webUrl) == null) { finish(); return }
        video = selected
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 16f
            setPadding(dp(16), dp(6), dp(16), dp(6)); text = video.title
        }
        root.addView(status)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label; textSize = 16f; isFocusable = true; minHeight = dp(52)
            setOnClickListener { action() }; bar.addView(this)
        }
        button("Назад") { onBackPressed() }
        button("−10 с") { transport("seek", -10) }
        button("▶ / Ⅱ") { transport("toggle") }
        button("+10 с") { transport("seek", 10) }
        button("Видео крупно") { transport("expand") }
        button("К сайту") { transport("restore"); browser.requestFocus() }
        nativeButton = button("В ТВ-плеере (0)") { chooseMedia() }
        button("Курсор") {
            pointer = !pointer
            cursor.visibility = if (pointer) View.VISIBLE else View.GONE
            x = frame.width / 2f; y = frame.height / 2f; moveCursor()
            browser.requestFocus()
            if (pointer) notice("Стрелки — курсор, OK — нажать, Menu — кнопки")
        }
        button("Обновить") { browser.reload() }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; addView(bar)
        }, LinearLayout.LayoutParams(-1, -2))
        frame = FrameLayout(this)
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        browser = WebView(this).apply {
            isFocusable = true; isFocusableInTouchMode = true
            setBackgroundColor(Color.BLACK)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(true)
                builtInZoomControls = true; displayZoomControls = false
                useWideViewPort = true; loadWithOverviewMode = true
                userAgentString = WebVideoSearch.USER_AGENT
            }
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false)
        frame.addView(browser, FrameLayout.LayoutParams(-1, -1))
        cursor = TextView(this).apply {
            text = "⊕"; textSize = 30f; setTextColor(Color.CYAN)
            visibility = View.GONE; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        frame.addView(cursor, FrameLayout.LayoutParams(dp(40), dp(40)))
        setContentView(root)
        browser.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, progress: Int) {
                if (progress < 100) status.text = "Загрузка $progress% · ${view.url.orEmpty().toHttpUrlOrNull()?.host.orEmpty()}"
            }
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fullscreen != null) { callback.onCustomViewHidden(); return }
                fullscreen = view; fullscreenCallback = callback
                frame.addView(view, FrameLayout.LayoutParams(-1, -1))
                browser.visibility = View.GONE
                view.requestFocus()
            }
            override fun onHideCustomView() { hideFullscreen() }
        }
        browser.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                blockNavigation(request.url.toString())

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = blockNavigation(url)

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                pageGeneration++; media.clear(); nativeButton.text = "В ТВ-плеере (0)"
                if (url == video.webPageUrl) video.streamUrl?.let(::addMedia)
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (WebVideoSearch.webUrl(url) == null) return
                status.text = "${url.toHttpUrlOrNull()?.host.orEmpty()} · Menu — кнопки · OK на странице — выбор"
                if (WebVideoSearch.webUrl(url) != null) {
                    val title = view.title?.takeIf { it.isNotBlank() } ?: video.title
                    video = if (url == video.webPageUrl) video.copy(title = title) else WebVideoSearch.page(url, title)
                    StreamStore(this@WebPlayerActivity).remember(video.copy(streamUrl = null, requestHeaders = emptyMap()))
                }
                collectMedia()
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) status.text = "Страница недоступна · попробуйте другой сайт или «Обновить»"
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (request.method == "GET" && WebVideoSearch.isMediaUrl(url)) {
                    val generation = pageGeneration
                    handler.post { if (generation == pageGeneration) addMedia(url) }
                }
                return null
            }
        }
        val restored = savedInstanceState?.let { browser.restoreState(it) }
        val restoredUrl = savedInstanceState?.getString("resume_url")?.let(WebVideoSearch::webUrl)
        if (restoredUrl != null) browser.loadUrl(restoredUrl)
        else if (restored == null) browser.loadUrl(video.pageUrl)
        // Static discoveries are hints; dynamic requests will refresh expiring URLs.
        video.streamUrl?.let(::addMedia)
        browser.requestFocus()
    }

    private fun blockNavigation(url: String): Boolean {
        if (WebVideoSearch.webUrl(url) != null) return false
        notice("Эту ссылку нельзя открыть в ТВ-браузере")
        return true
    }

    private fun collectMedia() {
        val generation = pageGeneration
        browser.evaluateJavascript("""(function(){var a=[];function scan(w){try{w.document.querySelectorAll('video,video source').forEach(function(v){var s=v.currentSrc||v.src;if(s)a.push(s)});for(var i=0;i<w.frames.length;i++)scan(w.frames[i])}catch(e){}}scan(window);return JSON.stringify(a)})()""") { result ->
            if (generation != pageGeneration) return@evaluateJavascript
            runCatching {
                val values = JSONArray(JSONTokener(result).nextValue() as String)
                for (i in 0 until values.length()) addMedia(values.getString(i))
            }
        }
    }

    private fun addMedia(url: String) {
        if (WebVideoSearch.webUrl(url) == null || media.size >= 30) return
        media.add(url); nativeButton.text = "В ТВ-плеере (${media.size})"
    }

    private fun chooseMedia() {
        if (media.isEmpty()) {
            notice("Запустите видео на сайте. Если прямой поток не появится, смотрите через браузер.")
            return
        }
        val urls = media.toList()
        AlertDialog.Builder(this).setTitle("Найденные потоки · выберите видео")
            .setItems(urls.mapIndexed { i, url ->
                val parsed = url.toHttpUrlOrNull()!!
                "${i + 1}. ${parsed.host} · ${parsed.pathSegments.last().take(70)}"
            }.toTypedArray()) { _, index ->
                transport("pause")
                StreamStore(this).putVideos("queue", emptyList())
                startActivity(Intent(this, StreamPlayerActivity::class.java).putExtra("video", video.copy(
                    streamUrl = urls[index],
                    requestHeaders = mapOf("User-Agent" to WebVideoSearch.USER_AGENT, "Referer" to (browser.url ?: video.pageUrl))
                )))
            }.setNegativeButton("Отмена", null).show()
    }

    /** Cross-origin players keep their own controls; no attempt to break origin/DRM rules. */
    private fun transport(action: String, seconds: Int = 0) {
        val code = assets.open("web-player.js").bufferedReader().use { it.readText() }
        browser.evaluateJavascript("($code)('$action',$seconds)") { result ->
            if (result != "true" && action != "pause" && action != "restore") {
                notice("Управление доступно в плеере сайта. Используйте «Курсор» и OK.")
            }
        }
    }

    private fun hideFullscreen() {
        fullscreen?.let { frame.removeView(it) }; fullscreen = null
        fullscreenCallback?.onCustomViewHidden(); fullscreenCallback = null
        browser.visibility = View.VISIBLE
    }

    private fun moveCursor() { cursor.translationX = x - dp(20); cursor.translationY = y - dp(20) }

    // Public Android callback, also used by StreamPlayerActivity; AndroidX marks its helper restricted.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::browser.isInitialized) return super.dispatchKeyEvent(event)
        val mediaAction = when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "toggle"
            KeyEvent.KEYCODE_MEDIA_PLAY -> "play"
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> "pause"
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "forward"
            KeyEvent.KEYCODE_MEDIA_REWIND -> "back"
            else -> null
        }
        if (mediaAction != null) {
            if (event.action == KeyEvent.ACTION_DOWN) when (mediaAction) {
                "forward" -> transport("seek", 10)
                "back" -> transport("seek", -10)
                else -> transport(mediaAction)
            }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN) nativeButton.requestFocus()
            return true
        }
        if (pointer && browser.hasFocus() && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> x -= dp(18)
                KeyEvent.KEYCODE_DPAD_RIGHT -> x += dp(18)
                KeyEvent.KEYCODE_DPAD_UP -> y -= dp(18)
                KeyEvent.KEYCODE_DPAD_DOWN -> y += dp(18)
                else -> {
                    val now = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val touch = MotionEvent.obtain(now, now, action, x, y, 0)
                        browser.dispatchTouchEvent(touch); touch.recycle()
                    }
                }
            }
            x = x.coerceIn(0f, (frame.width - 1).coerceAtLeast(0).toFloat())
            y = y.coerceIn(0f, (frame.height - 1).coerceAtLeast(0).toFloat())
            moveCursor(); return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Compatibility with older TV devices")
    override fun onBackPressed() {
        when {
            fullscreen != null -> hideFullscreen()
            browser.canGoBack() -> browser.goBack()
            else -> super.onBackPressed()
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        if (::browser.isInitialized) {
            browser.saveState(outState)
            outState.putSerializable("web_video", video)
            outState.putString("resume_url", resumeUrl ?: browser.url?.let(WebVideoSearch::webUrl) ?: video.pageUrl)
        }
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume()
        if (::browser.isInitialized) {
            browser.onResume()
            resumeUrl?.let { browser.loadUrl(it) }; resumeUrl = null
            handler.post(scan)
        }
    }
    override fun onPause() {
        if (::browser.isInitialized) {
            // Unload also stops cross-origin iframe audio, which JS and onPause cannot reliably pause.
            resumeUrl = browser.url?.let(WebVideoSearch::webUrl) ?: video.webPageUrl
            hideFullscreen(); browser.loadUrl("about:blank"); browser.onPause()
        }
        handler.removeCallbacks(scan)
        super.onPause()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::browser.isInitialized) {
            hideFullscreen(); browser.stopLoading()
            (browser.parent as? ViewGroup)?.removeView(browser); browser.destroy()
        }
        super.onDestroy()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun notice(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
