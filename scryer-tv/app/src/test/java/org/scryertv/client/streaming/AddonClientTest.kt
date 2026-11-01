package org.scryertv.client.streaming

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AddonClientTest {
    private val base = "https://addon.example.org/abc"

    private val manifest = AddonClient.parseManifest(JSONObject("""
        {"id":"org.example","name":"Пример","types":["movie","series"],"idPrefixes":["tt"],
         "resources":["catalog",{"name":"meta","types":["series"]},{"name":"stream","types":["movie","series"],"idPrefixes":["tt","kp"]}],
         "catalogs":[
           {"type":"movie","id":"top","name":"Популярное"},
           {"type":"series","id":"search","name":"Поиск","extra":[{"name":"search","isRequired":true}]},
           {"type":"movie","id":"old","extraSupported":["search"]}
         ]}
    """))

    @Test fun acceptsManifestLinksAndStremioScheme() {
        assertEquals(base, AddonClient.baseUrl("$base/manifest.json"))
        assertEquals(base, AddonClient.baseUrl("stremio://addon.example.org/abc/manifest.json"))
        assertNull(AddonClient.baseUrl("https://example.org/list.m3u"))
        assertNull(AddonClient.baseUrl("file:///manifest.json"))
    }

    @Test fun readsCatalogsAndResourceFilters() {
        assertEquals("Пример", manifest.name)
        assertEquals(listOf("Популярное", "Поиск", "old"), manifest.catalogs.map { it.name })
        assertFalse(manifest.catalogs[0].needsExtra)
        assertTrue(manifest.catalogs[1].searchable && manifest.catalogs[1].needsExtra)
        assertTrue(manifest.catalogs[2].searchable)
        assertTrue(manifest.supports("stream", "movie", "kp123"))
        assertFalse(manifest.supports("stream", "tv", "tt1"))
        assertFalse(manifest.supports("meta", "movie", "tt1"))
        assertTrue(manifest.supports("meta", "series", "tt1"))
        assertFalse(manifest.supports("subtitles", "movie", "tt1"))
    }

    @Test fun encodesPathsLikeJavaScript() {
        assertEquals("$base/catalog/series/search/search=%D0%B4%D0%BE%D0%BA%D1%82%D0%BE%D1%80%20%D0%BA%D1%82%D0%BE.json",
            AddonClient.catalogUrl(base, "series", "search", mapOf("search" to "доктор кто")))
        assertEquals("$base/stream/series/tt0944947%3A1%3A2.json", AddonClient.streamUrl(base, "series", "tt0944947:1:2"))
    }

    @Test fun episodesComeInSeasonOrderWithSpecialsLast() {
        val show = AddonClient.parseMetas(JSONObject("""{"metas":[{"id":"tt1","type":"series","name":"Шоу","poster":"https://p/1.jpg"}]}"""), base, "Пример").single()
        assertTrue(show.isShow)
        val episodes = AddonClient.parseEpisodes(JSONObject("""
            {"meta":{"id":"tt1","type":"series","name":"Шоу","videos":[
              {"id":"tt1:2:1","season":2,"episode":1,"name":"Начало"},
              {"id":"tt1:0:1","season":0,"episode":1,"name":"Спецвыпуск"},
              {"id":"tt1:1:2","season":1,"episode":2},
              {"id":"tt1:1:1","season":1,"episode":1}]}}
        """), show)
        assertEquals(listOf("tt1:1:1", "tt1:1:2", "tt1:2:1", "tt1:0:1"), episodes.map { it.videoId })
        assertEquals("Шоу · S02E01 · Начало", episodes[2].title)
        assertTrue(episodes.all { it.isAddon && !it.isShow && !it.isRutube })
    }

    @Test fun keepsOnlyPlayableHttpLinksWithTheirHeaders() {
        val options = AddonClient.parseStreams(JSONObject("""
            {"streams":[
              {"name":"1080p","title":"Озвучка А\nWEB-DL","url":"https://cdn.example/a.m3u8",
               "behaviorHints":{"bingeGroup":"voice-a-1080","proxyHeaders":{"request":{"Referer":"https://site.example/"}}}},
              {"name":"Torrent","infoHash":"abcdef"},
              {"name":"YT","ytId":"xyz"},
              {"name":"Внешний","externalUrl":"https://site.example/watch"},
              {"name":"ftp","url":"ftp://example/a.mp4"},
              {"name":"720p","url":"https://cdn.example/b.mp4"}]}
        """), base, "Пример")
        assertEquals(2, options.size)
        assertEquals("1080p · Озвучка А · WEB-DL", options[0].label)
        assertEquals(mapOf("Referer" to "https://site.example/"), options[0].headers)
        assertEquals("voice-a-1080", options[0].hint)
        assertEquals("720p", options[1].hint)
    }

    @Test fun autoNextKeepsTheChosenVoiceAndAddon() {
        val a = StreamOption("A", "https://a", "1080p · Озвучка А", "https://a/1", emptyMap(), "voice-a")
        val b = StreamOption("A", "https://a", "720p · Озвучка Б", "https://a/2", emptyMap(), null)
        val other = StreamOption("B", "https://b", "720p · Озвучка Б", "https://b/1", emptyMap(), null)
        assertEquals(a, AddonClient.pick(listOf(b, a, other), "https://a", "voice-a"))
        assertEquals(b, AddonClient.pick(listOf(a, b, other), "https://a", "720p · Озвучка Б"))
        assertEquals(other, AddonClient.pick(listOf(other), "https://a", "voice-a"))
        assertNull(AddonClient.pick(emptyList(), null, null))
    }

    @Test fun newFieldsSurviveHistoryStorage() {
        val item = VideoItem("addon:1:series:tt1:1:2", "Шоу · S01E02", addonBase = base, contentType = "series",
            metaId = "tt1", videoId = "tt1:1:2", streamAddonBase = base, streamHint = "voice-a",
            requestHeaders = mapOf("Referer" to "https://site.example/"), season = 1, episode = 2)
        assertEquals(item, VideoItem.fromJson(item.toJson()))
        val legacy = VideoItem.fromJson(JSONObject("""{"id":"abc","title":"Rutube"}"""))
        assertTrue(legacy.isRutube)
    }

    @Test fun titleMatchingIgnoresCasePunctuationAndYo() {
        assertEquals(SourceRepository.normalize("Ёлки 2: (2011)"), SourceRepository.normalize("елки 2 2011"))
    }
}
