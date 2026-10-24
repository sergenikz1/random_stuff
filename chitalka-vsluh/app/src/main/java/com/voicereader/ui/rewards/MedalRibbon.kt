package com.voicereader.ui.rewards

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicereader.R
import com.voicereader.rewards.FocusCatalog
import com.voicereader.rewards.RewardsEngine
import com.voicereader.rewards.RewardsState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Цвет ступени медали: кольцо, дорожка и подпись окрашиваются в металл цели. */
private val ACCENT = longArrayOf(
    0xFFD39A5C, 0xFFC3C9D0, 0xFFA9B6C3, 0xFFF0B27A, 0xFFE9EEF3, 0xFFFFD86B,
    0xFFBFEAF5, 0xFF7FA8FF, 0xFF6EE7A6, 0xFFFF7A93, 0xFFC8F3FF
)
private val ORANGE = Color(0xFFFF9A3D)
private val TRACK = Color(0xFF2A303B)

private fun accentOf(medal: Int) = Color(ACCENT[(medal.coerceIn(0, FocusCatalog.COUNT - 1)) / FocusCatalog.RANKS_PER_TIER])

@Composable
fun medalName(index: Int): String =
    if (index < 0) stringResource(R.string.medal_start)
    else stringResource(FocusCatalog.TIER_NAMES[index / 5]) + " " + FocusCatalog.ROMAN[index % 5]

private fun clamp01(v: Float) = v.coerceIn(0f, 1f)
private fun seg(t: Float, a: Float, b: Float) = clamp01((t - a) / (b - a))
private fun eOut(k: Float) = 1 - (1 - k).pow(3)
private fun eInOut(k: Float) = if (k < .5f) 4 * k * k * k else 1 - (-2 * k + 2).pow(3) / 2
private fun eBack(k: Float): Float { val c = 1.7f; return 1 + (c + 1) * (k - 1).pow(3) + c * (k - 1).pow(2) }
private fun mix(a: Float, b: Float, k: Float) = a + (b - a) * k

/** Длительность вручения: обычная медаль и первая медаль новой ступени. */
private const val EARN_S = 1.35f
private const val TIER_UP_S = 2.2f

/**
 * Лента медалей в режиме чтения — карточка внизу экрана. Слева полученная медаль, по центру цель в кольце
 * прогресса, справа следующая. Медаль вручается со вспышкой и переворотом, после чего лента сдвигается;
 * на паузе всё сереет, а кольцо превращается в минутный отсчёт. Нажатие открывает «Мои награды».
 */
@Composable
fun MedalRibbon(
    state: RewardsState,
    modifier: Modifier = Modifier,
    dim: Boolean = false,
    ringFx: String? = null,
    onClick: (() -> Unit)? = null
) {
    val clock = rememberClock()
    var shown by remember { mutableIntStateOf(state.medal) }
    var earnIdx by remember { mutableStateOf<Int?>(null) }
    val earnT = remember { Animatable(0f) }
    val shift = remember { Animatable(1f) }
    val ringA = remember { Animatable(1f) }
    val drop = remember { Animatable(0f) }
    val enter = remember { Animatable(0f) }

    LaunchedEffect(Unit) { enter.animateTo(1f, tween(1300, easing = LinearEasing)) }
    LaunchedEffect(state.medal) {
        val target = state.medal
        earnIdx = null; shift.snapTo(1f); ringA.snapTo(1f); drop.snapTo(0f)
        if (target > shown) {
            // Вручаем по одной, даже если за раз набралось несколько медалей.
            while (shown < target) {
                val idx = shown + 1
                val dur = if (idx % FocusCatalog.RANKS_PER_TIER == 0) TIER_UP_S else EARN_S
                earnIdx = idx
                earnT.snapTo(0f)
                earnT.animateTo(dur, tween((dur * 1000).toInt(), easing = LinearEasing))
                earnIdx = null
                shown = idx
                ringA.snapTo(0f); shift.snapTo(0f)
                shift.animateTo(1f, tween(750, easing = LinearEasing))
                ringA.animateTo(1f, tween(400))
            }
        } else if (target < shown) {
            // Серия сгорела: медали падают, лента начинается заново.
            drop.animateTo(1f, tween(900, easing = LinearEasing))
            shown = target
            drop.snapTo(0f); enter.snapTo(0f)
            enter.animateTo(1f, tween(1300, easing = LinearEasing))
        }
    }

    val paused = state.pauseLeft != null
    val pause by animateFloatAsState(if (paused) 1f else 0f, tween(600), label = "pause")
    val progress by animateFloatAsState(FocusCatalog.progressToNext(state.sessionSeconds), tween(900), label = "progress")
    val pauseLeft by animateFloatAsState((state.pauseLeft ?: RewardsEngine.PAUSE_GRACE_SECONDS).toFloat(), tween(1000, easing = LinearEasing), label = "left")

    val tierNames = FocusCatalog.TIER_NAMES.map { stringResource(it) }
    fun name(i: Int) = tierNames[i / 5] + " " + FocusCatalog.ROMAN[i % 5]
    val earnedFmt = stringResource(R.string.ribbon_earned)
    val tierUpFmt = stringResource(R.string.ribbon_tier_up)
    val pauseFmt = stringResource(R.string.ribbon_pause)
    val brokenText = stringResource(R.string.ribbon_broken)
    val measurer = rememberTextMeasurer(cacheSize = 16)

    val cardShape = RoundedCornerShape(28.dp)
    val dockAlpha = if (dim) 0.8f else 1f
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, bottom = 16.dp)
            .height(112.dp)
            .alpha(dockAlpha)
            .then(if (onClick != null) Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick) else Modifier)
    ) {
        // Карточка: тень, градиент и рамка, которая на паузе становится оранжевой.
        val borderColor = lerp(Color(0xFF2A303B), Color(0xFF5A3B1C), pause)
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { val e = eOut(enter.value); alpha = clamp01(e * 1.3f); translationY = (1 - e) * 40 * density }
                .shadow(14.dp, cardShape)
                .background(Brush.verticalGradient(listOf(Color(0xF71F232D), Color(0xFA16191F))), cardShape)
                .border(1.dp, borderColor, cardShape)
        )
        Canvas(Modifier.matchParentSize().graphicsLayer { clip = false }) {
            drawRibbon(
                RibbonFrame(
                    t = clock.value, cur = shown, u = shift.value, progress = progress, ringA = ringA.value,
                    pause = pause, left = pauseLeft, earnIdx = earnIdx, earnT = earnT.value,
                    drop = drop.value, enter = enter.value, ringFx = ringFx
                ),
                labels = { f -> ribbonLabels(f, ::name, earnedFmt, tierUpFmt, pauseFmt, brokenText) },
                drawLabel = { text, color, alpha, scale, bold, center ->
                    if (alpha > 0.01f) {
                        val layout = measurer.measure(text, TextStyle(fontSize = 12.5.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium, color = color, letterSpacing = 0.2.sp))
                        withTransform({ scale(scale, scale, center) }) {
                            drawText(layout, alpha = alpha, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
                        }
                    }
                }
            )
        }
    }
}

/** Всё, что нужно, чтобы нарисовать один кадр ленты. */
private class RibbonFrame(
    val t: Float, val cur: Int, val u: Float, val progress: Float, val ringA: Float,
    val pause: Float, val left: Float, val earnIdx: Int?, val earnT: Float,
    val drop: Float, val enter: Float, val ringFx: String?
)

private class Label(val text: String, val color: Color, val alpha: Float, val scale: Float = 1f, val bold: Boolean = false)

private fun ribbonLabels(
    f: RibbonFrame, name: (Int) -> String, earnedFmt: String, tierUpFmt: String, pauseFmt: String, broken: String
): List<Label> {
    val target = min(f.cur + 1, FocusCatalog.COUNT - 1)
    if (f.drop > 0f) return listOf(Label(broken, Color(0xFF8B95A7), 1f))
    val earn = f.earnIdx
    if (earn != null) {
        val out = seg(f.earnT, .25f, .42f); val k = seg(f.earnT, .42f, .7f)
        val tierUp = earn % FocusCatalog.RANKS_PER_TIER == 0
        val txt = String.format(if (tierUp) tierUpFmt else earnedFmt, name(earn))
        return listOf(
            Label(name(target), accentOf(target), 1 - out),
            Label(txt, if (tierUp) Color(0xFFFFF3C4) else accentOf(earn), k, 1 + .12f * sin(k * PI.toFloat()), bold = true)
        )
    }
    if (f.u < 1f && f.cur >= 0) {
        val tierUp = f.cur % FocusCatalog.RANKS_PER_TIER == 0
        val txt = String.format(if (tierUp) tierUpFmt else earnedFmt, name(f.cur))
        return listOf(
            Label(txt, if (tierUp) Color(0xFFFFF3C4) else accentOf(f.cur), 1 - seg(f.u, 0f, .4f), bold = true),
            Label(name(target), accentOf(target), seg(f.u, .55f, 1f))
        )
    }
    val sec = kotlin.math.ceil(f.left).toInt().coerceAtLeast(0)
    val pulse = if (f.left < 10) 1 + .06f * max(0f, cos(f.t * PI.toFloat() * 4)) else 1f
    return listOf(
        Label(name(target), lerp(accentOf(target), Color(0xFFE6EAF2), .15f), 1 - f.pause),
        Label(String.format(pauseFmt, "0:%02d".format(sec)), if (f.left < 10) Color(0xFFFF7A4D) else Color(0xFFFFB066), f.pause, pulse)
    )
}

private fun DrawScope.drawRibbon(
    f: RibbonFrame,
    labels: (RibbonFrame) -> List<Label>,
    drawLabel: DrawScope.(String, Color, Float, Float, Boolean, Offset) -> Unit
) {
    val d = density
    val w = size.width
    val cy = 48 * d
    val ringR = 37 * d
    // Слоты: −1 — уходит влево, 0 — полученная, 1 — цель, 2 — следующая, 3 — появляется справа.
    val slotX = floatArrayOf(.06f, .182f, .5f, .818f, .945f)
    val slotSize = floatArrayOf(26f, 42f, 60f, 34f, 26f)
    val slotOp = floatArrayOf(0f, 1f, 1f, .9f, 0f)
    fun slot(p: Float): Triple<Float, Float, Float> {
        val q = p.coerceIn(-1f, 3f) + 1
        val a = q.toInt().coerceAtMost(3); val b = min(4, a + 1); val k = q - a
        return Triple(mix(slotX[a], slotX[b], k) * w, mix(slotSize[a], slotSize[b], k) * d, mix(slotOp[a], slotOp[b], k))
    }
    val cx = w * .5f
    val target = min(f.cur + 1, FocusCatalog.COUNT - 1)
    val earn = f.earnIdx
    var acc = if (f.u < 1f) lerp(accentOf(f.cur), accentOf(target), eInOut(f.u)) else accentOf(target)
    if (earn != null) acc = accentOf(earn)
    val ringCol = lerp(acc, ORANGE, f.pause)
    val ent = eOut(f.enter)
    val drop = f.drop
    val t = f.t
    val near = clamp01((f.progress - .88f) / .12f) * (1 - f.pause) * (if (earn != null) 0f else 1f)
    val beatK = (t % 1.1f) / 1.1f
    val beat = exp(-((beatK - .08f) / .05f).pow(2)) + .6f * exp(-((beatK - .28f) / .05f).pow(2))
    val breath = .5f + .5f * sin(t * 2 * PI.toFloat() / 3.4f)
    val tierUp = earn != null && earn % FocusCatalog.RANKS_PER_TIER == 0
    val c = Offset(cx, cy)

    // Свечение за целью: «дыхание», у самой цели — сердцебиение.
    val glowA = ((.16f + .1f * breath) + near * (.18f + .25f * beat)) * f.ringA * (1 - drop) * ent
    drawCircle(Brush.radialGradient(listOf(ringCol.copy(alpha = clamp01(glowA)), Color.Transparent), c, ringR * 1.75f), ringR * 1.75f, c)

    // Лучи при новой ступени.
    if (tierUp) {
        val k = seg(f.earnT, .15f, .5f) * (1 - seg(f.earnT, 1.6f, 2.2f))
        if (k > 0) for (j in 0 until 14) rotate(j * 360f / 14 + f.earnT * 22, c) {
            val path = Path().apply {
                moveTo(cx - 5 * d, cy - ringR - 4 * d); lineTo(cx, cy - ringR - 62 * d * k); lineTo(cx + 5 * d, cy - ringR - 4 * d); close()
            }
            drawPath(path, acc.copy(alpha = .32f * k))
        }
    }

    // Дорожка: прошлое — сплошная в цвет ступени, будущее — пунктир. Во время сдвига гаснет.
    val trackA = (1 - drop) * ent * (if (f.u < 1f) (1 - 2 * f.u).pow(2) else 1f)
    if (trackA > .01f) {
        val lx = slotX[1] * w + 24 * d; val rx = slotX[3] * w - 21 * d
        drawLine(Brush.horizontalGradient(listOf(lerp(acc, TRACK, .55f), ringCol), lx, cx - ringR - 6 * d),
            Offset(lx, cy), Offset(cx - ringR - 6 * d, cy), 3 * d, StrokeCap.Round, alpha = trackA * (1 - .4f * f.pause))
        var x = cx + ringR + 6 * d
        while (x < rx) { drawCircle(Color(0xFF3A4150).copy(alpha = trackA), 1.5f * d, Offset(x, cy)); x += 9 * d }
        // Раз в 9 секунд по дорожке к цели летят три искры.
        val ph = (t - 3.2f) % 9f
        if (f.pause < .01f && earn == null && ph in 0f..1.6f) for (j in 0 until 3) {
            val k = seg(ph, j * .18f, j * .18f + 1)
            if (k <= 0f || k >= 1f) continue
            val sx = mix(lx, cx - ringR - 2 * d, eInOut(k)); val a = sin(k * PI.toFloat()) * trackA
            drawCircle(Brush.radialGradient(listOf(ringCol.copy(alpha = .4f * a), Color.Transparent), Offset(sx, cy), 8 * d), 8 * d, Offset(sx, cy))
            drawCircle(Color.White.copy(alpha = a), 2.2f * d, Offset(sx, cy))
        }
    }

    // Кольцо прогресса; на паузе — оранжевый отсчёт, последние 10 секунд мигает.
    val rA = f.ringA * (1 - drop) * ent
    if (rA > .01f) {
        val sw = 4 * d
        drawCircle(lerp(TRACK, Color(0xFF3A2A1C), f.pause).copy(alpha = rA), ringR, c, style = Stroke(sw))
        val value = if (earn != null) 1f else mix(f.progress, f.left / RewardsEngine.PAUSE_GRACE_SECONDS, f.pause)
        val blink = if (f.pause > .5f && f.left < 10) .55f + .45f * cos(t * PI.toFloat() * 4) else 1f
        val flashW = if (earn != null) 1 - seg(f.earnT, 0f, .45f) else 0f
        val col = if (earn != null) lerp(acc, Color.White, flashW * .8f) else ringCol
        val a = rA * blink
        drawArc(col.copy(alpha = .3f * a), -90f, 360f * value, false, Offset(cx - ringR, cy - ringR), Size(ringR * 2, ringR * 2), style = Stroke(sw + 5 * d, cap = StrokeCap.Round))
        if (f.ringFx != null && f.pause < .5f && earn == null) {
            withTransform({}) { drawRingFxAlpha(f.ringFx, c, ringR, 360f * value, sw, t, col, a) }
        } else {
            drawArc(col.copy(alpha = a), -90f, 360f * value, false, Offset(cx - ringR, cy - ringR), Size(ringR * 2, ringR * 2), style = Stroke(sw, cap = StrokeCap.Round))
        }
        // Искра на конце дуги.
        if (earn == null && value in .02f..0.995f) {
            val ang = Math.toRadians((value * 360 - 90).toDouble())
            val o = Offset(cx + (cos(ang) * ringR).toFloat(), cy + (sin(ang) * ringR).toFloat())
            val sr = (2.4f + near * 1.2f * beat) * d
            drawCircle(Brush.radialGradient(listOf(ringCol.copy(alpha = .5f * a), Color.Transparent), o, sr * 3), sr * 3, o)
            drawCircle(Color.White.copy(alpha = .95f * a), sr, o)
        }
    }

    // Медали.
    val curShown = f.cur
    for (i in curShown - 1..curShown + 2) {
        if (i < -1 || i >= FocusCatalog.COUNT || (i == -1 && curShown > 0)) continue
        val p = i - curShown + 1 - f.u
        val (sx, sz, sop) = slot(p)
        var op = sop * ent
        val earned = i <= curShown
        val isTarget = abs(p - 1) < .5f
        var lock = clamp01(p - 1); var dull = if (earned) 0f else clamp01(p)
        if (!earned && i == target) dull = 1f
        if (earned && p < .5f) op *= mix(.86f, 1f, clamp01(p + .5f))
        var dy = 0f; var rot = 0f; var pz = f.pause
        if (f.enter < 1f) { val k = eBack(seg(f.enter, .1f + .15f * p, .7f + .15f * p)); op *= clamp01(k * 1.4f); dy = (1 - clamp01(k)) * 18 * d }
        if (drop > 0f) { dy += 26 * d * drop * drop; op *= 1 - drop; rot = (p - 1) * 18 * drop; pz = 1f }
        var flip = 1f; var scale = 1f; var flash = 0f
        if (earn != null && i == earn) {
            val fl = seg(f.earnT, .05f, .65f)
            flip = abs(cos(fl * PI.toFloat()))
            if (fl >= .5f) dull = 0f
            scale = 1 + .2f * sin(seg(f.earnT, .05f, .95f) * PI.toFloat())
            flash = max(0f, 1 - abs(fl - .5f) * 4)
        } else if (isTarget && earn == null) scale = 1 + .05f * near * beat
        val mc = Offset(sx, cy + dy)
        drawRibbonMedal(i, mc, sz, op, lock, dull, pz, flash, flip, scale, rot)
        // Блик по металлу цели раз в 7 секунд.
        if (isTarget && f.pause < .01f && drop == 0f && sz > 20 * d) {
            val k = seg((t + 1.4f) % 7f, 0f, .9f)
            if (k > 0f && k < 1f) {
                val rr = sz * .44f * scale
                clipPath(Path().apply { addOval(androidx.compose.ui.geometry.Rect(mc, rr)) }) {
                    val bx = mc.x - rr + eInOut(k) * rr * 3.2f - rr * .6f
                    rotate(22f, mc) {
                        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = .55f * sin(k * PI.toFloat())), Color.Transparent), bx - rr * .4f, bx + rr * .4f),
                            Offset(bx - rr * .4f, mc.y - rr * 2), Size(rr * .8f, rr * 4))
                    }
                }
            }
        }
        // Галочка на полученной медали.
        if (earned && i >= 0 && p > -.6f && p < .6f) {
            val a = (1 - abs(p) / .6f) * op
            val b = Offset(sx + sz * .33f, cy + sz * .3f)
            drawCircle(Color(0xFF16191F).copy(alpha = a), 9 * d, b)
            drawCircle(Color(0xFF1F6B3A).copy(alpha = a), 7.5f * d, b)
            val path = Path().apply { moveTo(b.x - 3.4f * d, b.y); lineTo(b.x - 1.1f * d, b.y + 2.4f * d); lineTo(b.x + 3.3f * d, b.y - 2.2f * d) }
            drawPath(path, Color(0xFFBFF5CF).copy(alpha = a), style = Stroke(1.8f * d, cap = StrokeCap.Round))
        }
    }

    // Вручение: кольцо-волна, большая волна новой ступени и брызги.
    if (earn != null) {
        val tau = f.earnT
        val w1 = seg(tau, 0f, .7f)
        if (w1 > 0 && w1 < 1) drawCircle(acc.copy(alpha = 1 - w1), ringR + 30 * d * eOut(w1), c, style = Stroke((3 * (1 - w1) + .5f) * d))
        if (tierUp) {
            val w2 = seg(tau, .2f, 1.2f)
            if (w2 > 0 && w2 < 1) drawCircle(acc.copy(alpha = .7f * (1 - w2)), ringR + 140 * d * eOut(w2), c, style = Stroke((6 * (1 - w2) + .5f) * d))
        }
        val n = if (tierUp) 22 else 14
        val b = seg(tau, .3f, if (tierUp) 1.6f else 1.25f)
        if (b > 0 && b < 1) for (j in 0 until n) {
            val a = j.toFloat() / n * 2 * PI.toFloat() + (j % 2) * .2f
            val dist = ringR - 4 * d + (if (tierUp) 70 else 42) * d * eOut(b) * (.75f + .25f * ((j * 7) % 5) / 4f)
            val o = Offset(cx + cos(a) * dist, cy + sin(a) * dist - 10 * d * b * b)
            val col = listOf(acc, Color.White, lerp(acc, Color(0xFFFFD86B), .5f))[j % 3]
            drawCircle(col.copy(alpha = 1 - b), (if (j % 3 == 0) 3f else 2f) * (1 - b * .6f) * d, o)
        }
    }

    // Подпись под целью.
    for (l in labels(f)) drawLabel(l.text, l.color, l.alpha * ent, l.scale, l.bold, Offset(cx, cy + 51 * d))
}

/** То же, что [drawRingFx], но с общей прозрачностью — для мигания и появления. */
private fun DrawScope.drawRingFxAlpha(effect: String, c: Offset, r: Float, sweep: Float, width: Float, t: Float, base: Color, alpha: Float) {
    if (alpha >= .99f) { drawRingFx(effect, c, r, sweep, width, t, base); return }
    drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(c, r + width * 3), androidx.compose.ui.graphics.Paint().apply { this.alpha = alpha })
    drawRingFx(effect, c, r, sweep, width, t, base)
    drawContext.canvas.restore()
}
