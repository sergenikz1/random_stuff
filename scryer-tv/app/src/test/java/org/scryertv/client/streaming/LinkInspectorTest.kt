package org.scryertv.client.streaming

import org.junit.Assert.*
import org.junit.Test

class LinkInspectorTest {
    private val page = "https://site.example/films/123/"

    @Test fun findsOpenVideoTagsMetaAndLinksInScripts() {
        val html = """
            <html><head>
              <meta property="og:title" content="Фильм &amp; сериал">
              <meta content="https://cdn.example/og.mp4" property="og:video">
              <meta property="og:video:url" content="https://player.example/embed/1">
            </head><body>
              <video src="/media/main.webm" controls></video>
              <video><source src='hls/master.m3u8' type="application/x-mpegURL"></video>
              <script>var cfg = {"file":"https:\/\/cdn.example\/stream\/index.m3u8?token=abc","poster":"/p.jpg"};</script>
              <a href="https://cdn.example/og.mp4">дубль</a>
              <video src="blob:https://site.example/xyz"></video>
            </body></html>
        """.trimIndent()
        assertEquals("Фильм & сериал", LinkInspector.pageTitle(html))
        val videos = LinkInspector.extractVideos(html, page, "Фильм")
        assertEquals(listOf(
            "https://cdn.example/og.mp4",
            "https://site.example/media/main.webm",
            "https://site.example/films/123/hls/master.m3u8",
            "https://cdn.example/stream/index.m3u8?token=abc"
        ), videos.map { it.streamUrl })
        assertEquals(LinkInspector.MIME_HLS, videos[2].streamMime)
        assertNull(videos[0].streamMime)
        assertTrue(videos.all { it.requestHeaders["Referer"] == page })
        assertTrue(videos[3].title.startsWith("Фильм · 4"))
    }

    @Test fun skipsPlayerPagesAndPagesWithoutVideo() {
        val embedOnly = """<meta property="og:video" content="https://player.example/embed/1">
            <meta property="og:video:type" content="text/html"><iframe src="https://player.example/embed/1"></iframe>"""
        assertTrue(LinkInspector.extractVideos(embedOnly, page, "Страница").isEmpty())
        assertTrue(LinkInspector.extractVideos("<html><p>Просто текст про mp4</p></html>", page, "Страница").isEmpty())
    }

    @Test fun ogVideoWithoutExtensionIsAcceptedWhenTypeSaysHls() {
        val html = """<meta property="og:video" content="https://cdn.example/live"><meta property="og:video:type" content="application/vnd.apple.mpegurl">"""
        val video = LinkInspector.extractVideos(html, page, "Эфир").single()
        assertEquals("Эфир", video.title)
        assertEquals(LinkInspector.MIME_HLS, video.streamMime)
    }

    @Test fun mimeSurvivesHistoryStorage() {
        val video = StreamRepository.direct("https://cdn.example/live", "Эфир").copy(streamMime = LinkInspector.MIME_HLS)
        assertEquals(video, VideoItem.fromJson(video.toJson()))
    }
}
