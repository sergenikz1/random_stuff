package com.voicereader.ui.rewards

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicereader.R
import com.voicereader.rewards.FocusCatalog
import com.voicereader.rewards.RewardsEngine
import com.voicereader.rewards.RewardsState

private val Track = Color(0xFF2A303B)
private val Gold = Color(0xFFF0B27A)
private val PauseOrange = Color(0xFFFF9A3D)

@Composable
fun medalName(index: Int): String =
    if (index < 0) stringResource(R.string.medal_start)
    else stringResource(FocusCatalog.TIER_NAMES[index / 5]) + " " + FocusCatalog.ROMAN[index % 5]

/**
 * Лента в режиме чтения: полученная медаль, цель (по центру, ровно на линии) и следующая.
 * Нажатие открывает «Мои награды».
 */
@Composable
fun MedalRibbon(state: RewardsState, modifier: Modifier = Modifier, dim: Boolean = false, onClick: (() -> Unit)? = null) {
    val current = state.medal
    val paused = state.pauseLeft != null
    val progress by animateFloatAsState(FocusCatalog.progressToNext(state.sessionSeconds), tween(900), label = "progress")

    Box(
        modifier
            .fillMaxWidth()
            .height(150.dp)
            .then(if (onClick != null) Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick) else Modifier)
    ) {
        if (paused) {
            Text(
                stringResource(R.string.ribbon_pause, state.pauseLeft ?: 0),
                color = Color(0xFFFFB066),
                fontSize = 12.5.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .background(Color(0xFF22180F), RoundedCornerShape(14.dp))
                    .border(1.dp, Color(0xFF5A3B1C), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 5.dp)
            )
        }
        // Смена цели — лента плавно сдвигается на шаг.
        AnimatedContent(
            targetState = current,
            transitionSpec = {
                (slideInHorizontally(tween(500)) { it / 4 } + fadeIn(tween(500))) togetherWith
                    (slideOutHorizontally(tween(450)) { -it / 4 } + fadeOut(tween(450)))
            },
            label = "ribbon",
            modifier = Modifier.fillMaxSize()
        ) { medal ->
            RibbonRow(medal, if (medal == current) progress else 0f, paused, state.pauseLeft ?: 0, dim)
        }
    }
}

@Composable
private fun RibbonRow(current: Int, progress: Float, paused: Boolean, pauseLeft: Int, dim: Boolean) {
    val lineY = 74.dp
    val pulse = rememberInfiniteTransition(label = "pulse")
    // Раз в несколько секунд цель «вздыхает», а к ней по линии пробегает блик.
    val scale by pulse.animateFloat(1f, 1f, infiniteRepeatable(keyframes {
        durationMillis = 4200; 1f at 0; 1.07f at 300; 1f at 650; 1f at 4200
    }), label = "scale")
    val shine by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart), label = "shine")
    val target = (current + 1).coerceAtMost(FocusCatalog.COUNT - 1)
    val next = current + 2
    val alpha = if (dim) 0.75f else 1f

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val xs = listOf(w * 0.18f, w * 0.5f, w * 0.82f)
        Canvas(Modifier.fillMaxSize()) {
            val y = lineY.toPx()
            val left = xs[0].toPx(); val mid = xs[1].toPx(); val right = xs[2].toPx()
            drawLine(Track, Offset(left, y), Offset(right, y), 4.dp.toPx(), StrokeCap.Round)
            val fillTo = left + (mid - left) * progress
            drawLine(Brush.horizontalGradient(listOf(Color(0xFF8D6A3D), if (paused) Color(0xFFA8723F) else Gold), left, fillTo),
                Offset(left, y), Offset(fillTo, y), 4.dp.toPx(), StrokeCap.Round)
            if (!paused && shine < 0.45f) {
                val x = left + (mid - left) * (shine / 0.45f) * progress.coerceAtLeast(0.15f)
                drawCircle(Brush.radialGradient(listOf(Color(0xEEFFE6B4), Color.Transparent), Offset(x, y), 18.dp.toPx()), 18.dp.toPx(), Offset(x, y))
            }
            // Кольцо прогресса вокруг цели; на паузе — оранжевый отсчёт до сгорания серии.
            val r = 46.dp.toPx()
            drawCircle(Brush.radialGradient(listOf(if (paused) Color(0x55FF8C3C) else Color(0x66F0B27A), Color.Transparent), Offset(mid, y), r * 1.3f), r * 1.3f, Offset(mid, y))
            drawCircle(Track, r, Offset(mid, y), style = Stroke(4.dp.toPx()))
            val sweep = 360f * if (paused) pauseLeft / RewardsEngine.PAUSE_GRACE_SECONDS.toFloat() else progress
            drawArc(if (paused) PauseOrange else Gold, -90f, sweep, false, Offset(mid - r, y - r),
                androidx.compose.ui.geometry.Size(r * 2, r * 2), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
        }
        RibbonMedal(current, xs[0], lineY, 52.dp, alpha * if (current < 0) 0.35f else 1f, locked = current < 0, label = "✓ " + medalName(current).takeIf { current >= 0 }.orEmpty(), labelOffset = 34.dp)
        RibbonMedal(target, xs[1], lineY, 78.dp, alpha, locked = false, label = medalName(target), labelOffset = 60.dp, scale = scale, highlight = true)
        if (next < FocusCatalog.COUNT) RibbonMedal(next, xs[2], lineY, 46.dp, alpha, locked = true, label = medalName(next), labelOffset = 34.dp)
    }
}

@Composable
private fun RibbonMedal(
    index: Int, x: Dp, centerY: Dp, size: Dp, alpha: Float, locked: Boolean,
    label: String, labelOffset: Dp, scale: Float = 1f, highlight: Boolean = false
) {
    Box(Modifier.offset(x - size / 2, centerY - size / 2).size(size).scale(scale)) {
        MedalArt(index.coerceAtLeast(0), Modifier.fillMaxSize(), locked = locked)
    }
    if (label.isNotBlank() && label != "✓ ") {
        Text(
            label,
            fontSize = if (highlight) 12.sp else 11.sp,
            fontWeight = if (highlight) FontWeight.Medium else FontWeight.Normal,
            color = if (highlight) Color(0xFFF6D2A8) else Color(0xFFB4BCCB).copy(alpha = alpha),
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.offset(x - 70.dp, centerY + labelOffset - 8.dp).width(140.dp)
        )
    }
}
