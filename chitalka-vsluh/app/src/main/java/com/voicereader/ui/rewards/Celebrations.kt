package com.voicereader.ui.rewards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicereader.R
import com.voicereader.rewards.DailyCatalog
import com.voicereader.rewards.GrowKind
import com.voicereader.rewards.RanksCatalog
import com.voicereader.rewards.Rarity
import com.voicereader.rewards.Reward
import com.voicereader.rewards.RewardEvent
import com.voicereader.rewards.RewardKind
import com.voicereader.rewards.Rewards
import com.voicereader.rewards.RewardsState
import com.voicereader.rewards.Welcome
import kotlinx.coroutines.delay
import java.text.NumberFormat

fun rarityColor(r: Rarity) = when (r) {
    Rarity.COMMON -> Color(0xFF7F8AA0); Rarity.RARE -> Color(0xFFA07CFF); Rarity.LEGENDARY -> Color(0xFFFFB340)
}

/** «Читатель III»: звание и ступень. */
@Composable
fun rankName(rank: Int): String {
    val r = rank.coerceIn(0, RanksCatalog.COUNT - 1)
    return stringArrayResource(R.array.rank_titles)[RanksCatalog.titleOf(r)] + " " + RanksCatalog.ROMAN[RanksCatalog.stepOf(r)]
}

fun formatPoints(points: Long): String = NumberFormat.getIntegerInstance().format(points)

/** Небольшой предпросмотр награды: фон, цвет текста, шрифт, рост или голос. */
@Composable
fun RewardPreview(reward: Reward, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                when (reward.kind) {
                    RewardKind.BACKGROUND -> rewardBrush(reward)
                    RewardKind.GROW -> Brush.linearGradient(listOf(Color(0xFF16201A), Color(0xFF16201A)))
                    RewardKind.TEXT -> Brush.linearGradient(listOf(Color(0xFF101216), Color(0xFF101216)))
                    else -> Brush.linearGradient(listOf(Color(0xFF1B2029), Color(0xFF1B2029)))
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        when (reward.kind) {
            RewardKind.BACKGROUND -> Text("Аа", color = Color(0xFFE8DCC8), fontSize = 20.sp, fontFamily = FontFamily.Serif)
            RewardKind.TEXT -> Text("Аа", color = Color(reward.color), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            RewardKind.FONT -> Text("Аа", color = Color(0xFFE6EAF2), fontSize = 26.sp, fontFamily = reward.font?.let { fontFamily(it) })
            RewardKind.GROW -> GrowthArt(reward.grow ?: GrowKind.TREE, 6, Modifier.size(70.dp))
            RewardKind.VOICE -> VoiceWave(if (reward.rarity == Rarity.LEGENDARY) Color(0xFFFFB340) else Color(0xFFA07CFF))
            RewardKind.ANIM -> AnimPreview(reward, Modifier.matchParentSize())
        }
    }
}

@Composable
fun VoiceWave(color: Color, modifier: Modifier = Modifier.size(90.dp, 40.dp)) {
    Canvas(modifier) {
        val bars = 15
        val w = size.width / bars
        for (i in 0 until bars) {
            val h = (6 + kotlin.math.abs(kotlin.math.sin(i * 1.3)) * 26).toFloat() / 40f * size.height
            drawRoundRect(color, Offset(i * w, (size.height - h) / 2), androidx.compose.ui.geometry.Size(w * 0.6f, h),
                androidx.compose.ui.geometry.CornerRadius(w * .3f))
        }
    }
}

/** Карточка награды за звание: вид сверху, предпросмотр, название, редкость. */
@Composable
fun RewardCard(reward: Reward, unlocked: Boolean, modifier: Modifier = Modifier) {
    val rc = rarityColor(reward.rarity)
    val bg = when (reward.rarity) {
        Rarity.COMMON -> listOf(Color(0xFF232A38), Color(0xFF191D26))
        Rarity.RARE -> listOf(Color(0xFF272042), Color(0xFF1A1A2A))
        Rarity.LEGENDARY -> listOf(Color(0xFF3A2A10), Color(0xFF1F1A12))
    }
    Box(modifier.width(140.dp).padding(top = 8.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(bg))
                .border(1.dp, rc.copy(alpha = if (reward.rarity == Rarity.COMMON) 0.35f else 0.7f), RoundedCornerShape(18.dp))
                .padding(8.dp, 14.dp, 8.dp, 10.dp)
                .then(if (unlocked) Modifier else Modifier.background(Color(0x66101216))),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            RewardPreview(reward, Modifier.fillMaxWidth().height(66.dp))
            Spacer(Modifier.height(7.dp))
            Text(stringResource(reward.title), fontSize = 12.5.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, lineHeight = 15.sp)
            Text(stringResource(reward.rarity.title), fontSize = 9.5.sp, color = rc, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
            Text(
                if (unlocked) stringResource(R.string.reward_unlocked)
                else stringResource(R.string.reward_at_points, formatPoints(RanksCatalog.POINTS[reward.rank])),
                fontSize = 10.sp, color = Color(0xFFB4BCCB)
            )
        }
        Text(
            stringResource(reward.kind.title),
            fontSize = 9.5.sp, letterSpacing = 0.8.sp,
            fontWeight = if (reward.rarity == Rarity.LEGENDARY) FontWeight.Bold else FontWeight.Medium,
            color = when (reward.rarity) { Rarity.LEGENDARY -> Color(0xFF2A1A05); Rarity.RARE -> Color(0xFFE8DEFF); else -> Color(0xFFCFE0FF) },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-8).dp)
                .background(when (reward.rarity) { Rarity.LEGENDARY -> Color(0xFFB07A1E); Rarity.RARE -> Color(0xFF4A3A8F); else -> Color(0xFF2A3140) }, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        )
        if (!unlocked) Icon(Icons.Filled.Lock, null, tint = Color(0xFF9AA3B2), modifier = Modifier.align(Alignment.TopEnd).padding(12.dp, 18.dp).size(14.dp))
    }
}

/** Лучи за медалью или значком. */
@Composable
private fun Rays(color: Color, modifier: Modifier) {
    val spin = rememberInfiniteTransition(label = "rays")
    val angle by spin.animateFloat(0f, 90f, infiniteRepeatable(tween(8000, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    Canvas(modifier.rotate(angle)) {
        val c = center; val r = size.minDimension / 2
        for (i in 0 until 18) {
            val a = Math.toRadians(i * 20.0)
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(c.x, c.y)
                lineTo(c.x + (r * kotlin.math.cos(a)).toFloat(), c.y + (r * kotlin.math.sin(a)).toFloat())
                lineTo(c.x + (r * kotlin.math.cos(a + 0.14)).toFloat(), c.y + (r * kotlin.math.sin(a + 0.14)).toFloat())
                close()
            }
            drawPath(path, Brush.radialGradient(listOf(color, Color.Transparent), c, r))
        }
    }
}

