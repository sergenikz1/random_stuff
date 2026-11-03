package org.intergalactictv.client.streaming

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** Ask WebView for cookies for each destination, including HLS segments/redirects.
 * Never copy a page's Cookie header into persistent history or another host's request. */
internal class WebPlaybackCookies : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val manager = CookieManager.getInstance()
        cookies.forEach { manager.setCookie(url.toString(), it.toString()) }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        CookieManager.getInstance().getCookie(url.toString()).orEmpty().split(';').mapNotNull {
            val name = it.substringBefore('=').trim()
            if ('=' !in it || name.isBlank()) return@mapNotNull null
            runCatching {
                Cookie.Builder().name(name).value(it.substringAfter('=').trim())
                    .hostOnlyDomain(url.host).path("/").apply { if (url.isHttps) secure() }.build()
            }.getOrNull()
        }
}
