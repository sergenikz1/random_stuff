package com.voicereader.ui.rewards

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.voicereader.R
import com.voicereader.rewards.RanksCatalog
import com.voicereader.rewards.ReaderFont
import com.voicereader.rewards.Reward
import com.voicereader.rewards.RewardsState

/** Оформление читалки, собранное из выбранных наград. null — стандартное. */
data class ReaderLook(val background: Brush?, val backgroundColor: Color?, val text: Color?, val font: FontFamily?)

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
            else -> R.font.reward_newspaper
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
    return ReaderLook(
        background = bg?.let { rewardBrush(it) },
        backgroundColor = bg?.let { Color(it.color) },
        text = text?.let { Color(it.color) },
        font = font
    )
}
