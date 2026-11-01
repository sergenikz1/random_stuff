package org.scryertv.client.streaming

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RutubePlaybackTest {
    @Test fun acceptsHlsAddressWithoutFilenameExtensionAndSuppliesMimeType() {
        val result = RutubeApi.parsePlayback(JSONObject("""{"video_balancer":{"m3u8":"https://media.example.org/play/episode?token=sample"}}"""))
        assertEquals("application/x-mpegURL", result.mimeType)
        assertEquals("https://media.example.org/play/episode?token=sample", result.url)
    }

    @Test fun normalizesProtocolRelativeContentUrls() {
        val result = RutubeApi.parsePlayback(JSONObject("""{"video_balancer":{"m3u8":"//media.example.org/episode.m3u8"}}"""))
        assertEquals("https://media.example.org/episode.m3u8", result.url)
    }
    @Test fun selectsContentInsteadOfAdvertising() {
        val response = JSONObject("""{
          "title":"Серия 1",
          "ad":{"url":"https://ads.example.org/ad.m3u8"},
          "video_balancer":{"m3u8":"https://media.example.org/episode.m3u8"}
        }""")
        val result = RutubeApi.parsePlayback(response)
        assertEquals("https://media.example.org/episode.m3u8", result.url)
        assertFalse(result.isLive)
    }

    @Test fun supportsNestedLiveUrls() {
        val result = RutubeApi.parsePlayback(JSONObject("""{"live_streams":{"hls":[{"url":"https://media.example.org/live.m3u8"}]}}"""))
        assertTrue(result.isLive)
    }

    @Test(expected = RutubeApi.ApiException::class) fun rejectsAdvertisingOnlyResponse() {
        RutubeApi.parsePlayback(JSONObject("""{"ad":{"url":"https://ads.example.org/ad.m3u8"}}"""))
    }
}
