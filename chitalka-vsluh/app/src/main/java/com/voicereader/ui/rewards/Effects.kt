package com.voicereader.ui.rewards

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.runtime.withFrameNanos
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Секунды с момента появления на экране — общий «часовой механизм» для всех анимаций наград. */
@Composable
fun rememberClock(): State<Float> {
    val t = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { t.floatValue = (it - start) / 1_000_000_000f }
    }
    return t
}

/** Детерминированный «случай» по номеру частицы: одинаковый рисунок на каждом кадре. */
fun rnd(k: Int): Float {
    val x = sin(k * 127.1 + 311.7) * 43758.5453
    return (x - floor(x)).toFloat()
}

private fun Float.wrap(m: Float) = ((this % m) + m) % m

// ---------------- Живые фоны ----------------

/** Живой фон за текстом. Яркость нарочно низкая, чтобы не мешать читать. */
@Composable
fun LiveBackground(effect: String, modifier: Modifier = Modifier, intensity: Float = 1f) {
    val clock = rememberClock()
    Canvas(modifier) { drawLive(effect, clock.value, intensity) }
}

fun DrawScope.drawLive(effect: String, t: Float, k: Float = 1f) {
    val w = size.width; val h = size.height; val d = density
    when (effect) {
        "SNOW" -> for (i in 0 until 70) {
            val sp = (18 + rnd(i) * 30) * d
            val y = (rnd(i * 7) * h + t * sp).wrap(h + 20 * d) - 10 * d
            val x = rnd(i * 3) * w + sin(t * .8f + i) * 8 * d
            drawCircle(Color.White.copy(alpha = (.25f + .4f * rnd(i * 5)) * k), (1f + rnd(i * 11) * 1.8f) * d, Offset(x, y))
        }
        "FIREFLIES" -> for (i in 0 until 26) {
            val x = (rnd(i) * w + sin(t * (.2f + rnd(i * 2) * .3f) + i) * 40 * d).wrap(w)
            val y = (rnd(i * 3) * h + cos(t * (.15f + rnd(i * 4) * .25f) + i * 2) * 50 * d).wrap(h)
            val a = (.5f + .5f * sin(t * (1.5f + rnd(i * 5)) + i)) * k
            drawCircle(Brush.radialGradient(listOf(Color(0x99E8FF7A).copy(alpha = .55f * a), Color.Transparent), Offset(x, y), 12 * d), 12 * d, Offset(x, y))
            drawCircle(Color(0xFFF4FFB0).copy(alpha = .9f * a), 1.8f * d, Offset(x, y))
        }
        "RAIN" -> {
            for (i in 0 until 90) {
                val sp = (380 + rnd(i) * 220) * d
                val x = rnd(i * 3) * (w + 40 * d) - 20 * d
                val y = (rnd(i * 7) * h + t * sp).wrap(h + 40 * d) - 30 * d
                drawLine(Color(0xFFB8CCE0).copy(alpha = (.12f + .2f * rnd(i * 2)) * k), Offset(x, y), Offset(x - 4 * d, y + 16 * d), 1.1f * d)
            }
            // Капли на стекле медленно сползают.
            for (i in 0 until 14) {
                val x = rnd(i * 13) * w
                val y = (rnd(i * 17) * h + t * (6 + rnd(i) * 10) * d).wrap(h)
                drawCircle(Color(0xFFDDE8F5).copy(alpha = .14f * k), (2.5f + rnd(i * 19) * 3) * d, Offset(x, y), style = Stroke(1f * d))
            }
        }
        "STARS" -> for (i in 0 until 80) {
            val a = (.25f + .75f * abs(sin(t * (.6f + rnd(i * 2)) + i * 1.7f))) * k
            drawCircle(Color.White.copy(alpha = .55f * a), (.6f + rnd(i * 3) * 1.3f) * d, Offset(rnd(i) * w, rnd(i * 5) * h))
        }
        "LEAVES" -> for (i in 0 until 18) {
            val sp = (24 + rnd(i) * 26) * d
            val y = (rnd(i * 7) * h + t * sp).wrap(h + 30 * d) - 15 * d
            val x = rnd(i * 3) * w + sin(t * .9f + i) * 26 * d
            val c = listOf(Color(0xFFD9822B), Color(0xFFC0392B), Color(0xFFE0B04A))[i % 3]
            withTransform({ translate(x, y); rotate(t * (40 + rnd(i * 9) * 80) + i * 30, Offset.Zero) }) {
                drawOval(c.copy(alpha = .45f * k), Offset(-6 * d, -3 * d), Size(12 * d, 6 * d))
            }
        }
        "CANDLE" -> {
            val f = .8f + .12f * sin(t * 7.3f) + .08f * sin(t * 13.1f + 1)
            val c = Offset(w / 2, h * 1.02f)
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFB347).copy(alpha = .22f * f * k), Color(0xFFFF8A3D).copy(alpha = .08f * k), Color.Transparent), c, h * .75f * f), h * .75f * f, c)
        }
        "SEA" -> for (b in 0 until 4) {
            val path = Path()
            val base = h * (.78f + b * .06f)
            path.moveTo(0f, h)
            var x = 0f
            while (x <= w + 10) {
                path.lineTo(x, base + sin(x / (60 * d) + t * (.6f + b * .2f) + b) * 7 * d)
                x += 8 * d
            }
            path.lineTo(w, h); path.close()
            drawPath(path, Color(0xFF2E7DD6).copy(alpha = (.06f + b * .02f) * k))
        }
        "SAKURA" -> for (i in 0 until 26) {
            val sp = (26 + rnd(i) * 22) * d
            val y = (rnd(i * 7) * h + t * sp).wrap(h + 20 * d) - 10 * d
            val x = (rnd(i * 3) * w + t * 14 * d + sin(t + i) * 18 * d).wrap(w)
            withTransform({ translate(x, y); rotate(t * 60 + i * 40, Offset.Zero) }) {
                drawOval(Color(0xFFFFB7D0).copy(alpha = .5f * k), Offset(-4 * d, -2.4f * d), Size(8 * d, 4.8f * d))
            }
        }
        "BUBBLES" -> for (i in 0 until 30) {
            val sp = (20 + rnd(i) * 30) * d
            val y = h - (rnd(i * 7) * h + t * sp).wrap(h + 20 * d) + 10 * d
            val x = rnd(i * 3) * w + sin(t * 1.3f + i) * 6 * d
            drawCircle(Color(0xFFBFE8FF).copy(alpha = .3f * k), (2.5f + rnd(i * 5) * 5) * d, Offset(x, y), style = Stroke(1f * d))
        }
        "METEORS" -> {
            for (i in 0 until 40) drawCircle(Color.White.copy(alpha = .3f * k), (.6f + rnd(i) * .8f) * d, Offset(rnd(i * 3) * w, rnd(i * 5) * h))
            for (m in 0 until 2) {
                val period = 3.2f + m * 1.7f
                val ph = ((t + m * 1.3f) % period) / 1.1f
                if (ph < 1f) {
                    val n = floor((t + m * 1.3f) / period).toInt() + m * 31
                    val sx = rnd(n) * w * .8f + w * .2f; val sy = rnd(n * 3) * h * .4f
                    val x = sx - ph * 220 * d; val y = sy + ph * 120 * d
                    drawLine(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = .7f * k * (1 - ph))), Offset(x + 70 * d, y - 38 * d), Offset(x, y)),
                        Offset(x + 70 * d, y - 38 * d), Offset(x, y), 1.6f * d, StrokeCap.Round)
                }
            }
        }
        "NEBULA" -> {
            val cols = listOf(Color(0xFF7A5CFF), Color(0xFF3A8FD6), Color(0xFFFF6FA5), Color(0xFF5EE3C9))
            for (i in 0 until 4) {
                val c = Offset(w * (.2f + .6f * rnd(i)) + sin(t * .07f + i) * 60 * d, h * (.15f + .7f * rnd(i * 3)) + cos(t * .05f + i * 2) * 80 * d)
                val r = (160 + rnd(i * 5) * 120) * d
                drawCircle(Brush.radialGradient(listOf(cols[i].copy(alpha = .13f * k), Color.Transparent), c, r), r, c)
            }
        }
        "AURORA" -> for (b in 0 until 3) {
            val path = Path()
            val c = listOf(Color(0xFF5EE39A), Color(0xFF7FE3FF), Color(0xFFB49CFF))[b]
            val top = h * (.06f + b * .07f)
            path.moveTo(0f, top)
            var x = 0f
            while (x <= w + 10) {
                path.lineTo(x, top + sin(x / (90 * d) + t * (.25f + b * .1f) + b * 2) * 22 * d)
                x += 10 * d
            }
            path.lineTo(w, top + 140 * d); path.lineTo(0f, top + 140 * d); path.close()
            drawPath(path, Brush.verticalGradient(listOf(c.copy(alpha = (.13f + .05f * sin(t * .5f + b)) * k), Color.Transparent), top - 20 * d, top + 140 * d))
        }
    }
}

// ---------------- Подсветка текущего предложения ----------------

/**
 * Рисует подсветку озвучиваемого предложения. [p] — 0→1 за первые доли секунды после начала,
 * [t] — часы для бесконечных эффектов.
 */
fun DrawScope.drawHighlight(effect: String?, p: Float, t: Float, base: Color) {
    val r = CornerRadius(8 * density)
    when (effect) {
        null -> drawRoundRect(base, cornerRadius = r)
        "WAVE" -> {
            drawRoundRect(base, cornerRadius = r)
            val x = ((t * .35f) % 1.4f - .2f) * size.width
            drawRoundRect(Brush.horizontalGradient(listOf(Color.Transparent, Color(0x338AB4F8), Color.Transparent), x - 80 * density, x + 80 * density), cornerRadius = r)
        }
        "MARKER" -> drawRoundRect(Color(0x55FFD54F), size = Size(size.width * p, size.height), cornerRadius = r)
        "PEN" -> {
            drawRoundRect(base.copy(alpha = base.alpha * .6f), cornerRadius = r)
            val y = size.height - 2 * density
            drawLine(Color(0xFF8AB4F8), Offset(8 * density, y), Offset(8 * density + (size.width - 16 * density) * p, y), 2 * density, StrokeCap.Round)
        }
        "INK" -> drawRoundRect(base.copy(alpha = base.alpha * p), cornerRadius = r)
        "NEON" -> {
            val a = .55f + .35f * sin(t * 3f)
            drawRoundRect(Color(0x1A00E5FF), cornerRadius = r)
            drawRoundRect(Color(0xFF00E5FF).copy(alpha = .25f * a), cornerRadius = r, style = Stroke(6 * density))
            drawRoundRect(Color(0xFF7FF4FF).copy(alpha = a), cornerRadius = r, style = Stroke(1.5f * density))
        }
        "GOLD_DUST" -> {
            drawRoundRect(base, cornerRadius = r)
            for (i in 0 until 16) {
                val life = 2.2f + rnd(i) * 1.6f
                val ph = ((t + rnd(i * 3) * life) % life) / life
                val x = rnd(i * 5) * size.width
                val y = size.height - ph * (size.height + 14 * density)
                drawCircle(Color(0xFFFFD86B).copy(alpha = sin(ph * PI.toFloat()) * .8f), (1f + rnd(i * 7) * 1.4f) * density, Offset(x, y))
            }
        }
        else -> drawRoundRect(base, cornerRadius = r)
    }
}

/** Прозрачность текста при «чернилах»: предложение проявляется. */
fun inkAlpha(effect: String?, p: Float) = if (effect == "INK") .25f + .75f * p else 1f

/** Модификатор для активного предложения: сам следит за временем и стартом подсветки. */
@Composable
fun Modifier.sentenceHighlight(effect: String?, active: Boolean, key: Any, base: Color): Modifier {
    if (!active) return this
    val p = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { p.animateTo(1f, tween(if (effect == "PEN") 900 else 650, easing = LinearEasing)) }
    val clock = if (effect == "WAVE" || effect == "NEON" || effect == "GOLD_DUST") rememberClock() else null
    return this.drawBehind { drawHighlight(effect, p.value, clock?.value ?: 0f, base) }
}

// ---------------- Салют наград ----------------

/**
 * Брызги в окне нового ранга. [k] — 0→1 от вспышки до затухания, [c] — центр, [accent] — цвет эпохи.
 * Без выбранной награды — обычные искры.
 */
fun DrawScope.drawCelebrate(effect: String?, k: Float, c: Offset, accent: Color, count: Int = 24) {
    if (k <= 0f || k >= 1f) return
    val d = density
    val fade = 1 - k
    when (effect) {
        "CONFETTI" -> {
            val cols = listOf(Color(0xFFFF6F8A), Color(0xFFFFD86B), Color(0xFF5EE39A), Color(0xFF7FB8FF), Color(0xFFB49CFF))
            for (i in 0 until count * 2) {
                val a = rnd(i) * 2 * PI; val v = (90 + rnd(i * 3) * 160) * d
                val x = c.x + (cos(a) * v * k).toFloat(); val y = c.y + (sin(a) * v * k).toFloat() + 220 * d * k * k
                withTransform({ translate(x, y); rotate(k * 720 * (rnd(i * 5) - .5f) + i * 30, Offset.Zero) }) {
                    drawRect(cols[i % cols.size].copy(alpha = fade), Offset(-3 * d, -1.5f * d), Size(6 * d, 3 * d))
                }
            }
        }
        "FIREWORKS" -> for (burst in 0 until 3) {
            val bk = ((k - burst * .18f) / .7f).coerceIn(0f, 1f)
            if (bk <= 0f || bk >= 1f) continue
            val bc = Offset(c.x + (burst - 1) * 90 * d, c.y - (60 + burst % 2 * 40) * d)
            val col = listOf(Color(0xFFFFD86B), Color(0xFFFF6F8A), Color(0xFF7FE3FF))[burst]
            for (i in 0 until 20) {
                val a = i / 20.0 * 2 * PI
                val r = 70 * d * (1 - (1 - bk) * (1 - bk))
                drawLine(col.copy(alpha = 1 - bk), Offset(bc.x + (cos(a) * r * .7f).toFloat(), bc.y + (sin(a) * r * .7f).toFloat()),
                    Offset(bc.x + (cos(a) * r).toFloat(), bc.y + (sin(a) * r).toFloat() + 10 * d * bk), 2 * d, StrokeCap.Round)
            }
        }
        "STARFALL" -> for (i in 0 until count) {
            val x = c.x + (rnd(i) - .5f) * 340 * d
            val y = c.y - 260 * d + k * (420 + rnd(i * 3) * 160) * d
            withTransform({ translate(x, y); scale(1.6f * d, 1.6f * d, Offset.Zero) }) {
                drawPath(starPath, Color(0xFFFFF3C4).copy(alpha = fade))
            }
        }
        "PETALS" -> for (i in 0 until count) {
            val x = c.x + (rnd(i) - .5f) * 320 * d + sin(k * 6 + i) * 20 * d
            val y = c.y - 200 * d + k * (360 + rnd(i * 3) * 120) * d
            withTransform({ translate(x, y); rotate(k * 400 + i * 40, Offset.Zero) }) {
                drawOval(Color(0xFFFFB7D0).copy(alpha = fade), Offset(-5 * d, -3 * d), Size(10 * d, 6 * d))
            }
        }
        "LIGHTNING" -> {
            val flash = if (k < .12f || (k > .3f && k < .38f)) 1f else 0f
            if (flash > 0) for (b in 0 until 3) {
                val path = Path(); var x = c.x + (b - 1) * 70 * d; var y = c.y - 260 * d
                path.moveTo(x, y)
                for (s in 0 until 7) { x += (rnd(b * 10 + s) - .5f) * 50 * d; y += 34 * d; path.lineTo(x, y) }
                drawPath(path, Color(0xFFBFE8FF).copy(alpha = .9f), style = Stroke(2.5f * d, cap = StrokeCap.Round))
                drawPath(path, Color(0x557FB8FF), style = Stroke(9f * d, cap = StrokeCap.Round))
            }
            for (i in 0 until count) {
                val a = rnd(i) * 2 * PI; val v = (60 + rnd(i * 3) * 120) * d * k
                drawCircle(Color(0xFFBFE8FF).copy(alpha = fade), 1.6f * d, Offset(c.x + (cos(a) * v).toFloat(), c.y + (sin(a) * v).toFloat()))
            }
        }
        "SPARKS" -> for (i in 0 until count * 2) {
            val a = -PI / 2 + (rnd(i) - .5f) * 2.4; val v = (120 + rnd(i * 3) * 200) * d
            val x = c.x + (cos(a) * v * k).toFloat(); val y = c.y + (sin(a) * v * k).toFloat() + 260 * d * k * k
            val col = if (i % 3 == 0) Color(0xFFFFF3C4) else if (i % 3 == 1) Color(0xFFFFB347) else Color(0xFFFF6A3D)
            drawCircle(col.copy(alpha = fade), (1.2f + rnd(i * 7) * 1.6f) * d, Offset(x, y))
        }
        else -> for (i in 0 until count) {
            val a = i.toDouble() / count * 2 * PI
            val v = (60 + 90 * (.7f + .3f * rnd(i))) * d * (1 - (1 - k) * (1 - k) * (1 - k))
            drawCircle((if (i % 3 == 0) Color.White else accent).copy(alpha = fade), (2.6f * (1 - k * .6f)) * d,
                Offset(c.x + (cos(a) * v).toFloat(), c.y + (sin(a) * v).toFloat() - 30 * d * k * k))
        }
    }
}

private val starPath = Path().apply {
    moveTo(0f, -5f); lineTo(1.2f, -1.2f); lineTo(5f, 0f); lineTo(1.2f, 1.2f); lineTo(0f, 5f); lineTo(-1.2f, 1.2f); lineTo(-5f, 0f); lineTo(-1.2f, -1.2f); close()
}

// ---------------- Смена главы ----------------

/** Переход при смене главы. [k] — 0→1 за время перехода, [bg] — цвет страницы. */
fun DrawScope.drawChapterTransition(effect: String, k: Float, bg: Color) {
    if (k <= 0f || k >= 1f) return
    val w = size.width; val h = size.height
    when (effect) {
        "FLIP" -> {
            // Страница уходит справа налево, на сгибе — тень.
            val x = w * (1 - k)
            drawRect(bg.copy(alpha = .92f), Offset(x, 0f), Size(w - x, h))
            drawRect(Brush.horizontalGradient(listOf(Color(0x66000000), Color.Transparent), x, x + 40 * density), Offset(x, 0f), Size(40 * density, h))
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color(0x22FFFFFF)), x - 24 * density, x), Offset(x - 24 * density, 0f), Size(24 * density, h))
        }
        "DISSOLVE" -> drawRect(bg.copy(alpha = sin(k * PI.toFloat()) * .9f))
        "SCROLL" -> {
            val y = h * k
            drawRect(bg.copy(alpha = .9f), Offset(0f, y), Size(w, h - y))
            drawRect(Brush.verticalGradient(listOf(Color(0xFFC9A86A), Color(0xFF8A6A3A)), y - 10 * density, y + 10 * density), Offset(0f, y - 10 * density), Size(w, 20 * density))
        }
        "SLIDE" -> {
            val x = if (k < .5f) w * (1 - k * 2) else -w * (k - .5f) * 2
            drawRect(bg.copy(alpha = .95f), Offset(x, 0f), Size(w, h))
        }
    }
}

// ---------------- Кольцо медали ----------------

/** Эффект на дуге прогресса: [start]/[sweep] в градусах, [r] — радиус, [c] — центр. */
fun DrawScope.drawRingFx(effect: String?, c: Offset, r: Float, sweep: Float, width: Float, t: Float, base: Color) {
    val topLeft = Offset(c.x - r, c.y - r); val sz = Size(r * 2, r * 2)
    when (effect) {
        "RAINBOW" -> drawArc(Brush.sweepGradient(listOf(Color(0xFFFF6F8A), Color(0xFFFFD86B), Color(0xFF5EE39A), Color(0xFF7FB8FF), Color(0xFFB49CFF), Color(0xFFFF6F8A)), c),
            -90f, sweep, false, topLeft, sz, style = Stroke(width, cap = StrokeCap.Round))
        "FLAME" -> {
            drawArc(Color(0xFFFF8A3D), -90f, sweep, false, topLeft, sz, style = Stroke(width, cap = StrokeCap.Round))
            val n = (sweep / 12).toInt()
            for (i in 0 until n) {
                val a = Math.toRadians((-90 + i * 12 + 6).toDouble())
                val flick = .6f + .4f * sin(t * 9 + i * 1.7f)
                val o = Offset(c.x + (cos(a) * r).toFloat(), c.y + (sin(a) * r).toFloat())
                drawCircle(Color(0xFFFFD86B).copy(alpha = .5f * flick), width * .9f * flick, o)
            }
        }
        "ELECTRIC" -> {
            drawArc(Color(0xFF7FE3FF).copy(alpha = .5f), -90f, sweep, false, topLeft, sz, style = Stroke(width, cap = StrokeCap.Round))
            val path = Path()
            val steps = (sweep / 6).toInt().coerceAtLeast(2)
            val seed = (t * 14).toInt()
            for (i in 0..steps) {
                val a = Math.toRadians((-90 + sweep * i / steps).toDouble())
                val rr = r + (rnd(seed * 50 + i) - .5f) * width * 1.6f
                val o = Offset(c.x + (cos(a) * rr).toFloat(), c.y + (sin(a) * rr).toFloat())
                if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            drawPath(path, Color(0xFFE6FBFF), style = Stroke(width * .45f, cap = StrokeCap.Round))
        }
        else -> drawArc(base, -90f, sweep, false, topLeft, sz, style = Stroke(width, cap = StrokeCap.Round))
    }
}

// ---------------- Превью анимации на плитке и в карточке награды ----------------

/** Маленькое живое превью анимации-награды. */
@Composable
fun AnimPreview(reward: com.voicereader.rewards.Reward, modifier: Modifier = Modifier) {
    val clock = rememberClock()
    val effect = reward.effect ?: return
    Canvas(modifier) {
        val t = clock.value
        drawRect(Color(0xFF0E1622))
        fun lines(alpha: Float = .3f) {
            for (k in 0 until 4) drawRoundRect(Color(0xFFC9D4E6).copy(alpha = alpha), Offset(size.width * .1f, size.height * (.18f + k * .2f)),
                Size(size.width * (if (k == 3) .5f else .8f - k * .05f), size.height * .08f), CornerRadius(size.height * .04f))
        }
        when (reward.anim) {
            com.voicereader.rewards.AnimKind.LIVE -> { lines(.18f); drawLive(effect, t, 1.6f) }
            com.voicereader.rewards.AnimKind.HIGHLIGHT -> {
                lines(.18f)
                val p = ((t % 2.4f) / .8f).coerceAtMost(1f)
                withTransform({ translate(size.width * .06f, size.height * .36f) }) {
                    val inner = Size(size.width * .88f, size.height * .3f)
                    val old = drawContext.size
                    drawContext.size = inner
                    drawHighlight(effect, p, t, Color(0x338AB4F8))
                    drawContext.size = old
                }
            }
            com.voicereader.rewards.AnimKind.CELEBRATE -> drawCelebrate(effect, (t % 1.8f) / 1.5f, Offset(size.width / 2, size.height * .55f), Color(0xFFFFD86B), 10)
            com.voicereader.rewards.AnimKind.CHAPTER -> { lines(); drawChapterTransition(effect, (t % 2f) / 1.4f, Color(0xFF1B2433)) }
            com.voicereader.rewards.AnimKind.RING -> {
                val c = Offset(size.width / 2, size.height / 2); val r = size.minDimension * .34f
                drawCircle(Color(0xFF2A303B), r, c, style = Stroke(3 * density))
                drawRingFx(effect, c, r, (t * 80) % 360f, 3 * density, t, Color(0xFFF0B27A))
            }
            null -> Unit
        }
    }
}
