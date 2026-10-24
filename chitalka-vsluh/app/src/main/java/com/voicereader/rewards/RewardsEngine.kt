package com.voicereader.rewards

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Всё, что знает система наград. Хранится локально, никуда не отправляется. */
data class RewardsState(
    val points: Long = 0,
    /** Текущая серия без перерыва, секунды чтения (паузы не считаются). */
    val sessionSeconds: Int = 0,
    /** Сколько секунд осталось до того, как серия сгорит; null — пауза не идёт. */
    val pauseLeft: Int? = null,
    val bestSessionSeconds: Int = 0,
    val bestSessionDay: String? = null,
    /** Бонус за рекорд в этой серии уже выдан. */
    val recordAwarded: Boolean = false,
    /** Рекорд на момент начала текущей серии — с ним и сравниваем. */
    val bestBeforeSession: Int = 0,
    /** Сколько секунд читали в каждый день (yyyy-MM-dd). */
    val dayLog: Map<String, Int> = emptyMap(),
    /** Дни, пропуск которых спасла капля воды. */
    val savedDays: Set<String> = emptySet(),
    val growthDays: Int = 0,
    val lastCountedDay: String? = null,
    val drops: Int = 0,
    val wilted: Boolean = false,
    val welcomeShownDay: String? = null,
    val wordsCarry: Int = 0,
    // Оформление
    val growKind: GrowKind = GrowKind.TREE,
    val backgroundId: String? = null,
    val textId: String? = null,
    val font: ReaderFont = ReaderFont.DEFAULT,
    /** Выбранные анимации из наград: id награды по каждому виду. */
    val anims: Map<AnimKind, String> = emptyMap(),
    // Переключатели
    val ribbonEnabled: Boolean = true,
    val dailyEnabled: Boolean = true,
    val celebrations: Boolean = true
) {
    val rank: Int get() = RanksCatalog.rankFor(points)
    val medal: Int get() = FocusCatalog.medalFor(sessionSeconds)
    val bestMedal: Int get() = FocusCatalog.medalFor(bestSessionSeconds)
    val stage: Int get() = DailyCatalog.stageFor(growthDays)
    fun unlocked(reward: Reward) = rank >= reward.rank
    fun unlocked(kind: GrowKind) = kind == GrowKind.TREE ||
        RanksCatalog.REWARDS.any { it.grow == kind && unlocked(it) }
    /** Включённая анимация этого вида, если её ранг уже получен. */
    fun anim(kind: AnimKind): Reward? = RanksCatalog.byId(anims[kind])?.takeIf { it.anim == kind && unlocked(it) }
}

sealed interface RewardEvent {
    data class MedalUp(val medal: Int) : RewardEvent
    data class Record(val seconds: Int) : RewardEvent
    data class RankUp(val rank: Int) : RewardEvent
    data class DayCounted(val growthDays: Int) : RewardEvent
}

/** Чистые переходы состояния — без Android и часов, чтобы их можно было проверить тестами. */
object RewardsEngine {
    const val PAUSE_GRACE_SECONDS = 60
    const val CHAPTER_POINTS = 50
    const val MARATHON_POINTS = 15
    const val MARATHON_EVERY_SECONDS = 600
    const val RECORD_POINTS = 60
    const val WORDS_PER_POINT = 15

    fun multiplier(sessionSeconds: Int): Double = when {
        sessionSeconds >= 45 * 60 -> 2.0
        sessionSeconds >= 20 * 60 -> 1.5
        else -> 1.0
    }

    /** Одна секунда активного чтения. */
    fun tick(state: RewardsState, today: LocalDate, events: MutableList<RewardEvent>): RewardsState {
        val key = today.toString()
        val seconds = state.sessionSeconds + 1
        val bestBefore = if (state.sessionSeconds == 0) state.bestSessionSeconds else state.bestBeforeSession
        val readToday = (state.dayLog[key] ?: 0) + 1
        var next = state.copy(sessionSeconds = seconds, pauseLeft = null, bestBeforeSession = bestBefore, dayLog = state.dayLog + (key to readToday))
        if (FocusCatalog.medalFor(seconds) > FocusCatalog.medalFor(state.sessionSeconds))
            events += RewardEvent.MedalUp(FocusCatalog.medalFor(seconds))
        if (seconds % MARATHON_EVERY_SECONDS == 0) next = addPoints(next, MARATHON_POINTS.toLong(), events)
        // Рекорд празднуем один раз за серию, и только если прошлый рекорд был не совсем коротким.
        if (seconds > bestBefore && !next.recordAwarded && bestBefore >= 5 * 60) {
            events += RewardEvent.Record(seconds)
            next = addPoints(next.copy(recordAwarded = true), RECORD_POINTS.toLong(), events)
        }
        if (seconds > next.bestSessionSeconds) next = next.copy(bestSessionSeconds = seconds, bestSessionDay = key)
        if (readToday == DailyCatalog.DAY_GOAL_SECONDS) next = countDay(next, today, events)
        return next
    }

    fun sentenceRead(state: RewardsState, events: MutableList<RewardEvent>): RewardsState =
        addPoints(state, Math.round(multiplier(state.sessionSeconds)), events)

    fun wordsRead(state: RewardsState, count: Int, events: MutableList<RewardEvent>): RewardsState {
        val total = state.wordsCarry + count
        val points = total / WORDS_PER_POINT
        val next = state.copy(wordsCarry = total % WORDS_PER_POINT)
        return if (points == 0) next else addPoints(next, Math.round(points * multiplier(state.sessionSeconds)), events)
    }

    fun chapterFinished(state: RewardsState, events: MutableList<RewardEvent>) =
        addPoints(state, CHAPTER_POINTS.toLong(), events)

    fun addPoints(state: RewardsState, amount: Long, events: MutableList<RewardEvent>): RewardsState {
        val before = state.rank
        val next = state.copy(points = state.points + amount)
        if (next.rank > before) events += RewardEvent.RankUp(next.rank)
        return next
    }

    /** Пауза: отсчёт до сгорания серии. Возвращает новое состояние после одной секунды паузы. */
    fun pauseTick(state: RewardsState): RewardsState {
        val left = (state.pauseLeft ?: PAUSE_GRACE_SECONDS) - 1
        return if (left <= 0) endSession(state) else state.copy(pauseLeft = left)
    }

    fun endSession(state: RewardsState) = state.copy(sessionSeconds = 0, pauseLeft = null, recordAwarded = false)

    /** День набрал норму: дерево растёт, раз в неделю — капля воды. */
    fun countDay(state: RewardsState, today: LocalDate, events: MutableList<RewardEvent>): RewardsState {
        val key = today.toString()
        if (state.lastCountedDay == key) return state
        val days = state.growthDays + 1
        val drops = if (days % DailyCatalog.DROP_EVERY_DAYS == 0)
            (state.drops + 1).coerceAtMost(DailyCatalog.MAX_DROPS) else state.drops
        events += RewardEvent.DayCounted(days)
        return state.copy(growthDays = days, lastCountedDay = key, drops = drops, wilted = false)
    }

    /**
     * Проверка пропусков при открытии приложения и смене дня.
     * Один пропуск: капля спасает день, без капли — дерево вянет. Два и больше — откат на стадию.
     */
    fun evaluateDay(state: RewardsState, today: LocalDate): RewardsState {
        val last = state.lastCountedDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return state
        val gap = ChronoUnit.DAYS.between(last, today)
        if (gap <= 1) return state
        if (gap == 2L) {
            val missed = last.plusDays(1).toString()
            return if (state.drops > 0) state.copy(drops = state.drops - 1, savedDays = state.savedDays + missed, lastCountedDay = missed)
            else state.copy(wilted = true)
        }
        // Серия прервана: дерево уменьшается на одну стадию и дальше растёт с этого места.
        val stage = DailyCatalog.stageFor(state.growthDays)
        val shrunk = if (stage == 0) 0 else DailyCatalog.STAGE_DAYS[stage - 1]
        return state.copy(growthDays = shrunk, lastCountedDay = null, wilted = true)
    }

    /** Какой экран дня показать при первом заходе сегодня. */
    fun welcomeFor(state: RewardsState, today: LocalDate): Welcome? {
        if (!state.dailyEnabled || state.welcomeShownDay == today.toString()) return null
        if (state.lastCountedDay == today.toString()) return null
        if (state.wilted) return Welcome.WILTED
        val yesterday = today.minusDays(1).toString()
        if (state.lastCountedDay == yesterday && state.growthDays > 0)
            return if (yesterday in state.savedDays) Welcome.SAVED else Welcome.GREW
        return null
    }

    /** Старые записи дальше года не нужны даже для сетки. */
    fun prune(state: RewardsState, today: LocalDate): RewardsState {
        val border = today.minusDays(400).toString()
        return state.copy(
            dayLog = state.dayLog.filterKeys { it >= border },
            savedDays = state.savedDays.filterTo(HashSet()) { it >= border }
        )
    }
}

enum class Welcome { GREW, SAVED, WILTED }
