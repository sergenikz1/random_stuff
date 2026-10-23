package com.voicereader.ui.rewards

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.PathParser
import com.voicereader.rewards.FocusCatalog
import com.voicereader.rewards.GrowKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Палитра ступени медали: обод, лицевая сторона, «чернила» эмблемы и цвет камня. */
private class TierLook(val rim: Pair<Long, Long>, val face: Pair<Long, Long>, val ink: Long, val gem: Long? = null)

private val TIERS = listOf(
    TierLook(0xFF5E3A1A to 0xFFB07A45, 0xFF7A4D25 to 0xFFC4915A, 0xFF3D230D),
    TierLook(0xFF4B525B to 0xFFA8B0B9, 0xFF6B737D to 0xFFC3C9D0, 0xFF2B3036),
    TierLook(0xFF2C3238 to 0xFF8D99A5, 0xFF454D56 to 0xFF9FABB7, 0xFF161A1E),
    TierLook(0xFF6B3C1C to 0xFFE0A06A, 0xFF9C5B2E to 0xFFF0B27A, 0xFF4A2610),
    TierLook(0xFF7D8790 to 0xFFF4F7FA, 0xFFA9B3BC to 0xFFFFFFFF, 0xFF56606A),
    TierLook(0xFF7A5A08 to 0xFFFFE08A, 0xFFB8860B to 0xFFFFD86B, 0xFF5A4104),
    TierLook(0xFF5F7A86 to 0xFFE3F4F8, 0xFF8FB3C0 to 0xFFEFFCFF, 0xFF3A5560),
    TierLook(0xFF0F2A6B to 0xFF6EA0FF, 0xFF1F47B3 to 0xFF8FB8FF, 0xFF0B1A44, 0xFF3D74FF),
    TierLook(0xFF0B4A2C to 0xFF5EE39A, 0xFF13804A to 0xFF7FF0B0, 0xFF06301C, 0xFF22C46E),
    TierLook(0xFF5C0A1C to 0xFFFF6F8A, 0xFFA3122F to 0xFFFF8AA0, 0xFF3D0612, 0xFFFF2A55),
    TierLook(0xFF4A6B85 to 0xFFF0FBFF, 0xFF9FD6F2 to 0xFFFFFFFF, 0xFF2C4A60, 0xFFBFF0FF)
)

private val pathCache = HashMap<String, Path>()
private fun svg(d: String): Path = pathCache.getOrPut(d) { PathParser().parsePathString(d).toPath() }

private val GRAYSCALE = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/** Обесцвечивает всё нарисованное внутри — для закрытых наград. */
private fun DrawScope.maybeGray(gray: Boolean, dim: Float = 0.45f, block: DrawScope.() -> Unit) {
    if (!gray) { block(); return }
    drawIntoCanvas { canvas ->
        val paint = androidx.compose.ui.graphics.Paint().apply { colorFilter = GRAYSCALE; alpha = dim }
        canvas.saveLayer(Rect(Offset.Zero, size), paint)
        block()
        canvas.restore()
    }
}

/** Рисует в координатах −50…50 (как макеты), вписывая их в размер холста. */
private inline fun DrawScope.inBox(half: Float, block: DrawScope.() -> Unit) {
    val s = min(size.width, size.height) / (half * 2)
    withTransform({
        translate(size.width / 2, size.height / 2)
        scale(s, s, Offset.Zero)
    }) { block() }
}

@Composable
fun MedalArt(index: Int, modifier: Modifier = Modifier, locked: Boolean = false) {
    Canvas(modifier) { maybeGray(locked) { inBox(50f) { drawMedal(index.coerceIn(0, FocusCatalog.COUNT - 1)) } } }
}

private fun DrawScope.drawMedal(index: Int) {
    val tier = index / FocusCatalog.RANKS_PER_TIER
    val rank = index % FocusCatalog.RANKS_PER_TIER
    val look = TIERS[tier]
    val rimLight = Color(look.rim.second); val rimDark = Color(look.rim.first)
    val ink = Color(look.ink); val faceLight = Color(look.face.second)
    drawCircle(Brush.linearGradient(listOf(rimLight, rimDark), Offset(-44f, -44f), Offset(44f, 44f)), 44f, Offset.Zero)
    drawCircle(Brush.radialGradient(listOf(faceLight, Color(look.face.first)), Offset(-12f, -14f), 58f), 36f, Offset.Zero)
    drawCircle(rimDark, 36f, Offset.Zero, style = Stroke(1.5f))
    if (tier == 0) {
        listOf("M-30 -10q15 6 30 0t30 0", "M-32 4q16 6 32 0t32 0", "M-28 18q14 5 28 0t28 0").forEach {
            drawPath(svg(it), ink.copy(alpha = 0.18f), style = Stroke(1.2f))
        }
    }
    withTransform({ translate(0f, -4f) }) {
        val fill = look.gem?.let { Color(it) } ?: ink
        val highlight = if (look.gem != null) ink else faceLight
        drawEmblem(tier, fill, highlight, if (look.gem != null) ink else null)
    }
    // Лента с римской цифрой ранга
    drawPath(svg("M-22 30h44l-5 8 5 8h-44l5-8z"), rimDark)
    drawPath(svg("M-19 32h38l-3 6 3 6h-38l3-6z"), Brush.linearGradient(listOf(rimLight, rimDark), Offset(-19f, 32f), Offset(19f, 44f)))
    drawLabel(FocusCatalog.ROMAN[rank], 0f, 42.5f, 11f, look.ink, serif = true)
}

private fun DrawScope.drawLabel(text: String, x: Float, y: Float, size: Float, color: Long, serif: Boolean = false) {
    drawIntoCanvas { canvas ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toInt(); textSize = size; textAlign = Paint.Align.CENTER
            typeface = Typeface.create(if (serif) Typeface.SERIF else Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.nativeCanvas.drawText(text, x, y, paint)
    }
}

/** Свои эмблемы: лист, свеча, ключ, колокол, месяц, солнце, компас, снежинка, клевер, пламя, корона. */
private fun DrawScope.drawEmblem(tier: Int, fill: Color, hl: Color, outline: Color?) {
    fun shape(d: String) {
        val p = svg(d)
        drawPath(p, fill)
        outline?.let { drawPath(p, it, style = Stroke(1.4f)) }
    }
    fun line(d: String, color: Color, width: Float) = drawPath(svg(d), color, style = Stroke(width, cap = StrokeCap.Round))
    when (tier) {
        0 -> { shape("M-15 15C-15 -5 -2 -18 17 -18 17 3 4 17 -15 15z"); line("M-15 15L9 -9M-3 3l-6-4M3 -3l2 7", hl, 2f) }
        1 -> { shape("M-6 -6h12v23h-12z"); shape("M0 -9c-5-5 0-10 0-13 4 4 6 8 0 13z"); line("M-6 0h12", hl, 1.5f) }
        2 -> { drawCircle(fill, 9f, Offset(-7f, -7f)); drawCircle(hl, 4f, Offset(-7f, -7f)); shape("M-1 -1l16 16-4 4-3-3-3 3-3-3 3-3-7-7z") }
        3 -> { shape("M-14 10c3-3 3-8 3-14a11 11 0 0 1 22 0c0 6 0 11 3 14z"); drawCircle(fill, 4f, Offset(0f, 15f)); shape("M-2 -22h4v5h-4z"); line("M-4 -12a7 7 0 0 0-4 7", hl, 2f) }
        4 -> { shape("M6 -18a18 18 0 1 0 12 26A15 15 0 0 1 6 -18z"); drawPath(svg("M9 -6l1.5 3 3 .5-2.2 2 .6 3.2L9 1.2l-2.9 1.5.6-3.2-2.2-2 3-.5z"), hl) }
        5 -> { drawCircle(fill, 9f, Offset.Zero); for (a in 0 until 8) rotate(a * 45f, Offset.Zero) { line("M0 -13v-6", fill, 3.2f) } }
        6 -> { drawCircle(fill, 17f, Offset.Zero, style = Stroke(3f)); shape("M0 -15l4 11 11 4-11 4-4 11-4-11-11-4 11-4z"); drawCircle(hl, 3f, Offset.Zero) }
        7 -> for (a in 0 until 3) rotate(a * 60f, Offset.Zero) {
            line("M0 -18v36M-5 -14l5 4 5-4M-5 14l5-4 5 4", outline ?: fill, 5.6f); line("M0 -18v36M-5 -14l5 4 5-4M-5 14l5-4 5 4", fill, 3.4f)
        }
        8 -> { for (a in 0 until 4) rotate(a * 90f, Offset.Zero) { drawCircle(fill, 7.5f, Offset(0f, -8f)); outline?.let { drawCircle(it, 7.5f, Offset(0f, -8f), style = Stroke(1.2f)) } }
            line("M0 0q4 12 10 18", outline ?: fill, 3f) }
        9 -> shape("M0 -20c9 9 15 15 15 23a15 15 0 0 1-30 0c0-6 4-10 7-13 0 6 2 9 5 9-2-8 3-13 3-19z")
        else -> { shape("M-18 12l-3-22 11 9 10-15 10 15 11-9-3 22z"); shape("M-18 13h36v5h-36z"); drawCircle(hl, 3f, Offset(0f, 1f)) }
    }
}

// --- Звания: щит со звёздами; цвет звёзд меняется каждые 5 званий ---

private val STAR_COLORS = longArrayOf(0xFFD08A4E, 0xFFDFE6EE, 0xFFFFD86B, 0xFF5EE39A, 0xFFFF6F8A, 0xFFBFF0FF)

@Composable
fun RankBadge(rank: Int, modifier: Modifier = Modifier, locked: Boolean = false) {
    Canvas(modifier) { maybeGray(locked, 0.55f) { inBox(36f) { drawBadge(rank) } } }
}

private fun DrawScope.drawBadge(rank: Int) {
    val color = Color(STAR_COLORS[(rank / 5).coerceIn(0, STAR_COLORS.size - 1)])
    val stars = rank % 5 + 1
    val shield = svg("M-24 -32h48v46l-24 18-24-18z")
    drawPath(shield, Color(0xFF1F2633))
    drawPath(shield, color, style = Stroke(2.5f))
    drawPath(svg("M-18 -26h36"), color.copy(alpha = 0.5f), style = Stroke(1.5f))
    val xs = when (stars) { 1 -> listOf(0f); 2 -> listOf(-9f, 9f); 3 -> listOf(-12f, 0f, 12f); 4 -> listOf(-14f, -5f, 5f, 14f); else -> listOf(-16f, -8f, 0f, 8f, 16f) }
    val star = svg("M0 -10l2.9 6.1 6.6.8-4.9 4.5 1.3 6.6L0 4.8l-5.9 3.2 1.3-6.6-4.9-4.5 6.6-.8z")
    xs.forEachIndexed { k, x ->
        val y = if (stars > 3) (if (k % 2 == 1) 2f else -6f) else -2f
        val s = if (stars > 3) 0.62f else 0.8f
        withTransform({ translate(x, y); scale(s, s, Offset.Zero) }) { drawPath(star, color) }
    }
    drawLabel((rank + 1).toString(), 0f, 22f, 9f, STAR_COLORS[(rank / 5).coerceIn(0, STAR_COLORS.size - 1)])
}

// --- Что растёт каждый день: 11 стадий для каждого вида ---

@Composable
fun GrowthArt(kind: GrowKind, stage: Int, modifier: Modifier = Modifier, locked: Boolean = false, wilted: Boolean = false) {
    val wiltFilter = remember {
        ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
            0.55f, 0.35f, 0.1f, 0f, 10f,
            0.45f, 0.40f, 0.1f, 0f, 0f,
            0.30f, 0.25f, 0.1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )))
    }
    Canvas(modifier) {
        maybeGray(locked) {
            if (wilted) drawIntoCanvas { c ->
                c.saveLayer(Rect(Offset.Zero, size), androidx.compose.ui.graphics.Paint().apply { colorFilter = wiltFilter })
                inBox(45f) { rotate(-3f, Offset(0f, 40f)) { drawGrowth(kind, stage) } }
                c.restore()
            } else inBox(45f) { drawGrowth(kind, stage) }
        }
    }
}

private val GROUND_DARK = Color(0xFF3A2A1A)
private val GROUND = Color(0xFF4D7A33)

private fun DrawScope.ground() {
    drawOval(GROUND_DARK, Offset(-38f, 35f), androidx.compose.ui.geometry.Size(76f, 12f))
    drawOval(GROUND, Offset(-34f, 36f), androidx.compose.ui.geometry.Size(68f, 8f))
}

private fun DrawScope.drawGrowth(kind: GrowKind, stage: Int) {
    when (kind) {
        GrowKind.TREE -> { ground(); tree(stage) }
        GrowKind.MUSHROOM -> { ground(); mushrooms(stage) }
        GrowKind.FIRE -> { if (stage < 9) ground(); fire(stage) }
        GrowKind.CRYSTAL -> crystals(stage)
        GrowKind.GARDEN -> { ground(); garden(stage) }
    }
}

private fun DrawScope.oneTree(x: Float, s: Float, dots: Int, flowers: Boolean, fruit: Boolean) {
    val h = 10 + s * 5; val r = 6 + s * 3.2f
    val trunk = Path().apply {
        moveTo(x - 1.5f - s * .3f, 40f); lineTo(x - 1 - s * .15f, 40 - h); lineTo(x + 1 + s * .15f, 40 - h); lineTo(x + 1.5f + s * .3f, 40f); close()
    }
    drawPath(trunk, Color(0xFF7A4D25))
    drawCircle(Color(0xFF2F8F4E), r, Offset(x, 40 - h - r * .4f))
    drawCircle(Color(0xFF3AA65C), r * .7f, Offset(x - r * .55f, 40 - h + r * .15f))
    drawCircle(Color(0xFF2B8448), r * .72f, Offset(x + r * .55f, 40 - h + r * .1f))
    drawCircle(Color(0xFF4CBE6C), r * .55f, Offset(x - r * .2f, 40 - h - r * .75f))
    for (i in 0 until dots) {
        val a = i * 2.4; val rr = r * .7f
        val c = Offset(x + (cos(a) * rr * .8).toFloat(), 40 - h - r * .3f + (sin(a) * rr * .6).toFloat())
        if (flowers) drawCircle(Color(0xFFFFB3CF), 1.8f, c) else if (fruit) drawCircle(Color(0xFFE5484D), 2.3f, c)
    }
}

private fun DrawScope.tree(stage: Int) {
    val leaf = Color(0xFF4CBE6C)
    when {
        stage == 0 -> { drawOval(Color(0xFFA0723F), Offset(-4f, 30.5f), androidx.compose.ui.geometry.Size(8f, 11f)); drawPath(svg("M0 31q2 -3 0 -6"), Color(0xFF7A4D25), style = Stroke(1.4f)) }
        stage == 1 -> { drawLine(leaf, Offset(0f, 40f), Offset(0f, 28f), 2f); drawPath(svg("M0 30q-8 -2 -9 -8q7 0 9 8zM0 32q8 -3 9 -9q-7 1 -9 9z"), leaf) }
        stage == 2 -> { drawLine(Color(0xFF6B8F3A), Offset(0f, 40f), Offset(0f, 20f), 2.4f)
            listOf(-8f to -22f, 8f to -26f, -6f to -32f, 6f to -34f).forEach { (a, b) ->
                drawPath(svg("M0 ${40 + b + 6}q$a -2 ${a * 1.2f} -6q${-a * .8f} 0 ${-a * 1.2f} 6z"), leaf) }
        }
        stage <= 8 -> oneTree(0f, stage - 2.2f, if (stage == 5) 9 else if (stage == 6) 7 else 0, stage == 5, stage == 6)
        stage == 9 -> { oneTree(-17f, 3f, 0, false, false); oneTree(17f, 3.2f, 0, false, false); oneTree(0f, 5.2f, 0, false, false) }
        else -> { oneTree(-24f, 2.6f, 0, false, false); oneTree(24f, 2.8f, 0, false, false); oneTree(-10f, 4f, 0, false, false); oneTree(11f, 4.2f, 0, false, false); oneTree(0f, 5.6f, 5, false, true) }
    }
}

private fun DrawScope.mushroom(x: Float, s: Float, glow: Boolean) {
    val w = 6 + s * 3; val hh = 6 + s * 3.4f
    drawPath(svg("M${x - w * .32f} 40 q${w * .1f} ${-hh} 0 ${-hh} h${w * .64f} q${-w * .1f} 0 0 ${hh}z"), Color(0xFFF1E6D0))
    if (glow) drawCircle(Color(0x5559E0D0), w * 1.2f, Offset(x, 40 - hh - w * .3f))
    drawPath(svg("M${x - w} ${40 - hh + 1} q$w ${-w * 1.3f} ${w * 2} 0z"), if (glow) Color(0xFF59E0D0) else Color(0xFFC8442E))
    drawCircle(Color.White, w * .13f, Offset(x - w * .4f, 40 - hh - w * .35f))
    drawCircle(Color.White, w * .1f, Offset(x + w * .35f, 40 - hh - w * .5f))
}

private fun DrawScope.mushrooms(stage: Int) {
    when {
        stage == 0 -> listOf(Offset(0f, 36f) to 2f, Offset(5f, 33f) to 1.4f, Offset(-4f, 31f) to 1.2f).forEach { (o, r) -> drawCircle(Color(0xFFD9C9A8), r, o) }
        stage == 1 -> drawPath(svg("M-20 39q10-6 20 0t20 0M-14 40q6-4 14-2"), Color(0xFFE8DCC0), style = Stroke(1.2f))
        stage <= 3 -> mushroom(0f, stage.toFloat(), false)
        stage == 4 -> { mushroom(-9f, 2.6f, false); mushroom(9f, 2f, false); mushroom(0f, 3.4f, false) }
        stage == 5 -> { mushroom(-20f, 1.6f, false); mushroom(20f, 1.8f, false); mushroom(-8f, 2.6f, false); mushroom(9f, 3f, false) }
        stage == 6 -> { listOf(-24f, -12f, 0f, 12f, 24f).forEachIndexed { i, x -> mushroom(x, 1.4f + (i % 2) * .6f, false) }; mushroom(0f, 3.6f, false) }
        else -> {
            listOf(-26f, -13f, 0f, 13f, 26f).forEachIndexed { i, x -> mushroom(x, 1.6f + (i % 2) * .8f + (stage - 7) * .2f, true) }
            mushroom(0f, 3.8f + (stage - 7) * .3f, true)
            if (stage >= 9) listOf(Offset(-18f, -8f), Offset(20f, -14f), Offset(4f, -22f)).forEach { drawCircle(Color(0xFFBBFFFF), 1.5f, it) }
        }
    }
}

private fun DrawScope.flame(x: Float, s: Float) {
    drawPath(svg("M$x ${40 - s * 7} q${s * 4} ${s * 4} ${s * 3} ${s * 6.5f} q${-s} ${s * .6f} ${-s * 3} ${s * .5f} q${-s * 2} 0 ${-s * 3} ${-s * .5f} q${-s} ${-s * 2.5f} ${s * 3} ${-s * 6.5f}z"), Color(0xFFFF9A3D))
    drawPath(svg("M$x ${40 - s * 4.6f} q${s * 2.4f} ${s * 2.6f} ${s * 1.6f} ${s * 4.2f} h${-s * 3.2f} q${-s * .8f} ${-s * 1.6f} ${s * 1.6f} ${-s * 4.2f}z"), Color(0xFFFFD86B))
}

private fun DrawScope.fire(stage: Int) {
    val logs = { drawPath(svg("M-16 40l30-6M16 40l-30-6"), Color(0xFF7A4D25), style = Stroke(4f, cap = StrokeCap.Round)) }
    when {
        stage == 0 -> { drawCircle(Color(0x55FF9A3D), 5f, Offset(0f, 34f)); drawCircle(Color(0xFFFFD86B), 2f, Offset(0f, 34f)) }
        stage == 1 -> { drawOval(Color(0xFF5A2A12), Offset(-6f, 34f), androidx.compose.ui.geometry.Size(12f, 6f)); drawOval(Color(0xFFFF7A2A), Offset(-3f, 34.4f), androidx.compose.ui.geometry.Size(6f, 3.2f)) }
        stage <= 3 -> flame(0f, stage * .9f)
        stage <= 5 -> { logs(); flame(0f, stage * .85f); flame(-6f, stage * .45f); flame(6f, stage * .5f) }
        stage == 6 -> { // Очаг: каменная арка
            drawPath(svg("M-26 40v-30h52v30z"), Color(0xFF6B5345)); drawPath(svg("M-30 12h60v-6h-60z"), Color(0xFF8A6E5C))
            drawPath(svg("M-16 40v-20q16-12 32 0v20z"), Color(0xFF1A1210)); flame(0f, 2.2f)
        }
        stage == 7 -> { // Печь: изразцы и труба
            drawPath(svg("M-24 40v-40h48v40z"), Color(0xFFE8E2D6)); drawPath(svg("M-24 0h48M-24 13h48M-24 26h48M0 0v40"), Color(0xFFB9AE9A), style = Stroke(1f))
            drawPath(svg("M6 0v-26h10v26z"), Color(0xFF9A8F80)); drawPath(svg("M-14 40v-14q8-8 16 0v14z"), Color(0xFF1A1210)); flame(-6f, 1.6f)
        }
        stage == 8 -> {
            drawPath(svg("M-9 40l3-50h12l3 50z"), Color(0xFFE8E2D6)); drawPath(svg("M-7.5 20h15M-6.5 0h13"), Color(0xFFC8442E), style = Stroke(5f))
            drawPath(svg("M-7 -20h14v10h-14z"), Color(0xFF2A2F3A)); drawCircle(Color(0x66FFD86B), 9f, Offset(0f, -15f)); drawCircle(Color(0xFFFFD86B), 4f, Offset(0f, -15f))
            drawPath(svg("M4 -15l30-6v12z"), Color(0x59FFD86B))
        }
        stage == 9 -> {
            drawCircle(Color(0x44FFB347), 22f, Offset.Zero); drawCircle(Color(0xFFFFF3C4), 14f, Offset.Zero)
            for (a in 0 until 8) rotate(a * 45f, Offset.Zero) { drawLine(Color(0xFFFFD86B), Offset(0f, -26f), Offset(0f, -34f), 2.5f, StrokeCap.Round) }
        }
        else -> { drawCircle(Color(0x44FFB347), 36f, Offset(0f, 4f)); drawCircle(Color(0xFFFFD86B), 26f, Offset(0f, 4f)) }
    }
}

private fun DrawScope.crystals(stage: Int) {
    drawOval(Color(0xFF2A2F3A), Offset(-34f, 36f), androidx.compose.ui.geometry.Size(68f, 10f))
    val n = min(5, 1 + stage / 2)
    val colors = listOf(Color(0xFF7FD3FF), Color(0xFFA98BFF), Color(0xFF5EE3C9))
    for (k in 0 until n) {
        val x = (k - (n - 1) / 2f) * 11; val h = 14 + stage * 3 - abs(x) * .6f; val w = 6f
        val p = svg("M${x - w} 38 L${x - w} ${38 - h} L$x ${38 - h - 8} L${x + w} ${38 - h} L${x + w} 38z")
        if (stage >= 7) drawPath(p, colors[k % 3].copy(alpha = 0.35f), style = Stroke(5f))
        drawPath(p, colors[k % 3]); drawPath(p, Color(0xFF1B2A44), style = Stroke(1f))
        drawLine(Color(0x59FFFFFF), Offset(x, 38 - h - 8), Offset(x, 38f), 1f)
    }
}

private fun DrawScope.garden(stage: Int) {
    val flowers = min(7, 1 + stage)
    val colors = listOf(Color(0xFFFF8AB8), Color(0xFFFFD86B), Color(0xFFA98BFF), Color(0xFFFF9E8A))
    for (k in 0 until flowers) {
        val x = (k - (flowers - 1) / 2f) * 9; val y = 28f - (k % 2) * 6 - stage
        drawLine(Color(0xFF3AA65C), Offset(x, 40f), Offset(x, y), 1.6f)
        if (stage == 0) { drawCircle(Color(0xFF3AA65C), 2.5f, Offset(x, y)); continue }
        for (a in 0 until 5) {
            val ang = a * 72 * PI / 180
            drawCircle(colors[k % 4], 2.6f, Offset(x + (cos(ang) * 3.2).toFloat(), y + (sin(ang) * 3.2).toFloat()))
        }
        drawCircle(Color(0xFFFFF3B0), 1.8f, Offset(x, y))
    }
}
