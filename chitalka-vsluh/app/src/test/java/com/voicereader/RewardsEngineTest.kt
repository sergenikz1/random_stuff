package com.voicereader

import com.voicereader.rewards.DailyCatalog
import com.voicereader.rewards.FocusCatalog
import com.voicereader.rewards.RanksCatalog
import com.voicereader.rewards.RewardEvent
import com.voicereader.rewards.RewardKind
import com.voicereader.rewards.RewardsEngine
import com.voicereader.rewards.RewardsState
import com.voicereader.rewards.Welcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RewardsEngineTest {

    private val day = LocalDate.of(2026, 10, 21)

    private fun read(state: RewardsState, seconds: Int, events: MutableList<RewardEvent> = ArrayList()): RewardsState {
        var s = state
        repeat(seconds) { s = RewardsEngine.tick(s, day, events) }
        return s
    }

    @Test
    fun focusMedalsFollowTiersOf5Ranks() {
        assertEquals(55, FocusCatalog.COUNT)
        assertEquals(-1, FocusCatalog.medalFor(59))
        assertEquals(0, FocusCatalog.medalFor(60))              // Дерево I — 1 минута
        assertEquals(4, FocusCatalog.medalFor(5 * 60))          // Дерево V — 5 минут
        assertEquals(5, FocusCatalog.medalFor(6 * 60))          // Камень I
        assertEquals(54, FocusCatalog.medalFor(180 * 60))       // Алмаз V — 3 часа
        assertEquals(0.5f, FocusCatalog.progressToNext(90), 0.001f)
    }

    @Test
    fun readingEmitsMedalAndCountsTheDay() {
        val events = ArrayList<RewardEvent>()
        val s = read(RewardsState(), 10 * 60, events)
        assertEquals(10 * 60, s.sessionSeconds)
        assertEquals(10 * 60, s.dayLog[day.toString()])
        assertTrue(events.any { it is RewardEvent.MedalUp && it.medal == 0 })
        assertEquals(1, s.growthDays)
        assertEquals(day.toString(), s.lastCountedDay)
        assertTrue(events.any { it is RewardEvent.DayCounted })
        assertEquals(RewardsEngine.MARATHON_POINTS.toLong(), s.points) // бонус за 10 минут
    }

    @Test
    fun pauseLongerThanAMinuteEndsTheStreakButShortOneKeepsIt() {
        var s = read(RewardsState(), 120).copy(pauseLeft = RewardsEngine.PAUSE_GRACE_SECONDS)
        repeat(59) { s = RewardsEngine.pauseTick(s) }
        assertEquals(120, s.sessionSeconds)
        assertEquals(1, s.pauseLeft)
        s = RewardsEngine.pauseTick(s)
        assertEquals(0, s.sessionSeconds)
        assertNull(s.pauseLeft)
        assertEquals(120, s.bestSessionSeconds)
    }

    @Test
    fun recordIsCelebratedOncePerStreak() {
        val events = ArrayList<RewardEvent>()
        val s = read(RewardsState(bestSessionSeconds = 6 * 60), 8 * 60, events)
        assertEquals(1, events.count { it is RewardEvent.Record })
        assertEquals(8 * 60, s.bestSessionSeconds)
    }

    @Test
    fun sentencePointsGrowWithLongStreaksAndRanksUnlockRewards() {
        val events = ArrayList<RewardEvent>()
        var s = RewardsEngine.sentenceRead(RewardsState(), events)
        assertEquals(1, s.points)
        s = RewardsEngine.sentenceRead(s.copy(sessionSeconds = 46 * 60), events)
        assertEquals(3, s.points)
        s = RewardsEngine.chapterFinished(s, events)
        assertEquals(53, s.points)
        assertEquals(1, s.rank)
        assertTrue(events.any { it is RewardEvent.RankUp && it.rank == 1 })
        assertTrue(s.unlocked(RanksCatalog.rewardFor(1)!!))
        assertFalse(s.unlocked(RanksCatalog.rewardFor(2)!!))
    }

    @Test
    fun wordsInSpeedReadingAreWorthAPointPer15() {
        var s = RewardsEngine.wordsRead(RewardsState(), 14, ArrayList())
        assertEquals(0, s.points)
        s = RewardsEngine.wordsRead(s, 1, ArrayList())
        assertEquals(1, s.points)
        assertEquals(0, s.wordsCarry)
    }

    @Test
    fun ranksCatalogHas30RanksAndAlternatingRewards() {
        assertEquals(30, RanksCatalog.COUNT)
        assertEquals(29, RanksCatalog.REWARDS.size)
        assertEquals((1..29).toList(), RanksCatalog.REWARDS.map { it.rank })
        assertEquals(RewardKind.entries.toSet(), RanksCatalog.REWARDS.map { it.kind }.toSet())
        // Подряд не идут две награды одного вида.
        RanksCatalog.REWARDS.zipWithNext().forEach { (a, b) -> assertTrue("${a.rank}", a.kind != b.kind) }
        assertEquals(29, RanksCatalog.rankFor(1_000_000))
    }

    @Test
    fun oneMissedDayIsSavedByADropOtherwiseTheTreeWilts() {
        val base = RewardsState(growthDays = 14, lastCountedDay = day.minusDays(2).toString(), drops = 1)
        val saved = RewardsEngine.evaluateDay(base, day)
        assertEquals(0, saved.drops)
        assertTrue(day.minusDays(1).toString() in saved.savedDays)
        assertEquals(Welcome.SAVED, RewardsEngine.welcomeFor(saved, day))

        val wilted = RewardsEngine.evaluateDay(base.copy(drops = 0), day)
        assertTrue(wilted.wilted)
        assertEquals(14, wilted.growthDays)
        assertEquals(Welcome.WILTED, RewardsEngine.welcomeFor(wilted, day))
        // Прочитали сегодня — дерево ожило и растёт дальше.
        val back = RewardsEngine.countDay(wilted, day, ArrayList())
        assertFalse(back.wilted)
        assertEquals(15, back.growthDays)
    }

    @Test
    fun twoMissedDaysShrinkTheTreeOneStage() {
        val base = RewardsState(growthDays = 20, lastCountedDay = day.minusDays(3).toString())
        val s = RewardsEngine.evaluateDay(base, day)
        assertEquals(DailyCatalog.STAGE_DAYS[3], s.growthDays)   // «дерево» (14) → «деревце» (7)
        assertTrue(s.wilted)
        assertNull(s.lastCountedDay)
        // Повторная проверка в тот же день ничего больше не отнимает.
        assertEquals(s, RewardsEngine.evaluateDay(s, day))
    }

    @Test
    fun dropsAccumulateWeeklyUpToTwo() {
        var s = RewardsState(growthDays = 6)
        s = RewardsEngine.countDay(s, day, ArrayList())
        assertEquals(1, s.drops)
        s = RewardsEngine.countDay(s.copy(growthDays = 13, drops = 2), day.plusDays(1), ArrayList())
        assertEquals(2, s.drops)
    }

    @Test
    fun welcomeShowsGrowthOncePerDay() {
        val s = RewardsState(growthDays = 15, lastCountedDay = day.minusDays(1).toString())
        assertEquals(Welcome.GREW, RewardsEngine.welcomeFor(s, day))
        assertNull(RewardsEngine.welcomeFor(s.copy(welcomeShownDay = day.toString()), day))
        assertNull(RewardsEngine.welcomeFor(s.copy(dailyEnabled = false), day))
        assertNull(RewardsEngine.welcomeFor(RewardsState(), day))
    }
}
