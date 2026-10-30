package org.scryertv.client.streaming

import org.junit.Assert.*
import org.junit.Test

class EpisodeOrderTest {
    private fun video(title: String, author: Long = 7) = VideoItem(title, title, authorId = author)

    @Test fun parsesRussianAndCompactNumbering() {
        assertEquals(EpisodeOrder.Number("шоу", 2, 10), EpisodeOrder.parse("Шоу | 2 сезон | 10 серия"))
        assertEquals(EpisodeOrder.Number("шоу", 2, 10), EpisodeOrder.parse("Шоу S02E10 — Финал"))
        assertEquals(EpisodeOrder.Number("шоу", 1, 3), EpisodeOrder.parse("Шоу. Серия 3"))
        assertNull(EpisodeOrder.parse("Фильм 2024"))
        assertNull(EpisodeOrder.parse("1 серия: неизвестный сериал"))
    }

    @Test fun nextEpisodeUsesNumbersRatherThanSearchRanking() {
        val current = video("Шоу S01E02")
        val third = video("Шоу S01E03")
        val unrelated = video("Другое шоу S01E03")
        assertEquals(third, EpisodeOrder.next(current, listOf(video("Шоу S01E10"), unrelated, third)))
    }

    @Test fun doesNotMixUploadersOrJumpOverMissingEpisodes() {
        val current = video("Шоу S01E02")
        assertNull(EpisodeOrder.next(current, listOf(video("Шоу S01E03", 99))))
        assertNull(EpisodeOrder.next(current, listOf(video("Шоу S01E04"), video("Шоу S02E01"))))
        assertNull(EpisodeOrder.next(video("Шоу S01E02", 0), listOf(video("Шоу S01E03", 0))))
    }

    @Test fun crossesSeasonsAndStopsAtEnd() {
        val current = video("Шоу S01E10")
        val next = video("Шоу S02E01")
        assertEquals(next, EpisodeOrder.next(current, listOf(next, video("Шоу S02E02"))))
        assertNull(EpisodeOrder.next(next, listOf(current, next)))
        assertEquals(listOf(current), EpisodeOrder.ordered(current, listOf(current)))
    }
}
