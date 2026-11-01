package org.scryertv.client.streaming

import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

/** Полный путь «добавить источник → полки → поиск → выбор ссылки → автопереход» против локального сервера. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SourceRepositoryTest {
    private val server = MockWebServer()
    private lateinit var store: StreamStore
    private val base get() = server.url("/addon").toString().trimEnd('/')

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("streaming", Context.MODE_PRIVATE).edit().clear().commit()
        store = StreamStore(app)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val body = when (path) {
                    "/addon/manifest.json" -> """{"id":"test","name":"Тестовое дополнение","types":["movie","series"],
                        "resources":["catalog","meta","stream"],
                        "catalogs":[{"type":"movie","id":"top","name":"Новинки","extra":[{"name":"search"}]},
                                    {"type":"series","id":"genre","name":"По жанру","extra":[{"name":"genre","isRequired":true}]}]}"""
                    "/addon/catalog/movie/top.json" -> """{"metas":[{"id":"tt1","type":"movie","name":"Фильм","releaseInfo":"2024"},
                                                                     {"id":"tt9","type":"series","name":"Сериал"}]}"""
                    "/addon/catalog/movie/top/search=%D0%B4%D1%8E%D0%BD%D0%B0.json" -> """{"metas":[{"id":"tt2","type":"movie","name":"Дюна"}]}"""
                    "/addon/meta/series/tt9.json" -> """{"meta":{"id":"tt9","type":"series","name":"Сериал","videos":[
                        {"id":"tt9:1:2","season":1,"episode":2},{"id":"tt9:1:1","season":1,"episode":1}]}}"""
                    "/addon/stream/movie/tt1.json" -> """{"streams":[
                        {"name":"1080p","title":"Озвучка А","url":"https://cdn.example/a.m3u8","behaviorHints":{"proxyHeaders":{"request":{"Referer":"https://site.example/"}}}},
                        {"name":"720p","title":"Озвучка Б","url":"https://cdn.example/b.mp4"},
                        {"name":"Торрент","infoHash":"abc"}]}"""
                    "/addon/stream/series/tt9%3A1%3A1.json" -> """{"streams":[{"name":"A","url":"https://cdn.example/s1e1-a.mp4","behaviorHints":{"bingeGroup":"voice-a"}},
                        {"name":"B","url":"https://cdn.example/s1e1-b.mp4","behaviorHints":{"bingeGroup":"voice-b"}}]}"""
                    "/addon/stream/series/tt9%3A1%3A2.json" -> """{"streams":[{"name":"A","url":"https://cdn.example/s1e2-a.mp4","behaviorHints":{"bingeGroup":"voice-a"}},
                        {"name":"B","url":"https://cdn.example/s1e2-b.mp4","behaviorHints":{"bingeGroup":"voice-b"}}]}"""
                    "/one.m3u" -> "#EXTM3U\n#EXTINF:-1 group-title=\"Кино\",Фильм (2024)\nhttps://cdn.example/one.mp4\n#EXTINF:-1 group-title=\"Кино\",Другое\nhttps://cdn.example/other.mp4\n"
                    "/two.m3u" -> "#EXTM3U\n#EXTINF:-1,фильм 2024\nhttps://mirror.example/film.mp4\n"
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(body)
            }
        }
        server.start()
    }

    @After fun teardown() { server.shutdown() }

    @Test fun addonSourceGivesShelvesSearchAndAChoiceOfLinks() = runBlocking {
        val source = SourceRepository.add(store, "$base/manifest.json")
        assertEquals("Тестовое дополнение", source.name)
        assertEquals(StreamSource.Kind.ADDON, source.kind)
        try { SourceRepository.add(store, "$base/manifest.json"); fail("дубликат") } catch (_: IOException) {}

        // Каталог с обязательным жанром на главный экран не попадает.
        val rows = SourceRepository.rows(store)
        assertEquals(listOf("Тестовое дополнение · Новинки"), rows.map { it.title })
        val (movie, show) = rows.single().items
        assertTrue(show.isShow)

        assertEquals(listOf("Дюна"), SourceRepository.search(store, "дюна").single().items.map { it.title })

        val options = SourceRepository.streams(store, movie)
        assertEquals(listOf("1080p · Озвучка А", "720p · Озвучка Б"), options.map { it.label })

        // Выбрали вторую ссылку — плеер получит именно её.
        val chosen = movie.copy(streamAddonBase = options[1].addonBase, streamHint = options[1].hint)
        assertEquals("https://cdn.example/b.mp4", StreamRepository.resolve(chosen).url)
        val first = movie.copy(streamAddonBase = options[0].addonBase, streamHint = options[0].hint)
        val info = StreamRepository.resolve(first)
        assertEquals("application/x-mpegURL", info.mimeType)
        assertEquals(mapOf("Referer" to "https://site.example/"), info.headers)

        store.saveSource(source.copy(enabled = false))
        assertTrue(SourceRepository.rows(store).isEmpty())
        assertTrue(SourceRepository.streams(store, movie).isEmpty())
    }

    @Test fun nextEpisodeKeepsTheChosenVoice() = runBlocking {
        SourceRepository.add(store, "$base/manifest.json")
        val show = SourceRepository.rows(store).single().items.first { it.isShow }
        val episodes = SourceRepository.episodes(show)
        assertEquals(listOf("tt9:1:1", "tt9:1:2"), episodes.map { it.videoId })

        val option = SourceRepository.streams(store, episodes[0]).first { it.hint == "voice-b" }
        val pinned = episodes[0].copy(streamAddonBase = option.addonBase, streamHint = option.hint)
        val queue = StreamRepository.episodes(pinned)
        assertEquals(pinned.id, queue[0].id)
        assertEquals("https://cdn.example/s1e2-b.mp4", StreamRepository.resolve(queue[1]).url)
    }

    @Test fun sameFilmInTwoPlaylistsOffersBothLinks() = runBlocking {
        val one = SourceRepository.add(store, server.url("/one.m3u").toString())
        SourceRepository.add(store, server.url("/two.m3u").toString())
        assertEquals(StreamSource.Kind.PLAYLIST, one.kind)
        val rows = SourceRepository.rows(store)
        assertEquals(listOf("one.m3u · Кино", "two.m3u"), rows.map { it.title })
        val film = rows.first().items.first()
        assertEquals(listOf("https://cdn.example/one.mp4", "https://mirror.example/film.mp4"),
            SourceRepository.streams(store, film).map { it.url })
        assertEquals(2, SourceRepository.search(store, "ФИЛЬМ").size)
    }

    @Test fun oldSavedPlaylistBecomesASource() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("streaming", Context.MODE_PRIVATE)
        val video = StreamRepository.direct("https://cdn.example/old.mp4", "Старое", "Группа")
        prefs.edit().putString("catalog_url", "https://example.org/old.m3u")
            .putString("catalog", org.json.JSONArray().put(video.toJson()).toString()).commit()
        val source = store.sources().single()
        assertEquals("https://example.org/old.m3u", source.url)
        assertEquals(listOf(video), store.videos(SourceRepository.itemsKey(source)))
        assertEquals(1, store.sources().size)
    }
}
