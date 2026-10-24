package com.voicereader.ui.rewards

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.voicereader.R
import com.voicereader.rewards.AnimKind
import com.voicereader.rewards.RanksCatalog
import com.voicereader.rewards.ReaderFont
import com.voicereader.rewards.Reward
import com.voicereader.rewards.RewardsState

/**
 * Оформление читалки, собранное из выбранных наград. null — стандартное.
 * [light] — светлый фон: текст и подсветка слов тогда тёмные.
 * Анимации — имена эффектов из наград (см. Effects.kt).
 */
data class ReaderLook(
    val background: Brush?,
    val backgroundColor: Color?,
    val text: Color?,
    val font: FontFamily?,
    val light: Boolean = false,
    val live: String? = null,
    val highlight: String? = null,
    val celebrate: String? = null,
    val chapter: String? = null,
    val ring: String? = null
)

private val fonts = HashMap<ReaderFont, FontFamily>()

fun fontFamily(font: ReaderFont): FontFamily? = when (font) {
    ReaderFont.DEFAULT -> null
    else -> fonts.getOrPut(font) {
        FontFamily(Font(when (font) {
            ReaderFont.LITERARY -> R.font.reward_literary
            ReaderFont.TYPEWRITER -> R.font.reward_typewriter
            ReaderFont.ANTIQUE -> R.font.reward_antique
            ReaderFont.ROUNDED -> R.font.reward_rounded
            ReaderFont.HANDWRITING -> R.font.reward_handwriting
            ReaderFont.NEWSPAPER -> R.font.reward_newspaper
            ReaderFont.BOOKISH -> R.font.reward_bookish
            ReaderFont.GARAMOND -> R.font.reward_garamond
            ReaderFont.ELEGANT -> R.font.reward_elegant
            ReaderFont.DENSE -> R.font.reward_dense
            ReaderFont.SLAB -> R.font.reward_slab
            ReaderFont.CLEAR -> R.font.reward_clear
            ReaderFont.SCHOOL -> R.font.reward_school
            ReaderFont.CALLIGRAPHY -> R.font.reward_calligraphy
            ReaderFont.PHILOSOPHER -> R.font.reward_philosopher
            else -> R.font.reward_poster
        }))
    }
}

fun rewardBrush(reward: Reward): Brush =
    if (reward.color2 != 0L) Brush.linearGradient(listOf(Color(reward.color), Color(reward.color2)))
    else Brush.linearGradient(listOf(Color(reward.color), Color(reward.color)))

fun lookOf(state: RewardsState): ReaderLook {
    // Выбранное применяется, только если звание его действительно открывает.
    val bg = RanksCatalog.byId(state.backgroundId)?.takeIf { state.unlocked(it) }
    val text = RanksCatalog.byId(state.textId)?.takeIf { state.unlocked(it) }
    val fontReward = RanksCatalog.REWARDS.firstOrNull { it.font == state.font }
    val font = if (fontReward == null || state.unlocked(fontReward)) fontFamily(state.font) else null
    val light = bg != null && Color(bg.color).luminance() > 0.5f
    // На светлой бумаге светлый цвет текста не читается — берём его тёмный вариант.
    val textColor = text?.let { Color(it.color) }?.let { if (light) lerp(it, Color.Black, 0.6f) else it }
        ?: if (light) Color(0xFF2A2620) else null
    return ReaderLook(
        background = bg?.let { rewardBrush(it) },
        backgroundColor = bg?.let { Color(it.color) },
        text = textColor,
        font = font,
        light = light,
        live = state.anim(AnimKind.LIVE)?.effect,
        highlight = state.anim(AnimKind.HIGHLIGHT)?.effect,
        celebrate = state.anim(AnimKind.CELEBRATE)?.effect,
        chapter = state.anim(AnimKind.CHAPTER)?.effect,
        ring = state.anim(AnimKind.RING)?.effect
    )
}
