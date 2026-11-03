package org.intergalactictv.client.streaming

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import okio.ByteString.Companion.encodeUtf8

class WebVideoSearchTest {
    @Test fun `search adds hidden suffix and inspects only first six links in original order`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.requestUrl!!.encodedPath == "/search") {
                        assertEquals("Солярис смотреть онлайн", request.requestUrl!!.queryParameter("q"))
                        val links = (1..9).joinToString("") {
                            "<a class='result__a' href='${server.url("/film/$it")}'>Фильм $it</a>"
                        }
                        return MockResponse().setBody(links)
                    }
                    if (request.path == "/film/3") return MockResponse().setResponseCode(403)
                    return MockResponse().setBody("<title>${request.path}</title><video src='/content.mp4'></video>")
                }
            }
            val result = WebVideoSearch(OkHttpClient(), server.url("/search").toString(), null).search("Солярис")
            assertEquals(6, result.inspected)
            assertEquals(1, result.unavailable)
            assertEquals((1..6).map { server.url("/film/$it").toString() }, result.items.map { it.pageUrl })
            assertEquals(5, result.items.count { it.streamUrl != null })
            assertTrue(result.items[2].isWeb)
            assertFalse(result.items[2].isRutube)
            assertEquals(7, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun `parses wrapped search URLs and discards unsupported links and duplicates`() {
        val html = """
            <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Fmovie">Фильм</a>
            <a class="result__a" href="https://example.org/movie">Дубликат</a>
            <a class="result__a" href="javascript:alert(1)">Нельзя</a>
            <a class="result__a" href="file:///tmp/movie">Нельзя</a>
            <a class="result__a" href="https://user:secret@example.org/">Нельзя</a>
        """.trimIndent()
        val result = WebVideoSearch.parseResults(html, WebVideoSearch.SEARCH_URL)
        assertEquals(listOf("https://example.org/movie"), result.map { it.pageUrl })
    }

    @Test fun `extracts relative video and preserves source page and metadata`() {
        val result = WebVideoSearch.parsePage("""
            <meta property="og:title" content="Космос &amp; звёзды">
            <meta property="og:image" content="/poster.jpg">
            <video><source src="../video/master.m3u8?token=123"></video>
        """.trimIndent(), "https://example.org/films/movie", "Запрос")
        assertEquals("Космос & звёзды", result.title)
        assertEquals("https://example.org/video/master.m3u8?token=123", result.streamUrl)
        assertEquals("https://example.org/poster.jpg", result.thumbnail)
        assertEquals("https://example.org/films/movie", result.pageUrl)
        assertEquals(result.pageUrl, result.requestHeaders["Referer"])
        assertTrue(result.isWeb)
        assertFalse(result.isRutube)
        assertEquals(result, VideoItem.fromJson(result.toJson()))
    }

    @Test fun `embed and blob pages stay browser pages instead of fake media streams`() {
        val result = WebVideoSearch.parsePage("""
            <meta property="og:video" content="https://player.example.org/embed/123">
            <video src="blob:https://example.org/abcdef"></video>
            <iframe src="https://player.example.org/embed/123"></iframe>
            <script type="application/ld+json">{"@type":"VideoObject","embedUrl":"/embed/123"}</script>
        """, "https://example.org/movie", "Фильм")
        assertNull(result.streamUrl)
        assertNull(result.thumbnail)
        assertTrue(result.isWeb)
        assertFalse(result.isRutube)
    }

    @Test fun `recognizes JSON LD video content but not embed URL`() {
        val result = WebVideoSearch.parsePage("""
            <script type="application/ld+json">{"@graph":[{"@type":"VideoObject",
            "contentUrl":"/stream?id=123", "embedUrl":"https://player.example.org/embed/123"}]}</script>
        """, "https://example.org/movie", "Фильм")
        assertEquals("https://example.org/stream?id=123", result.streamUrl)
    }

    @Test fun `decodes Bing result redirects and skips advertisements outside organic results`() {
        val encoded = "https://example.org/movie".encodeUtf8().base64Url()
        val result = WebVideoSearch.parseResults("""
            <li class="b_ad"><h2><a href="https://ads.example.org">Реклама</a></h2></li>
            <li class="b_algo"><h2><a href="https://www.bing.com/ck/a?u=a1$encoded">Фильм</a></h2></li>
        """, "https://www.bing.com/search?q=test")
        assertEquals(listOf("https://example.org/movie"), result.map { it.pageUrl })
    }

    @Test fun `provider failure falls back without losing the original query suffix`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setBody("<a class='result__a' href='${server.url("/film")}'>Фильм</a>"))
            server.enqueue(MockResponse().setBody("<iframe src='https://player.example.org/embed'></iframe>"))
            val result = WebVideoSearch(OkHttpClient(), server.url("/first").toString(), server.url("/second").toString()).search("Солярис")
            assertEquals(1, result.items.size)
            assertEquals("Солярис смотреть онлайн", server.takeRequest().requestUrl!!.queryParameter("q"))
            assertEquals("Солярис смотреть онлайн", server.takeRequest().requestUrl!!.queryParameter("q"))
        } finally { server.shutdown() }
    }

    @Test fun `legacy history still identifies Rutube and direct streams`() {
        val old = VideoItem("rutube-id", "Видео")
        assertTrue(VideoItem.fromJson(old.toJson()).isRutube)
        assertFalse(old.copy(streamUrl = "https://example.org/v.mp4").isRutube)
    }
}
