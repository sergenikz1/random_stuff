package org.scryertv.client.streaming

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class StreamCookieJarTest {
    @Test fun keepsSourceSessionButNeverSendsCookiesToAnotherHost() {
        val jar = StreamCookieJar()
        val source = "https://rutube.ru/api/play/options/video/".toHttpUrl()
        jar.saveFromResponse(source, listOf(Cookie.parse(source, "session=test; Path=/; Secure")!!))
        assertEquals("test", jar.loadForRequest(source).single().value)
        assertTrue(jar.loadForRequest("https://example.org/stream.m3u8".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("http://rutube.ru/api/".toHttpUrl()).isEmpty())
    }
    @Test fun expiredCookiesAreRemovedAndReplacementsTakeEffect() {
        val jar = StreamCookieJar()
        val source = "https://rutube.ru/".toHttpUrl()
        jar.saveFromResponse(source, listOf(Cookie.parse(source, "session=old; Path=/")!!))
        jar.saveFromResponse(source, listOf(Cookie.parse(source, "session=new; Path=/")!!))
        assertEquals("new", jar.loadForRequest(source).single().value)
        jar.saveFromResponse(source, listOf(Cookie.parse(source, "session=gone; Path=/; Max-Age=0")!!))
        assertTrue(jar.loadForRequest(source).isEmpty())
    }
}
