package com.voicereader

import com.voicereader.text.SpeedWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedWordsTest {

    private fun highlighted(word: String) = word[SpeedWords.pivot(word)]

    @Test
    fun pivotMatchesTheVideo() {
        // Опорные буквы из образца: ли → и, в → в, Значит, → а, постарайся → т, справляются → а.
        assertEquals('и', highlighted("ли"))
        assertEquals('в', highlighted("в"))
        assertEquals('а', highlighted("Значит,"))
        assertEquals('т', highlighted("постарайся"))
        assertEquals('с', highlighted("быстрее"))
        assertEquals('п', highlighted("отпустить."))
        assertEquals('а', highlighted("справляются"))
        assertEquals('о', highlighted("Готов?"))
    }

    @Test
    fun pivotSkipsLeadingPunctuation() {
        assertEquals('и', highlighted("«Привет"))
        assertEquals('а', highlighted("— Так"))
        assertEquals(0, SpeedWords.pivot("…"))
    }

    @Test
    fun splitKeepsDashesAndQuotesWithWords() {
        assertEquals(listOf("— Значит,", "постарайся", "быстрее."), SpeedWords.split("— Значит, постарайся   быстрее."))
        assertEquals(listOf("Слова —", "это", "ритм…"), SpeedWords.split("Слова — это ритм…"))
        assertEquals(emptyList<String>(), SpeedWords.split("   "))
    }

    @Test
    fun punctuationAndLongWordsStayLonger() {
        val plain = SpeedWords.durationMs("слово", 300, endOfSentence = false)
        assertEquals(200L, plain)
        assertTrue(SpeedWords.durationMs("слово,", 300, false) > plain)
        assertTrue(SpeedWords.durationMs("слово.", 300, false) > SpeedWords.durationMs("слово,", 300, false))
        assertTrue(SpeedWords.durationMs("справляются", 300, false) > plain)
        assertEquals(SpeedWords.durationMs("слово.", 300, false), SpeedWords.durationMs("слово", 300, true))
    }

    @Test
    fun rampStartsAtHalfSpeedAndReachesTarget() {
        assertEquals(250, SpeedWords.rampedWpm(500, 0))
        assertTrue(SpeedWords.rampedWpm(500, 30) in 251..499)
        assertEquals(500, SpeedWords.rampedWpm(500, 60))
        assertEquals(100, SpeedWords.rampedWpm(150, 0))
    }
}
