package com.voicereader.rewards

import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import com.voicereader.R

/** Медали «без перерыва»: 11 ступеней по 5 рангов, ранг — доля минут внутри ступени. */
object FocusCatalog {
    @StringRes val TIER_NAMES = intArrayOf(
        R.string.tier_wood, R.string.tier_stone, R.string.tier_iron, R.string.tier_bronze, R.string.tier_silver,
        R.string.tier_gold, R.string.tier_platinum, R.string.tier_sapphire, R.string.tier_emerald,
        R.string.tier_ruby, R.string.tier_diamond
    )
    /** Верхняя граница ступени в минутах. */
    val TIER_UPPER = intArrayOf(5, 10, 15, 20, 30, 45, 60, 80, 100, 130, 180)
    const val RANKS_PER_TIER = 5
    val COUNT = TIER_UPPER.size * RANKS_PER_TIER
    val ROMAN = arrayOf("I", "II", "III", "IV", "V")

    fun tierLower(tier: Int): Int = if (tier == 0) 0 else TIER_UPPER[tier - 1]

    /** Минуты без перерыва, нужные для медали с этим индексом (0 — Дерево I). */
    fun thresholdMinutes(index: Int): Float {
        val tier = index / RANKS_PER_TIER
        val rank = index % RANKS_PER_TIER
        val lower = tierLower(tier)
        val step = (TIER_UPPER[tier] - lower) / RANKS_PER_TIER.toFloat()
        return lower + step * (rank + 1)
    }

    /** Индекс последней полученной медали, -1 — ещё ни одной. */
    fun medalFor(seconds: Int): Int {
        var result = -1
        for (i in 0 until COUNT) if (seconds >= thresholdMinutes(i) * 60) result = i else break
        return result
    }

    /** Доля пути от полученной медали к следующей. */
    fun progressToNext(seconds: Int): Float {
        val current = medalFor(seconds)
        if (current >= COUNT - 1) return 1f
        val from = if (current < 0) 0f else thresholdMinutes(current) * 60
        val to = thresholdMinutes(current + 1) * 60
        return ((seconds - from) / (to - from)).coerceIn(0f, 1f)
    }
}

/** Ежедневный рост: 11 стадий по числу дней подряд. */
object DailyCatalog {
    val STAGE_DAYS = intArrayOf(0, 1, 3, 7, 14, 21, 30, 60, 100, 180, 365)
    /** Сколько минут в день засчитывает день. */
    const val DAY_GOAL_SECONDS = 10 * 60
    /** Капля воды копится каждые 7 дней, больше двух не держится. */
    const val DROP_EVERY_DAYS = 7
    const val MAX_DROPS = 2

    fun stageFor(days: Int): Int = STAGE_DAYS.indexOfLast { days >= it }.coerceAtLeast(0)
    fun daysToNextStage(days: Int): Int? {
        val next = STAGE_DAYS.firstOrNull { it > days } ?: return null
        return next - days
    }
}

enum class GrowKind(@StringRes val title: Int, @ArrayRes val stagesRes: Int, @StringRes val grew: Int, @StringRes val wilted: Int) {
    TREE(R.string.grow_tree, R.array.stages_tree, R.string.grew_tree, R.string.wilted_tree),
    MUSHROOM(R.string.grow_mushroom, R.array.stages_mushroom, R.string.grew_mushroom, R.string.wilted_mushroom),
    FIRE(R.string.grow_fire, R.array.stages_fire, R.string.grew_fire, R.string.wilted_fire),
    CRYSTAL(R.string.grow_crystal, R.array.stages_crystal, R.string.grew_crystal, R.string.wilted_crystal),
    GARDEN(R.string.grow_garden, R.array.stages_garden, R.string.grew_garden, R.string.wilted_garden)
}

enum class Rarity(@StringRes val title: Int) {
    COMMON(R.string.rarity_common), RARE(R.string.rarity_rare), LEGENDARY(R.string.rarity_legendary)
}

enum class RewardKind(@StringRes val title: Int) {
    BACKGROUND(R.string.kind_background), TEXT(R.string.kind_text), FONT(R.string.kind_font),
    GROW(R.string.kind_grow), VOICE(R.string.kind_voice)
}

/** Шрифты из наград: встроены в приложение, все с кириллицей. */
enum class ReaderFont(@StringRes val title: Int) {
    DEFAULT(R.string.font_default), LITERARY(R.string.font_literary), TYPEWRITER(R.string.font_typewriter),
    ANTIQUE(R.string.font_antique), ROUNDED(R.string.font_rounded), HANDWRITING(R.string.font_handwriting),
    NEWSPAPER(R.string.font_newspaper)
}

/** Манера чтения: тот же голос с другим темпом и паузами между предложениями. */
enum class VoiceManner(@StringRes val title: Int, val rate: Float, val pauseMs: Int) {
    NORMAL(R.string.manner_normal, 1f, 0),
    STORYTELLER(R.string.manner_storyteller, 0.86f, 450),
    ANNOUNCER(R.string.manner_announcer, 1.12f, 0)
}

/**
 * Награда за звание. [value] — что именно открывается: цвет (ARGB), шрифт, вид роста,
 * манера или id нейросетевого голоса.
 */
data class Reward(
    val rank: Int,
    val kind: RewardKind,
    @StringRes val title: Int,
    val rarity: Rarity,
    val color: Long = 0,
    val color2: Long = 0,
    val font: ReaderFont? = null,
    val grow: GrowKind? = null,
    val manner: VoiceManner? = null,
    val voiceId: String? = null,
    val id: String
)

object RanksCatalog {
    const val COUNT = 30
    val POINTS = longArrayOf(
        0, 50, 150, 300, 500, 750, 1050, 1400, 1800, 2300, 2900, 3600, 4400, 5300, 6300,
        7400, 8600, 9900, 11300, 12800, 14500, 16300, 18200, 20200, 22300, 24600, 27000, 29600, 32400, 35500
    )

    fun rankFor(points: Long): Int = POINTS.indexOfLast { points >= it }.coerceAtLeast(0)

    private fun bg(rank: Int, title: Int, rarity: Rarity, color: Long, color2: Long = 0, id: String) =
        Reward(rank, RewardKind.BACKGROUND, title, rarity, color = color, color2 = color2, id = id)
    private fun text(rank: Int, title: Int, rarity: Rarity, color: Long, id: String) =
        Reward(rank, RewardKind.TEXT, title, rarity, color = color, id = id)
    private fun font(rank: Int, title: Int, rarity: Rarity, font: ReaderFont) =
        Reward(rank, RewardKind.FONT, title, rarity, font = font, id = "font_${font.name}")
    private fun grow(rank: Int, rarity: Rarity, kind: GrowKind) =
        Reward(rank, RewardKind.GROW, kind.title, rarity, grow = kind, id = "grow_${kind.name}")

    /** Награды по званиям (индекс звания 1..29), виды чередуются. */
    val REWARDS: List<Reward> = listOf(
        bg(1, R.string.bg_sepia, Rarity.COMMON, 0xFF2B2419, id = "bg_sepia"),
        text(2, R.string.text_amber, Rarity.COMMON, 0xFFFFCF8A, "text_amber"),
        font(3, R.string.font_literary, Rarity.COMMON, ReaderFont.LITERARY),
        grow(4, Rarity.COMMON, GrowKind.MUSHROOM),
        bg(5, R.string.bg_ocean, Rarity.RARE, 0xFF0D1D2E, id = "bg_ocean"),
        text(6, R.string.text_mint, Rarity.COMMON, 0xFF9FE8C7, "text_mint"),
        Reward(7, RewardKind.VOICE, R.string.manner_storyteller, Rarity.RARE, manner = VoiceManner.STORYTELLER, id = "manner_storyteller"),
        font(8, R.string.font_typewriter, Rarity.COMMON, ReaderFont.TYPEWRITER),
        grow(9, Rarity.RARE, GrowKind.FIRE),
        bg(10, R.string.bg_stars, Rarity.LEGENDARY, 0xFF0B1030, 0xFF2A2470, id = "bg_stars"),
        text(11, R.string.text_lavender, Rarity.COMMON, 0xFFC9B8FF, "text_lavender"),
        font(12, R.string.font_antique, Rarity.RARE, ReaderFont.ANTIQUE),
        bg(13, R.string.bg_parchment, Rarity.COMMON, 0xFF3A3021, id = "bg_parchment"),
        text(14, R.string.text_coral, Rarity.RARE, 0xFFFF9E8A, "text_coral"),
        grow(15, Rarity.LEGENDARY, GrowKind.CRYSTAL),
        bg(16, R.string.bg_forest, Rarity.COMMON, 0xFF14261A, id = "bg_forest"),
        font(17, R.string.font_rounded, Rarity.COMMON, ReaderFont.ROUNDED),
        text(18, R.string.text_sky, Rarity.COMMON, 0xFF9FD0FF, "text_sky"),
        Reward(19, RewardKind.VOICE, R.string.manner_announcer, Rarity.RARE, manner = VoiceManner.ANNOUNCER, id = "manner_announcer"),
        font(20, R.string.font_handwriting, Rarity.LEGENDARY, ReaderFont.HANDWRITING),
        bg(21, R.string.bg_wine, Rarity.RARE, 0xFF2E1219, id = "bg_wine"),
        text(22, R.string.text_rose, Rarity.COMMON, 0xFFFFB3CF, "text_rose"),
        grow(23, Rarity.RARE, GrowKind.GARDEN),
        bg(24, R.string.bg_graphite, Rarity.COMMON, 0xFF1C1C1E, id = "bg_graphite"),
        Reward(25, RewardKind.VOICE, R.string.voice_sima, Rarity.LEGENDARY, voiceId = "sima", id = "voice_sima"),
        text(26, R.string.text_platinum, Rarity.RARE, 0xFFE8F6FB, "text_platinum"),
        font(27, R.string.font_newspaper, Rarity.COMMON, ReaderFont.NEWSPAPER),
        bg(28, R.string.bg_aurora, Rarity.RARE, 0xFF0B2A2A, 0xFF3A0F3A, id = "bg_aurora"),
        text(29, R.string.text_gold, Rarity.LEGENDARY, 0xFFFFD86B, "text_gold")
    )

    fun rewardFor(rank: Int): Reward? = REWARDS.firstOrNull { it.rank == rank }
    fun byId(id: String?): Reward? = REWARDS.firstOrNull { it.id == id }
}
