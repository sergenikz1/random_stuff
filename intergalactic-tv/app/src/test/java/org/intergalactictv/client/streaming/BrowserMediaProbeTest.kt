package org.intergalactictv.client.streaming

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class BrowserMediaProbeTest {
    @Test fun `HTML pages disguised as video never become cards`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setBody("<!doctype html><h1>Войдите</h1>"))
        server.start()
        try {
            val site = WebVideoSearch.page(server.url("/movie").toString(), "Фильм")
            val result = BrowserMediaProbe(OkHttpClient()).verify(BrowserMedia(server.url("/fake.mp4").toString(), site.pageUrl), site)
            assertNull(result)
        } finally { server.shutdown() }
    }

    @Test fun `extensionless HLS is verified with frame referer and stored with explicit MIME`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream")
            .setBody("#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\nsegment.ts\n#EXT-X-ENDLIST"))
        server.start()
        try {
            val site = WebVideoSearch.page("https://movie.example.org/film", "Фильм")
            val stream = server.url("/get?id=123").toString()
            val result = BrowserMediaProbe(OkHttpClient()).verify(BrowserMedia(stream,
                "https://player.example.org/embed/123", poster = "https://movie.example.org/poster.jpg"), site)!!
            assertEquals(stream, result.streamUrl)
            assertEquals("application/x-mpegURL", result.streamMimeType)
            assertEquals(site.pageUrl, result.webPageUrl)
            assertEquals("https://player.example.org/embed/123", server.takeRequest().getHeader("Referer"))
            assertFalse(result.requestHeaders.containsKey("Cookie"))
            assertEquals(result, VideoItem.fromJson(result.toJson()))
            assertEquals("application/x-mpegURL", StreamRepository.resolve(result).mimeType)
        } finally { server.shutdown() }
    }

    @Test fun `protected DASH, HTML embeds and HLS segments are rejected`() {
        fun identify(type: String, text: String) = BrowserMediaProbe.identify(type, text.toByteArray())
        assertNull(identify("text/html", "<html><iframe src='/player'></iframe></html>"))
        assertNull(identify("application/dash+xml", "<MPD><Period><ContentProtection schemeIdUri='urn:uuid:example'/></Period></MPD>"))
        assertNull(identify("video/mp2t", "segment bytes"))
        assertNull(identify("application/vnd.apple.mpegurl", "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES\n#EXTINF:5,\nv.ts"))
        assertEquals("application/dash+xml", identify("application/xml", "<MPD><Period/></MPD>"))
        assertEquals("video/mp4", BrowserMediaProbe.identify("application/octet-stream", byteArrayOf(0,0,0,24)+"ftypisom".toByteArray()))
    }

    @Test fun `expired URLs fail validation and known advertising URLs are ignored`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(403))
        server.start()
        try {
            val page = WebVideoSearch.page("https://example.org/movie", "Фильм")
            assertNull(BrowserMediaProbe(OkHttpClient()).verify(BrowserMedia(server.url("/expired.m3u8").toString(), page.pageUrl), page))
            assertTrue(BrowserMediaProbe.isAdvertisement("https://ads.example.org/preroll.mp4"))
        } finally { server.shutdown() }
    }
}
