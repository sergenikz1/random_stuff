package com.voicereader.ui.rewards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicereader.R
import com.voicereader.rewards.DailyCatalog
import com.voicereader.rewards.GrowKind
import com.voicereader.rewards.RewardEvent
import com.voicereader.rewards.Rewards
import com.voicereader.rewards.RewardsState
import com.voicereader.rewards.Welcome
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

private fun k01(v: Float) = v.coerceIn(0f, 1f)
private fun sgm(t: Float, a: Float, b: Float) = k01((t - a) / (b - a))
private fun eo(k: Float) = 1 - (1 - k).pow(3)
private fun eio(k: Float) = if (k < .5f) 4 * k * k * k else 1 - (-2 * k + 2).pow(3) / 2
private fun eb(k: Float): Float { val c = 1.7f; return 1 + (c + 1) * (k - 1).pow(3) + c * (k - 1).pow(2) }

private val GREEN = Color(0xFF7BD88F)
private val WARM = Color(0xFFE0B07A)

/** Растения роняют листья, остальное (костёр, кристалл, созвездие…) — искры. */
private fun leafy(kind: GrowKind) = kind == GrowKind.TREE || kind == GrowKind.GARDEN || kind == GrowKind.CACTUS || kind == GrowKind.MUSHROOM

/**
 * Экран дня — при первом заходе за сутки. Рассвет и рост стадии (или пасмурно и дождь, если день пропущен),
 * счётчик дней, неделя, крупное задание «почитайте 1 минуту» и кнопка.
 */
@Composable
fun DailyWelcomeScreen(kind: Welcome, state: RewardsState, onContinue: () -> Unit) {
    val clock = rememberClock()
    val tau = clock.value
    val wilt = kind == Welcome.WILTED
    val saved = kind == Welcome.SAVED
    val stages = stringArrayResource(state.growKind.stagesRes)
    // Стадия сменилась, если вчерашний день как раз довёл до нового порога.
    val stageChanged = !wilt && state.stage > 0 && state.growthDays == DailyCatalog.STAGE_DAYS[state.stage]
    val today = state.dayLog[Rewards.today().toString()] ?: 0

    val dawn = if (wilt) 1f else eio(sgm(tau, 0f, 1.4f))
    val top = if (wilt) Color(0xFF1A1916) else lerp(Color(0xFF070A14), Color(0xFF10261A), dawn)
    val mid = if (wilt) Color(0xFF2A251E) else lerp(Color(0xFF0B1020), Color(0xFF1C3A26), dawn)
    val bot = if (wilt) Color(0xFF100E0C) else lerp(Color(0xFF05070C), Color(0xFF0D1210), dawn)

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, mid, bot)))) {
        // Небо: звёзды гаснут, встаёт солнце; или дождь.
        Canvas(Modifier.fillMaxSize()) {
            val d = density
            if (!wilt) {
                val sy = size.height * (.66f - .27f * eo(sgm(tau, .2f, 2f)))
                val c = Offset(size.width / 2, sy)
                drawCircle(Brush.radialGradient(listOf(Color(0x61FFE296), Color(0x1F7BD88F), Color.Transparent), c, 220 * d), 220 * d, c, alpha = .25f + .75f * dawn)
                for (j in 0 until 30) drawCircle(Color.White.copy(alpha = (1 - dawn) * (.4f + .6f * kotlin.math.abs(sin(tau * 2 + j)))), (.6f + rnd(j * 5)) * d,
                    Offset(rnd(j) * size.width, rnd(j * 3) * size.height * .4f))
            } else for (j in 0 until 70) {
                val sp = (380 + rnd(j) * 200) * d
                val x = rnd(j * 3) * (size.width + 40 * d) - 20 * d
                val y = (rnd(j * 7) * (size.height + 40 * d) + tau * sp) % (size.height + 40 * d) - 40 * d
                drawLine(Color(0xFF9FB3C8).copy(alpha = .18f + .3f * rnd(j * 2)), Offset(x, y), Offset(x - 4 * d, y + 16 * d), 1.2f * d)
            }
        }

        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(36.dp))
            // Счётчик дней: число прокручивается со вчерашнего на сегодняшнее.
            val kA = sgm(tau, .4f, .9f)
            val kicker = when (kind) {
                Welcome.WILTED -> stringResource(R.string.welcome_kicker_missed).uppercase()
                Welcome.SAVED -> stringResource(R.string.welcome_kicker_saved).uppercase()
                Welcome.GREW -> null
            }
            Box(Modifier.height(20.dp).graphicsLayer { alpha = kA }, contentAlignment = Alignment.Center) {
                if (kicker != null) Text(kicker, color = WARM.takeIf { wilt } ?: Color(0xFF9FD0FF), fontSize = 13.sp, letterSpacing = 3.sp, fontWeight = FontWeight.SemiBold)
                else {
                    val marker = 987654
                    val raw = stringResource(R.string.welcome_kicker_day, marker).uppercase()
                    val parts = raw.split(marker.toString())
                    val roll = eio(sgm(tau, 1.85f, 2.3f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(parts.first(), color = Color(0xFF9FE0AE), fontSize = 13.sp, letterSpacing = 3.sp, fontWeight = FontWeight.SemiBold)
                        Box(Modifier.height(18.dp).clip(RoundedCornerShape(2.dp))) {
                            Text("${state.growthDays - 1}", color = Color(0xFF9FE0AE), fontSize = 13.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.graphicsLayer { translationY = -roll * 18 * density; alpha = 1 - roll })
                            Text("${state.growthDays}", color = Color(0xFF9FE0AE), fontSize = 13.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.graphicsLayer { translationY = (1 - roll) * 18 * density; alpha = roll })
                        }
                        Text(parts.getOrElse(1) { "" }, color = Color(0xFF9FE0AE), fontSize = 13.sp, letterSpacing = 3.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            val tA = if (wilt) sgm(tau, .5f, 1f) else sgm(tau, 2.2f, 2.7f)
            Text(
                when (kind) {
                    Welcome.GREW -> stringResource(state.growKind.grew)
                    Welcome.SAVED -> stringResource(R.string.welcome_saved)
                    Welcome.WILTED -> stringResource(state.growKind.wilted)
                },
                fontSize = 30.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, lineHeight = 36.sp,
                modifier = Modifier.padding(top = 6.dp).graphicsLayer { alpha = tA; translationY = (1 - eo(tA)) * 14 * density }
            )
            Box(Modifier.height(34.dp), contentAlignment = Alignment.Center) {
                if (stageChanged) {
                    val c = sgm(tau, 2.5f, 2.9f)
                    Text(
                        stringResource(R.string.welcome_new_stage, stages[state.stage]), fontSize = 12.5.sp, color = Color(0xFFCFEED7),
                        modifier = Modifier.graphicsLayer { alpha = c; scaleX = eb(c); scaleY = eb(c) }
                            .background(Color(0xBF285034), RoundedCornerShape(14.dp)).border(1.dp, Color(0xFF4F8A5E), RoundedCornerShape(14.dp))
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                } else if (wilt) Text(stringResource(R.string.welcome_wilted_text), fontSize = 14.sp, color = Color(0xFFC9C0B0))
            }

            // Растение: старая стадия вспыхивает и сменяется новой; при увядании — качается и теряет листья.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val isle = sgm(tau, .5f, 1.1f)
                if (stageChanged) {
                    val oldOut = sgm(tau, 1.5f, 1.85f); val grow = sgm(tau, 1.75f, 2.6f)
                    if (oldOut < 1f) GrowthArt(state.growKind, state.stage - 1, Modifier.size(300.dp).graphicsLayer {
                        alpha = k01(isle * 1.5f); val s = (.9f + .1f * eo(isle)) * (1 + .08f * sin(sgm(tau, 1.2f, 1.6f) * PI.toFloat())) * (1 - eio(oldOut))
                        scaleX = s.coerceAtLeast(.001f); scaleY = scaleX; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(.5f, .92f)
                    })
                    if (grow > 0f) GrowthArt(state.growKind, state.stage, Modifier.size(300.dp).graphicsLayer {
                        val s = .4f + .6f * eb(grow); scaleX = s; scaleY = s
                        rotationZ = sin(tau * 1.4f) * 1.2f * sgm(tau, 2.6f, 3.2f)
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(.5f, .92f)
                    })
                } else {
                    GrowthArt(state.growKind, state.stage, Modifier.size(300.dp).graphicsLayer {
                        alpha = k01(isle * 1.5f)
                        val pop = if (wilt) 1f else 1 + .06f * sin(sgm(tau, 1.6f, 2.3f) * PI.toFloat())
                        scaleX = (.9f + .1f * eo(isle)) * pop; scaleY = scaleX
                        rotationZ = if (wilt) -3 + sin(tau * 1.1f) * 1.5f else 0f
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(.5f, .92f)
                    }, wilted = wilt)
                }
                Canvas(Modifier.fillMaxSize()) {
                    val d = density
                    val c = Offset(size.width / 2, size.height * .45f)
                    if (!wilt) {
                        // Листья или искры при росте.
                        val lb = sgm(tau, 1.8f, 3.4f)
                        if (lb > 0f && lb < 1f) for (j in 0 until 22) {
                            val a = -PI.toFloat() / 2 + (rnd(j) - .5f) * 2.6f
                            val dist = (40 + 140 * eo(lb) * (.6f + .4f * rnd(j * 2))) * d
                            val o = Offset(c.x + cos(a) * dist, c.y + sin(a) * dist + 90 * d * lb * lb)
                            if (leafy(state.growKind) && j % 3 != 0) withTransform({ translate(o.x, o.y); rotate(lb * 300 + j * 40, Offset.Zero) }) {
                                drawOval((if (j % 2 == 0) Color(0xFF4CBE6C) else GREEN).copy(alpha = 1 - lb), Offset(-5 * d, -2.6f * d), Size(10 * d, 5.2f * d))
                            } else drawCircle(Color(0xFFFFF6C8).copy(alpha = 1 - lb), 2.4f * d, o)
                        }
                        // Капля воды спасла день — она падает на растение.
                        if (saved) {
                            val k = sgm(tau, .9f, 1.5f)
                            if (k < 1f) drawCircle(Color(0xFF6FB7FF), 6 * d, Offset(c.x, c.y - 160 * d + 160 * d * eio(k)), alpha = if (k > 0f) 1f else 0f)
                            val sp = sgm(tau, 1.5f, 2.1f)
                            if (sp > 0f && sp < 1f) for (j in 0 until 8) {
                                val a = PI.toFloat() + j / 7f * PI.toFloat()
                                drawCircle(Color(0xFF9FD0FF).copy(alpha = 1 - sp), 2 * d, Offset(c.x + cos(a) * 30 * d * sp, c.y + sin(a) * 26 * d * sp))
                            }
                        }
                    } else if (leafy(state.growKind)) for (j in 0 until 7) {
                        val ph = ((tau + j * .55f) % 3.4f) / 3.4f
                        val x = c.x - 30 * d + rnd(j) * 60 * d + sin(ph * 6 + j) * 18 * d
                        val y = c.y + ph * 190 * d
                        withTransform({ translate(x, y); rotate(ph * 400 + j * 50, Offset.Zero) }) {
                            drawOval(Color(0xFFA08A4A).copy(alpha = sin(ph * PI.toFloat()) * .9f), Offset(-5 * d, -2.6f * d), Size(10 * d, 5.2f * d))
                        }
                    }
                }
            }

            WeekDots(state, wilt, tau, if (wilt) .8f else 2.8f)
            val iA = if (wilt) sgm(tau, 1.3f, 1.8f) else sgm(tau, 3.4f, 3.9f)
            Text(
                if (wilt) stringResource(R.string.welcome_no_drops)
                else DailyCatalog.daysToNextStage(state.growthDays)?.let {
                    stringResource(R.string.welcome_next_stage, it, stages[(state.stage + 1).coerceAtMost(stages.size - 1)])
                } ?: stringResource(R.string.welcome_max_stage),
                fontSize = 12.5.sp, color = Color(0xFFB9C6BD), textAlign = TextAlign.Center, lineHeight = 17.sp,
                modifier = Modifier.padding(top = 10.dp).graphicsLayer { alpha = iA }
            )
            if (!wilt && state.drops > 0) Text(
                stringResource(R.string.welcome_drops, state.drops), fontSize = 12.sp, color = Color(0xFF9FC7E8),
                modifier = Modifier.padding(top = 2.dp).graphicsLayer { alpha = iA }
            )
            GoalCard(today, wilt, if (wilt) 1.6f else 3.7f, tau)
            val bA = if (wilt) sgm(tau, 2f, 2.5f) else sgm(tau, 4.2f, 4.7f)
            val glow = .5f + .5f * sin(tau * 3)
            val accent = if (wilt) WARM else GREEN
            Box(
                Modifier.padding(top = 14.dp, bottom = 22.dp).fillMaxWidth().height(54.dp)
                    .graphicsLayer { alpha = bA }
                    .shadow((6 + 10 * glow * bA).dp, RoundedCornerShape(27.dp), ambientColor = accent, spotColor = accent)
                    .clip(RoundedCornerShape(27.dp)).background(accent)
                    .clickableNoRipple(onContinue),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(if (wilt) R.string.welcome_water else R.string.welcome_read), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = if (wilt) Color(0xFF2A1A0A) else Color(0xFF0D1A12))
            }
        }
    }
}

/** Задание дня — крупно и заметно: минута чтения засчитывает день. */
@Composable
private fun GoalCard(todaySeconds: Int, wilt: Boolean, start: Float, tau: Float) {
    val a = sgm(tau, start, start + .6f); val k = eb(a)
    val goal = DailyCatalog.DAY_GOAL_SECONDS
    val done = todaySeconds.coerceAtMost(goal)
    val acc = if (wilt) Color(0xFFFFB066) else GREEN
    Column(
        Modifier.padding(top = 12.dp).fillMaxWidth()
            .graphicsLayer { alpha = k01(a * 2); translationY = (1 - k) * 30 * density }
            .background(if (wilt) Color(0xEB281C12) else Color(0xEB142219), RoundedCornerShape(18.dp))
            .border(1.5.dp, if (wilt) Color(0xFFA0713C) else Color(0xFF3F8F55), RoundedCornerShape(18.dp))
            .padding(14.dp, 11.dp, 14.dp, 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.daily_goal_title), fontSize = 10.5.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, color = acc, modifier = Modifier.weight(1f))
            Text("%d:%02d / 1:00".format(done / 60, done % 60), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            stringResource(if (done >= goal) R.string.daily_goal_done else if (wilt) R.string.daily_goal_revive else R.string.daily_goal),
            fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp, modifier = Modifier.padding(top = 5.dp, bottom = 8.dp)
        )
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (wilt) Color(0xFF3A2A1C) else Color(0xFF24352A))) {
            Box(Modifier.fillMaxWidth(done / goal.toFloat()).fillMaxHeight().background(acc, RoundedCornerShape(3.dp)))
        }
    }
}

/** Последние 7 дней кружками: читал, спасён каплей, пропущен. Появляются по одному. */
@Composable
private fun WeekDots(state: RewardsState, wilt: Boolean, tau: Float, start: Float) {
    val today = Rewards.today()
    val names = stringArrayResource(R.array.weekdays_short)
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        for (k in 6 downTo 0) {
            val i = 6 - k
            val day = today.minusDays(k.toLong())
            val key = day.toString()
            val read = (state.dayLog[key] ?: 0) >= DailyCatalog.DAY_GOAL_SECONDS
            val saved = key in state.savedDays
            val isToday = k == 0
            val a = sgm(tau, start + i * .12f, start + i * .12f + .3f)
            val pop = eb(a) * if (isToday) 1 + .08f * sin(tau * 4) else 1f
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.graphicsLayer { alpha = k01(a * 2) }) {
                Box(
                    Modifier
                        .size(30.dp)
                        .graphicsLayer { scaleX = pop; scaleY = pop }
                        .clip(CircleShape)
                        .background(
                            when {
                                read -> Color(0xFF3F8F55); saved -> Color(0xFF4A6FA5)
                                isToday -> Color(0xFF1E2A22); wilt -> Color(0xFF5A2A20); else -> Color(0xFF1E2A22)
                            }
                        )
                        .then(if (isToday) Modifier.border(1.5.dp, if (wilt) WARM else GREEN, CircleShape) else Modifier),
                    contentAlignment = Alignment.Center
                ) { Text(if (read) "✓" else if (saved) "💧" else if (!isToday && wilt) "✕" else "", fontSize = 13.sp, color = Color(0xFFEAF7EE)) }
                Text(names[day.dayOfWeek.value - 1], fontSize = 10.sp, color = Color(0xFFB4BCCB))
            }
        }
    }
}

/**
 * Плашка над лентой медалей, пока день не засчитан: кольцо минуты и «День: 0:35 из 1:00».
 * Когда минута набрана, вспыхивает «День засчитан ✓» и уходит.
 */
@Composable
fun DayChip(state: RewardsState, modifier: Modifier = Modifier) {
    val todaySeconds = state.dayLog[Rewards.today().toString()] ?: 0
    var doneShown by remember { mutableStateOf(false) }
    val doneAnim = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        Rewards.events.collect { e ->
            if (e is RewardEvent.DayCounted) {
                doneShown = true
                doneAnim.snapTo(0f); doneAnim.animateTo(1f, androidx.compose.animation.core.tween(1100))
                delay(1800); doneShown = false
            }
        }
    }
    val visible = state.dailyEnabled && (todaySeconds < DailyCatalog.DAY_GOAL_SECONDS || doneShown)
    AnimatedVisibility(visible, modifier, enter = fadeIn() + scaleIn(initialScale = .9f), exit = fadeOut() + scaleOut(targetScale = .9f)) {
        val fin = if (doneShown) k01(doneAnim.value * 2) else 0f
        val green = GREEN
        Row(
            Modifier
                .graphicsLayer { val p = if (fin in 0.01f..0.99f) 1 + .12f * sin(fin * PI.toFloat()) else 1f; scaleX = p; scaleY = p }
                .shadow(10.dp, RoundedCornerShape(22.dp))
                .background(Color(0xF5141E18), RoundedCornerShape(22.dp))
                .border(1.5.dp, lerp(Color(0xFF2F4A37), green, fin), RoundedCornerShape(22.dp))
                .padding(start = 4.dp, end = 14.dp)
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                GrowthArt(state.growKind, maxOf(state.stage, 3), Modifier.size(22.dp))
                Canvas(Modifier.fillMaxSize()) {
                    val r = 13.dp.toPx(); val c = center
                    drawCircle(Color(0xFF24352A), r, c, style = Stroke(3.dp.toPx()))
                    drawArc(green, -90f, 360f * (todaySeconds.coerceAtMost(DailyCatalog.DAY_GOAL_SECONDS) / DailyCatalog.DAY_GOAL_SECONDS.toFloat()), false,
                        Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                    val b = doneAnim.value
                    if (doneShown && b > 0f && b < 1f) for (j in 0 until 14) {
                        val a = j / 14f * 2 * PI.toFloat(); val dist = (18 + 46 * eo(b)) * density
                        withTransform({ translate(c.x + cos(a) * dist, c.y + sin(a) * dist); rotate(j * 40 + b * 200, Offset.Zero) }) {
                            drawOval((if (j % 2 == 0) Color(0xFF4CBE6C) else Color(0xFFBFF5CF)).copy(alpha = 1 - b), Offset(-4 * density, -2 * density), Size(8 * density, 4 * density))
                        }
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            Column {
                if (fin < .5f) {
                    Text(stringResource(R.string.daychip_progress, "%d:%02d".format(todaySeconds / 60, todaySeconds % 60)), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.daychip_hint), fontSize = 10.5.sp, color = Color(0xFF9FB3A5))
                } else {
                    Text(stringResource(R.string.daychip_done), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = green)
                    Text(stringResource(R.string.daychip_streak, state.growthDays), fontSize = 10.5.sp, color = Color(0xFF9FB3A5))
                }
            }
        }
    }
}
