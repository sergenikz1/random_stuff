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

@Composable
fun rankName(rank: Int): String = stringArrayResource(R.array.ranks)[rank.coerceIn(0, RanksCatalog.COUNT - 1)]

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

/** Праздники во время чтения: новое звание — на весь экран, рекорд и день — плашкой сверху. */
@Composable
fun RewardCelebrations(onApply: (Reward) -> Unit) {
    val state by Rewards.state.collectAsState()
    var rankUp by remember { mutableStateOf<Int?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    val recordText = stringResource(R.string.toast_record)
    val dayText = stringResource(R.string.toast_day)
    LaunchedEffect(Unit) {
        Rewards.events.collect { e ->
            if (!Rewards.state.value.celebrations) return@collect
            when (e) {
                is RewardEvent.RankUp -> rankUp = e.rank
                is RewardEvent.Record -> toast = recordText.format(e.seconds / 60)
                is RewardEvent.DayCounted -> toast = dayText.format(e.growthDays)
                is RewardEvent.MedalUp -> Unit
            }
        }
    }
    LaunchedEffect(toast) { if (toast != null) { delay(3500); toast = null } }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = toast != null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp)
        ) {
            Text(
                toast.orEmpty(),
                color = Color(0xFFFFE0B0), fontSize = 14.sp,
                modifier = Modifier
                    .background(Color(0xF02A2014), RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0xFF8A6A3D), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
        rankUp?.let { rank ->
            val reward = RanksCatalog.rewardFor(rank)
            val pop = remember(rank) { Animatable(0.2f) }
            LaunchedEffect(rank) { pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 220f)) }
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xF205060A))
                    .clickable(remember { MutableInteractionSource() }, indication = null) { }
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                    Rays(Color(0x55FFD86B), Modifier.size(420.dp))
                    RankBadge(rank, Modifier.size(130.dp, 156.dp).scale(pop.value))
                }
                Text(stringResource(R.string.new_rank).uppercase(), color = Color(0xFFFFD86B), fontSize = 13.sp, letterSpacing = 3.sp)
                Spacer(Modifier.height(6.dp))
                Text(rankName(rank), fontSize = 26.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                if (reward != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(stringResource(R.string.new_reward_unlocked), color = Color(0xFFB4BCCB), fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    RewardCard(reward, true)
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { rankUp = null }) { Text(stringResource(R.string.later)) }
                    if (reward != null) Button(onClick = { onApply(reward); rankUp = null }) { Text(stringResource(R.string.apply)) }
                }
            }
        }
    }
}

/** Экран дня: при первом заходе за сутки показывает, как поживает росток. */
@Composable
fun DailyWelcomeScreen(kind: Welcome, state: RewardsState, onContinue: () -> Unit) {
    val good = kind != Welcome.WILTED
    val stages = stringArrayResource(state.growKind.stagesRes)
    val grow = remember { Animatable(if (good) 0.85f else 1f) }
    LaunchedEffect(Unit) { if (good) grow.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 120f)) }
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    if (good) listOf(Color(0xFF24402A), Color(0xFF14201A), Color(0xFF0D1210))
                    else listOf(Color(0xFF3A2F22), Color(0xFF1E1914), Color(0xFF100E0C))
                )
            )
            .systemBarsPadding()
            .padding(horizontal = 30.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when (kind) {
                    Welcome.GREW -> stringResource(R.string.welcome_kicker_day, state.growthDays)
                    Welcome.SAVED -> stringResource(R.string.welcome_kicker_saved)
                    Welcome.WILTED -> stringResource(R.string.welcome_kicker_missed)
                }.uppercase(),
                color = if (good) Color(0xFF9FE0AE) else Color(0xFFE0B07A), fontSize = 13.sp, letterSpacing = 3.sp, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
            Text(
                when (kind) {
                    Welcome.GREW -> stringResource(state.growKind.grew)
                    Welcome.SAVED -> stringResource(R.string.welcome_saved)
                    Welcome.WILTED -> stringResource(state.growKind.wilted)
                },
                fontSize = 30.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, lineHeight = 36.sp
            )
            GrowthArt(
                state.growKind, state.stage,
                Modifier.padding(vertical = 8.dp).size(300.dp).scale(grow.value),
                wilted = !good
            )
            Text(
                when (kind) {
                    Welcome.WILTED -> stringResource(R.string.welcome_wilted_text)
                    else -> DailyCatalog.daysToNextStage(state.growthDays)?.let {
                        stringResource(R.string.welcome_next_stage, it, stages[(state.stage + 1).coerceAtMost(stages.size - 1)])
                    } ?: stringResource(R.string.welcome_max_stage)
                },
                color = Color(0xFFC9D1C9), fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 21.sp
            )
            if (!good) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.welcome_wilted_warning), color = Color(0xFFE0B07A), fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(18.dp))
            WeekDots(state, good)
        }
        Button(
            onClick = onContinue,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (good) Color(0xFF7BD88F) else Color(0xFFE0B07A),
                contentColor = if (good) Color(0xFF0D1A12) else Color(0xFF2A1A0A)
            ),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp).fillMaxWidth().height(54.dp)
        ) { Text(stringResource(if (good) R.string.welcome_read else R.string.welcome_water), fontSize = 16.sp) }
    }
}

/** Последние 7 дней кружками: читал, спасён каплей, пропущен. */
@Composable
private fun WeekDots(state: RewardsState, good: Boolean) {
    val today = Rewards.today()
    val names = stringArrayResource(R.array.weekdays_short)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (k in 6 downTo 0) {
            val day = today.minusDays(k.toLong())
            val key = day.toString()
            val read = (state.dayLog[key] ?: 0) >= DailyCatalog.DAY_GOAL_SECONDS
            val saved = key in state.savedDays
            val isToday = k == 0
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                read -> Color(0xFF3F8F55); saved -> Color(0xFF4A6FA5)
                                isToday -> Color(0xFF1E2A22); else -> if (good) Color(0xFF1E2A22) else Color(0xFF5A2A20)
                            }
                        )
                        .then(if (isToday) Modifier.border(1.5.dp, Color(0xFF7BD88F), CircleShape) else Modifier),
                    contentAlignment = Alignment.Center
                ) { Text(if (read) "✓" else if (saved) "💧" else if (!isToday && !good) "✕" else "", fontSize = 13.sp) }
                Text(names[day.dayOfWeek.value - 1], fontSize = 10.sp, color = Color(0xFFB4BCCB))
            }
        }
    }
}
