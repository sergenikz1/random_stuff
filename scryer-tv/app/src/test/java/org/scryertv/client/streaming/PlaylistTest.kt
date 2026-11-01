package org.scryertv.client.streaming

import org.junit.Assert.*
import org.junit.Test

class PlaylistTest {
    @Test fun preservesEpisodeOrderGroupsAndRelativeLinks() {
        val items = Playlist.parse("""
            #EXTM3U
            #EXTINF:-1 group-title="Шоу" tvg-logo="https://example.org/poster.jpg",Серия 1
            episode1.mp4
            #EXTINF:-1 group-title="Шоу",Серия 2
            https://example.org/episode2.m3u8
            #EXTINF:-1,Недопустимый адрес
            file:///etc/passwd
        """.trimIndent(), "https://example.org/season/list.m3u")
        assertEquals(2, items.size)
        assertEquals("https://example.org/season/episode1.mp4", items[0].streamUrl)
        assertEquals("Шоу", items[1].group)
        assertEquals("Серия 2", items[1].title)
        assertEquals(items[0], VideoItem.fromJson(items[0].toJson()))
    }

    @Test(expected = IllegalArgumentException::class) fun neverTreatsHlsSegmentsAsEpisodes() {
        Playlist.parse("#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\nsegment.ts", "https://example.org/stream.m3u8")
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsHtmlInsteadOfSilentlyPlayingIt() {
        Playlist.parse("<html>not a playlist</html>", "https://example.org/list.m3u")
    }
}
