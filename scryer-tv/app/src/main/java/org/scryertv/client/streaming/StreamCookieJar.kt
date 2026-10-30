package org.scryertv.client.streaming

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** Keep source/CDN session cookies in memory and send them only to matching hosts/paths. */
internal class StreamCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        this.cookies.removeAll { old -> old.expiresAt <= System.currentTimeMillis() || cookies.any { it.name == old.name && it.domain == old.domain && it.path == old.path } }
        this.cookies += cookies.filter { it.expiresAt > System.currentTimeMillis() }
        while (this.cookies.size > 256) this.cookies.removeAt(0)
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return cookies.filter { it.matches(url) }
    }
}
