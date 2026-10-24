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
    /** Сколько чтения в день засчитывает день. */
    const val DAY_GOAL_SECONDS = 60
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
    GARDEN(R.string.grow_garden, R.array.stages_garden, R.string.grew_garden, R.string.wilted_garden),
    CACTUS(R.string.grow_cactus, R.array.stages_cactus, R.string.grew_cactus, R.string.wilted_cactus),
    STARS(R.string.grow_stars, R.array.stages_stars, R.string.grew_stars, R.string.wilted_stars),
    CASTLE(R.string.grow_castle, R.array.stages_castle, R.string.grew_castle, R.string.wilted_castle),
    CORAL(R.string.grow_coral, R.array.stages_coral, R.string.grew_coral, R.string.wilted_coral)
}

enum class Rarity(@StringRes val title: Int) {
    COMMON(R.string.rarity_common), RARE(R.string.rarity_rare), LEGENDARY(R.string.rarity_legendary)
}

enum class RewardKind(@StringRes val title: Int) {
    BACKGROUND(R.string.kind_background), TEXT(R.string.kind_text), FONT(R.string.kind_font),
    GROW(R.string.kind_grow), VOICE(R.string.kind_voice), ANIM(R.string.kind_anim)
}

/** Виды анимаций среди наград. */
enum class AnimKind(@StringRes val title: Int, @StringRes val hint: Int) {
    LIVE(R.string.anim_live, R.string.anim_live_hint),
    HIGHLIGHT(R.string.anim_highlight, R.string.anim_highlight_hint),
    CELEBRATE(R.string.anim_celebrate, R.string.anim_celebrate_hint),
    CHAPTER(R.string.anim_chapter, R.string.anim_chapter_hint),
    RING(R.string.anim_ring, R.string.anim_ring_hint)
}

/** Шрифты из наград: встроены в приложение, все с кириллицей. */
enum class ReaderFont(@StringRes val title: Int) {
    DEFAULT(R.string.font_default), LITERARY(R.string.font_literary), TYPEWRITER(R.string.font_typewriter),
    ANTIQUE(R.string.font_antique), ROUNDED(R.string.font_rounded), HANDWRITING(R.string.font_handwriting),
    NEWSPAPER(R.string.font_newspaper), BOOKISH(R.string.font_bookish), GARAMOND(R.string.font_garamond),
    ELEGANT(R.string.font_elegant), DENSE(R.string.font_dense), SLAB(R.string.font_slab), CLEAR(R.string.font_clear),
    SCHOOL(R.string.font_school), CALLIGRAPHY(R.string.font_calligraphy), PHILOSOPHER(R.string.font_philosopher),
    POSTER(R.string.font_poster)
}

/**
 * Награда за ранг. Что именно открывается: цвет (ARGB), шрифт, вид роста, id нейросетевого голоса
 * или анимация ([anim] + имя эффекта в [effect]).
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
    val voiceId: String? = null,
    val anim: AnimKind? = null,
    val effect: String? = null,
    val id: String
)

/** 100 рангов: 20 званий по 5 ступеней. Награда за каждый ранг, кроме первого. */
object RanksCatalog {
    const val COUNT = 100
    const val STEPS_PER_TITLE = 5
    val ROMAN = arrayOf("I", "II", "III", "IV", "V")

    /** Очков до ранга: шаг растёт от 50 ✦ (несколько минут чтения) до ~5 650 ✦ (около шести часов). */
    val POINTS: LongArray = LongArray(COUNT).also { p ->
        for (n in 1 until COUNT) p[n] = p[n - 1] + step(n)
    }

    private fun step(n: Int): Long {
        val v = 40 + 5.7 * Math.pow(n.toDouble(), 1.5)
        return if (v < 1000) Math.round(v / 10) * 10 else Math.round(v / 50) * 50
    }

    fun rankFor(points: Long): Int = POINTS.indexOfLast { points >= it }.coerceAtLeast(0)
    fun titleOf(rank: Int): Int = rank / STEPS_PER_TITLE
    fun stepOf(rank: Int): Int = rank % STEPS_PER_TITLE
    /** Эпоха оправы значка: медная, серебряная, золотая, звёздная. */
    fun eraOf(rank: Int): Int = titleOf(rank) / 5

    private fun bg(rank: Int, title: Int, rarity: Rarity, color: Long, color2: Long = 0, id: String) =
        Reward(rank, RewardKind.BACKGROUND, title, rarity, color = color, color2 = color2, id = id)
    private fun text(rank: Int, title: Int, rarity: Rarity, color: Long, id: String) =
        Reward(rank, RewardKind.TEXT, title, rarity, color = color, id = id)
    private fun font(rank: Int, title: Int, rarity: Rarity, font: ReaderFont) =
        Reward(rank, RewardKind.FONT, title, rarity, font = font, id = "font_${font.name}")
    private fun grow(rank: Int, rarity: Rarity, kind: GrowKind) =
        Reward(rank, RewardKind.GROW, kind.title, rarity, grow = kind, id = "grow_${kind.name}")
    private fun anim(rank: Int, title: Int, rarity: Rarity, kind: AnimKind, effect: String) =
        Reward(rank, RewardKind.ANIM, title, rarity, anim = kind, effect = effect, id = "fx_${kind.name}_$effect")

    /** Награды по рангам 1..99. Виды чередуются, легендарные — на каждом десятом ранге и в конце. */
    val REWARDS: List<Reward> = listOf(
        bg(1, R.string.bg_sepia, Rarity.COMMON, 0xFF2B2419, id = "bg_sepia"),
        text(2, R.string.text_amber, Rarity.COMMON, 0xFFFFCF8A, "text_amber"),
        font(3, R.string.font_literary, Rarity.COMMON, ReaderFont.LITERARY),
        grow(4, Rarity.RARE, GrowKind.MUSHROOM),
        anim(5, R.string.fx_live_snow, Rarity.RARE, AnimKind.LIVE, "SNOW"),
        bg(6, R.string.bg_ocean, Rarity.COMMON, 0xFF0D1D2E, id = "bg_ocean"),
        anim(7, R.string.fx_highlight_wave, Rarity.RARE, AnimKind.HIGHLIGHT, "WAVE"),
        text(8, R.string.text_mint, Rarity.COMMON, 0xFF9FE8C7, "text_mint"),
        font(9, R.string.font_typewriter, Rarity.COMMON, ReaderFont.TYPEWRITER),
        anim(10, R.string.fx_live_stars, Rarity.LEGENDARY, AnimKind.LIVE, "STARS"),
        bg(11, R.string.bg_parchment, Rarity.COMMON, 0xFF3A3021, id = "bg_parchment"),
        anim(12, R.string.fx_celebrate_confetti, Rarity.RARE, AnimKind.CELEBRATE, "CONFETTI"),
        text(13, R.string.text_lavender, Rarity.COMMON, 0xFFC9B8FF, "text_lavender"),
        anim(14, R.string.fx_chapter_flip, Rarity.RARE, AnimKind.CHAPTER, "FLIP"),
        font(15, R.string.font_antique, Rarity.RARE, ReaderFont.ANTIQUE),
        bg(16, R.string.bg_forest, Rarity.COMMON, 0xFF14261A, id = "bg_forest"),
        anim(17, R.string.fx_live_fireflies, Rarity.RARE, AnimKind.LIVE, "FIREFLIES"),
        text(18, R.string.text_coral, Rarity.COMMON, 0xFFFF9E8A, "text_coral"),
        grow(19, Rarity.RARE, GrowKind.FIRE),
        font(20, R.string.font_handwriting, Rarity.LEGENDARY, ReaderFont.HANDWRITING),
        bg(21, R.string.bg_graphite, Rarity.COMMON, 0xFF1C1C1E, id = "bg_graphite"),
        anim(22, R.string.fx_live_rain, Rarity.RARE, AnimKind.LIVE, "RAIN"),
        font(23, R.string.font_rounded, Rarity.COMMON, ReaderFont.ROUNDED),
        text(24, R.string.text_sky, Rarity.COMMON, 0xFF9FD0FF, "text_sky"),
        anim(25, R.string.fx_highlight_marker, Rarity.RARE, AnimKind.HIGHLIGHT, "MARKER"),
        bg(26, R.string.bg_wine, Rarity.COMMON, 0xFF2E1219, id = "bg_wine"),
        anim(27, R.string.fx_ring_flame, Rarity.RARE, AnimKind.RING, "FLAME"),
        text(28, R.string.text_rose, Rarity.COMMON, 0xFFFFB3CF, "text_rose"),
        font(29, R.string.font_newspaper, Rarity.COMMON, ReaderFont.NEWSPAPER),
        grow(30, Rarity.LEGENDARY, GrowKind.CRYSTAL),
        bg(31, R.string.bg_plum, Rarity.COMMON, 0xFF261A30, id = "bg_plum"),
        anim(32, R.string.fx_celebrate_starfall, Rarity.RARE, AnimKind.CELEBRATE, "STARFALL"),
        text(33, R.string.text_platinum, Rarity.COMMON, 0xFFE8F6FB, "text_platinum"),
        bg(34, R.string.bg_milk_tea, Rarity.COMMON, 0xFF3B3128, id = "bg_milk_tea"),
        font(35, R.string.font_bookish, Rarity.RARE, ReaderFont.BOOKISH),
        grow(36, Rarity.RARE, GrowKind.GARDEN),
        anim(37, R.string.fx_live_leaves, Rarity.RARE, AnimKind.LIVE, "LEAVES"),
        bg(38, R.string.bg_midnight, Rarity.COMMON, 0xFF0E1424, id = "bg_midnight"),
        text(39, R.string.text_lemon, Rarity.COMMON, 0xFFF3F19A, "text_lemon"),
        anim(40, R.string.fx_celebrate_fireworks, Rarity.LEGENDARY, AnimKind.CELEBRATE, "FIREWORKS"),
        font(41, R.string.font_garamond, Rarity.COMMON, ReaderFont.GARAMOND),
        anim(42, R.string.fx_chapter_dissolve, Rarity.RARE, AnimKind.CHAPTER, "DISSOLVE"),
        bg(43, R.string.bg_moss, Rarity.COMMON, 0xFF1D2618, id = "bg_moss"),
        anim(44, R.string.fx_highlight_pen, Rarity.RARE, AnimKind.HIGHLIGHT, "PEN"),
        text(45, R.string.text_peach, Rarity.RARE, 0xFFFFC9A8, "text_peach"),
        anim(46, R.string.fx_live_candle, Rarity.RARE, AnimKind.LIVE, "CANDLE"),
        bg(47, R.string.bg_coffee, Rarity.COMMON, 0xFF2A1D16, id = "bg_coffee"),
        font(48, R.string.font_elegant, Rarity.COMMON, ReaderFont.ELEGANT),
        text(49, R.string.text_sage, Rarity.COMMON, 0xFFC5D9B0, "text_sage"),
        Reward(50, RewardKind.VOICE, R.string.voice_sima, Rarity.LEGENDARY, voiceId = "sima", id = "voice_sima"),
        anim(51, R.string.fx_celebrate_petals, Rarity.RARE, AnimKind.CELEBRATE, "PETALS"),
        bg(52, R.string.bg_dusk, Rarity.COMMON, 0xFF1F1C2E, id = "bg_dusk"),
        grow(53, Rarity.RARE, GrowKind.CACTUS),
        anim(54, R.string.fx_live_sea, Rarity.RARE, AnimKind.LIVE, "SEA"),
        text(55, R.string.text_ice, Rarity.RARE, 0xFFCDEEFF, "text_ice"),
        font(56, R.string.font_dense, Rarity.COMMON, ReaderFont.DENSE),
        bg(57, R.string.bg_deep, Rarity.COMMON, 0xFF081A1F, id = "bg_deep"),
        anim(58, R.string.fx_highlight_ink, Rarity.RARE, AnimKind.HIGHLIGHT, "INK"),
        text(59, R.string.text_lilac, Rarity.COMMON, 0xFFE0B8FF, "text_lilac"),
        bg(60, R.string.bg_stars, Rarity.LEGENDARY, 0xFF0B1030, 0xFF2A2470, "bg_stars"),
        anim(61, R.string.fx_chapter_scroll, Rarity.RARE, AnimKind.CHAPTER, "SCROLL"),
        bg(62, R.string.bg_ash, Rarity.COMMON, 0xFF26262B, id = "bg_ash"),
        font(63, R.string.font_slab, Rarity.COMMON, ReaderFont.SLAB),
        anim(64, R.string.fx_live_sakura, Rarity.RARE, AnimKind.LIVE, "SAKURA"),
        text(65, R.string.text_sand, Rarity.RARE, 0xFFE8D5A8, "text_sand"),
        bg(66, R.string.bg_terracotta, Rarity.COMMON, 0xFF33201A, id = "bg_terracotta"),
        font(67, R.string.font_clear, Rarity.COMMON, ReaderFont.CLEAR),
        grow(68, Rarity.RARE, GrowKind.CASTLE),
        anim(69, R.string.fx_celebrate_lightning, Rarity.RARE, AnimKind.CELEBRATE, "LIGHTNING"),
        grow(70, Rarity.LEGENDARY, GrowKind.STARS),
        bg(71, R.string.bg_indigo, Rarity.COMMON, 0xFF141A3A, id = "bg_indigo"),
        text(72, R.string.text_turquoise, Rarity.COMMON, 0xFF7FE0D9, "text_turquoise"),
        anim(73, R.string.fx_live_bubbles, Rarity.RARE, AnimKind.LIVE, "BUBBLES"),
        bg(74, R.string.bg_pine, Rarity.COMMON, 0xFF0F2420, id = "bg_pine"),
        font(75, R.string.font_school, Rarity.RARE, ReaderFont.SCHOOL),
        text(76, R.string.text_cherry, Rarity.COMMON, 0xFFFF8FA8, "text_cherry"),
        anim(77, R.string.fx_highlight_neon, Rarity.RARE, AnimKind.HIGHLIGHT, "NEON"),
        bg(78, R.string.bg_plum_honey, Rarity.COMMON, 0xFF2A1830, 0xFF3A2A14, "bg_plum_honey"),
        text(79, R.string.text_sepia_ink, Rarity.COMMON, 0xFFD9B991, "text_sepia_ink"),
        anim(80, R.string.fx_live_aurora, Rarity.LEGENDARY, AnimKind.LIVE, "AURORA"),
        font(81, R.string.font_calligraphy, Rarity.COMMON, ReaderFont.CALLIGRAPHY),
        anim(82, R.string.fx_ring_electric, Rarity.RARE, AnimKind.RING, "ELECTRIC"),
        bg(83, R.string.bg_aurora, Rarity.COMMON, 0xFF0B2A2A, 0xFF3A0F3A, "bg_aurora"),
        anim(84, R.string.fx_live_meteors, Rarity.RARE, AnimKind.LIVE, "METEORS"),
        grow(85, Rarity.RARE, GrowKind.CORAL),
        text(86, R.string.text_emerald, Rarity.COMMON, 0xFF7FF0B0, "text_emerald"),
        anim(87, R.string.fx_chapter_slide, Rarity.RARE, AnimKind.CHAPTER, "SLIDE"),
        bg(88, R.string.bg_sunset, Rarity.COMMON, 0xFF2E1420, 0xFF3A2410, "bg_sunset"),
        font(89, R.string.font_philosopher, Rarity.COMMON, ReaderFont.PHILOSOPHER),
        anim(90, R.string.fx_ring_rainbow, Rarity.LEGENDARY, AnimKind.RING, "RAINBOW"),
        text(91, R.string.text_sapphire, Rarity.COMMON, 0xFF8FB8FF, "text_sapphire"),
        anim(92, R.string.fx_celebrate_sparks, Rarity.RARE, AnimKind.CELEBRATE, "SPARKS"),
        bg(93, R.string.bg_paper, Rarity.COMMON, 0xFFEFE6D2, id = "bg_paper"),
        anim(94, R.string.fx_highlight_gold_dust, Rarity.RARE, AnimKind.HIGHLIGHT, "GOLD_DUST"),
        font(95, R.string.font_poster, Rarity.RARE, ReaderFont.POSTER),
        text(96, R.string.text_ruby, Rarity.COMMON, 0xFFFF7A93, "text_ruby"),
        bg(97, R.string.bg_mint_paper, Rarity.COMMON, 0xFFDFEEE4, id = "bg_mint_paper"),
        anim(98, R.string.fx_live_nebula, Rarity.RARE, AnimKind.LIVE, "NEBULA"),
        text(99, R.string.text_gold, Rarity.LEGENDARY, 0xFFFFD86B, "text_gold")
    )

    fun rewardFor(rank: Int): Reward? = REWARDS.firstOrNull { it.rank == rank }
    fun byId(id: String?): Reward? = if (id == null) null else REWARDS.firstOrNull { it.id == id }
}
