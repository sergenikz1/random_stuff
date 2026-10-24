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
import com.voicereader.rewards.RanksCatalog
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

/**
 * Медаль для ленты: рисуется прямо на холсте с цветовым фильтром.
 * [lock] — закрыта (серая и тёмная), [dull] — ещё не получена (приглушена), [pause] — пауза серии,
 * [flash] — вспышка вручения, [flip] — сжатие по горизонтали при перевороте. index −1 — «Старт».
 */
fun DrawScope.drawRibbonMedal(
    index: Int, center: Offset, sizePx: Float, alpha: Float,
    lock: Float = 0f, dull: Float = 0f, pause: Float = 0f, flash: Float = 0f,
    flip: Float = 1f, scale: Float = 1f, rot: Float = 0f
) {
    if (alpha <= 0.01f || sizePx < 1f) return
    val gray = maxOf(lock, 0.85f * pause)
    val sat = (1 - gray) * (1 - 0.5f * dull)
    val bright = 1 - 0.55f * lock - 0.08f * dull - 0.12f * pause + 0.35f * flash
    val m = ColorMatrix().apply { setToSaturation(sat) }
    val v = m.values
    for (row in 0 until 3) for (col in 0 until 3) v[row * 5 + col] *= bright
    val half = sizePx * scale * 0.75f
    drawIntoCanvas { canvas ->
        val paint = androidx.compose.ui.graphics.Paint().apply { colorFilter = ColorFilter.colorMatrix(ColorMatrix(v)); this.alpha = alpha.coerceIn(0f, 1f) }
        canvas.saveLayer(Rect(center.x - half, center.y - half, center.x + half, center.y + half), paint)
        withTransform({
            translate(center.x, center.y); rotate(rot, Offset.Zero)
            scale(flip * scale * sizePx / 100f, scale * sizePx / 100f, Offset.Zero)
        }) {
            if (index < 0) {
                drawCircle(Color(0xFF161A21), 40f, Offset.Zero)
                drawCircle(Color(0xFF4A5263), 40f, Offset.Zero, style = Stroke(3f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(7f, 6f))))
                drawPath(svg("M-9 -16v32M-9 -16h20l-5 7 5 7h-20"), Color(0xFF8B95A7), style = Stroke(4f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            } else drawMedal(index.coerceIn(0, FocusCatalog.COUNT - 1))
        }
        canvas.restore()
    }
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

// --- Ранги: у каждого из 20 званий своя эмблема, оправа меняется по эпохам, звёзды — ступень I–V ---

private class Era(val rim: Long, val rim2: Long, val dark: Long)

private val ERAS = listOf(
    Era(0xFFD39A5C, 0xFFD39A5C, 0xFF3A2616),   // медная
    Era(0xFFD7E0EA, 0xFFD7E0EA, 0xFF262D38),   // серебряная
    Era(0xFFFFD86B, 0xFFFFD86B, 0xFF3A2E10),   // золотая, с лаврами
    Era(0xFFB9A2FF, 0xFF7FE3FF, 0xFF221A40)    // звёздная, с крыльями
)

/** Цвет эпохи ранга — для подсветки окна нового ранга. */
fun eraColor(rank: Int): Color = Color(ERAS[RanksCatalog.eraOf(rank).coerceIn(0, ERAS.size - 1)].rim)

/** [stars] — сколько звёзд уже зажжено (для анимации), null — все. */
@Composable
fun RankBadge(rank: Int, modifier: Modifier = Modifier, locked: Boolean = false, stars: Int? = null) {
    Canvas(modifier) { maybeGray(locked, 0.55f) { inBox(55f) { drawBadge(rank.coerceIn(0, RanksCatalog.COUNT - 1), stars) } } }
}

private val STAR = "M0 -10l2.9 6.1 6.6.8-4.9 4.5 1.3 6.6L0 4.8l-5.9 3.2 1.3-6.6-4.9-4.5 6.6-.8z"
private val DARK = Color(0x66000000)

private fun DrawScope.drawBadge(rank: Int, starsLit: Int?) {
    val title = RanksCatalog.titleOf(rank); val step = RanksCatalog.stepOf(rank); val e = RanksCatalog.eraOf(rank)
    val era = ERAS[e]
    val rim = Color(era.rim); val rim2 = Color(era.rim2); val dark = Color(era.dark)
    val brush = Brush.linearGradient(listOf(rim, rim2), Offset(-26f, -26f), Offset(26f, 34f))
    if (e >= 2) for (side in listOf(1f, -1f)) withTransform({ scale(side, 1f, Offset.Zero) }) {
        for (k in 0 until 5) withTransform({ translate(-31f + k * 1.2f, 18f - k * 9f); rotate(-30f + k * 8f, Offset.Zero) }) {
            drawOval(rim.copy(alpha = 0.9f), Offset(-3.2f, -6.5f), androidx.compose.ui.geometry.Size(6.4f, 13f))
        }
    }
    if (e >= 3) for (side in listOf(1f, -1f)) withTransform({ scale(side, 1f, Offset.Zero) }) {
        drawPath(svg("M-27 -14c-14-2-20 6-22 14 6-3 10-3 13-1-5 2-8 6-9 11 5-3 10-4 14-2"), rim2, style = Stroke(2.4f, cap = StrokeCap.Round))
    }
    val shield = svg("M-26 -26h52v32c0 14-12 22-26 28-14-6-26-14-26-28z")
    drawPath(shield, Brush.radialGradient(listOf(lerp(dark, Color.White, 0.12f), dark), Offset(-6f, -12f), 50f))
    drawPath(shield, brush, style = Stroke(if (e >= 1) 3.4f else 2.6f))
    if (e >= 1) drawLine(rim.copy(alpha = 0.5f), Offset(-20f, -20f), Offset(20f, -20f), 1.4f)
    withTransform({ translate(0f, 2f) }) { drawRankEmblem(title, brush, era.rim) }
    val n = step + 1
    for (k in 0 until n) withTransform({ translate((k - (n - 1) / 2f) * 9f, -33f); scale(0.42f, 0.42f, Offset.Zero) }) {
        drawPath(svg(STAR), if (starsLit == null || k < starsLit) rim else Color(0xFF3A4150))
    }
    val ribbon = svg("M-15 28h30l-3 6 3 6h-30l3-6z")
    drawPath(ribbon, dark); drawPath(ribbon, brush, style = Stroke(1.6f))
    drawLabel(RanksCatalog.ROMAN[step], 0f, 37.5f, 9f, era.rim)
}

private fun lerp(a: Color, b: Color, k: Float) = Color(a.red + (b.red - a.red) * k, a.green + (b.green - a.green) * k, a.blue + (b.blue - a.blue) * k, 1f)

/** Эмблемы званий: книга, наушники, карандаш, очки, стопка книг, микрофон, книга с сердцем, лампа, закладка, глобус,
 *  свиток, перо жар-птицы, перо с чернильницей, лупа, буквица «Ѣ», ключ, песочные часы, сова, шапочка магистра, башня. */
private fun DrawScope.drawRankEmblem(title: Int, c: Brush, rim: Long) {
    fun fill(d: String, alpha: Float = 1f) = drawPath(svg(d), c, alpha = alpha)
    fun dark(d: String) = drawPath(svg(d), DARK)
    fun line(d: String, w: Float, color: Color? = null) =
        if (color != null) drawPath(svg(d), color, style = Stroke(w, cap = StrokeCap.Round))
        else drawPath(svg(d), c, style = Stroke(w, cap = StrokeCap.Round))
    fun ring(cx: Float, cy: Float, r: Float, w: Float) = drawCircle(c, r, Offset(cx, cy), style = Stroke(w))
    fun box(x: Float, y: Float, w: Float, h: Float, r: Float, alpha: Float = 1f) =
        drawRoundRect(c, Offset(x, y), androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(r, r), alpha = alpha)
    when (title) {
        0 -> fill("M-16 -8q8-4 15 0v20q-7-4-15 0zM16 -8q-8-4-15 0v20q7-4 15 0z")
        1 -> { line("M-14 6v-4a14 14 0 0 1 28 0v4", 3.4f); box(-17f, 3f, 8f, 12f, 3f); box(9f, 3f, 8f, 12f, 3f) }
        2 -> { fill("M-13 13l3-9 17-17 6 6-17 17z"); drawPath(svg("M-13 13l3-9 6 6z"), Color(0xFFF6E0B5)); line("M4 -10l6 6", 1.5f, DARK) }
        3 -> { ring(-9f, 2f, 7f, 3.2f); ring(9f, 2f, 7f, 3.2f); line("M-2 1q2-3 4 0M-16 0l-4-5M16 0l4-5", 2.6f) }
        4 -> { box(-15f, 6f, 30f, 7f, 1.5f); box(-12f, -2f, 26f, 7f, 1.5f, 0.8f); box(-14f, -10f, 24f, 7f, 1.5f) }
        5 -> { box(-6f, -14f, 12f, 20f, 6f); line("M-11 0a11 11 0 0 0 22 0M0 11v5M-6 16h12", 2.8f) }
        6 -> { fill("M-14 -12h28v26h-28z"); drawPath(svg("M0 8c-9-6-9-12-4-13 2 0 4 2 4 3 0-1 2-3 4-3 5 1 5 7-4 13z"), Color(0x80000000)) }
        7 -> { fill("M-8 8a12 12 0 1 1 16 0v4h-16z"); box(-6f, 13f, 12f, 4f, 1.5f, 0.75f); line("M-3 4l3-6 3 6", 1.6f, DARK) }
        8 -> { fill("M-11 -15h22v30l-11-8-11 8z"); line("M-6 -9h12M-6 -4h12", 1.6f, DARK) }
        9 -> { ring(0f, 0f, 13f, 3f); drawOval(c, Offset(-6f, -13f), androidx.compose.ui.geometry.Size(12f, 26f), style = Stroke(2.2f)); line("M-13 0h26M-11 -7h22M-11 7h22", 2f) }
        10 -> { fill("M-12 -12h20a4 4 0 0 1 0 8h-2v18h-18a4 4 0 0 1 0-8h2z"); line("M-6 -2h10M-6 3h10", 1.6f, DARK) }
        11 -> { fill("M-12 15c4-14 12-25 25-30-2 12-10 22-21 26z"); line("M-12 15l15-19", 1.6f, Color(0x70000000)); drawCircle(DARK, 3f, Offset(6f, -6f)) }
        12 -> { fill("M-12 6h14l-2 9h-10z"); fill("M-4 5c3-10 9-17 18-20-1 9-7 16-15 20z", 0.85f) }
        13 -> { ring(-3f, -3f, 10f, 3.6f); line("M5 5l9 9", 5f) }
        14 -> drawLabel("Ѣ", 0f, 9f, 28f, rim, serif = true)
        15 -> { ring(-7f, -6f, 7f, 3.4f); line("M-2 -1l14 14M6 7l4-4M9 10l4-4", 3.4f) }
        16 -> { line("M-10 -15h20M-10 15h20", 3f); fill("M-8 -13h16l-8 13 8 13h-16l8-13z", 0.9f) }
        17 -> { fill("M-13 -12l5 4q8-4 16 0l5-4v20a13 12 0 0 1-26 0z"); drawCircle(DARK, 4f, Offset(-5f, -1f)); drawCircle(DARK, 4f, Offset(5f, -1f)); dark("M-2 5l2 3 2-3z") }
        18 -> { fill("M-18 -4l18-8 18 8-18 8z"); fill("M-10 0v8q10 6 20 0v-8", 0.8f); line("M15 -3v12", 2f); drawCircle(c, 2f, Offset(15f, 10f)) }
        else -> { fill("M-12 15v-20h24v20z"); fill("M-14 -5l4-9 5 5 5-8 5 8 5-5 4 9z"); dark("M-4 15v-9a4 4 0 0 1 8 0v9z") }
    }
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
        GrowKind.CACTUS -> cacti(stage)
        GrowKind.STARS -> constellation(stage)
        GrowKind.CASTLE -> { ground(); castle(stage) }
        GrowKind.CORAL -> reef(stage)
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

// --- Кактус: песок, колючки, цветы ---

private fun DrawScope.sand() {
    drawOval(Color(0xFF6B4F2A), Offset(-38f, 35f), androidx.compose.ui.geometry.Size(76f, 12f))
    drawOval(Color(0xFFC9A86A), Offset(-34f, 36f), androidx.compose.ui.geometry.Size(68f, 8f))
}

private fun DrawScope.cactus(x: Float, h: Float, w: Float, arms: Int, bloom: Int) {
    val green = Color(0xFF3C9A5A); val dark = Color(0xFF2B7444)
    fun body(left: Float, top: Float, bw: Float, bh: Float) =
        drawRoundRect(green, Offset(left, top), androidx.compose.ui.geometry.Size(bw, bh), androidx.compose.ui.geometry.CornerRadius(bw / 2, bw / 2))
    if (arms >= 1) { body(x - w * 1.5f, 40 - h * .7f, w * .55f, h * .35f); drawRect(green, Offset(x - w * 1.3f, 40 - h * .42f), androidx.compose.ui.geometry.Size(w, w * .45f)) }
    if (arms >= 2) { body(x + w * .95f, 40 - h * .85f, w * .55f, h * .4f); drawRect(green, Offset(x + w * .3f, 40 - h * .52f), androidx.compose.ui.geometry.Size(w, w * .45f)) }
    body(x - w / 2, 40 - h, w, h + 2)
    drawLine(dark, Offset(x, 40 - h + 3), Offset(x, 40f), 1f)
    if (w > 6) { drawLine(dark, Offset(x - w * .25f, 40 - h + 5), Offset(x - w * .25f, 40f), .8f); drawLine(dark, Offset(x + w * .25f, 40 - h + 5), Offset(x + w * .25f, 40f), .8f) }
    for (k in 0 until (h / 6).toInt()) {
        val y = 40 - h + 5 + k * 6
        drawLine(Color(0xFFF1E6D0), Offset(x - w / 2 - 1.5f, y), Offset(x - w / 2 + .5f, y), .7f)
        drawLine(Color(0xFFF1E6D0), Offset(x + w / 2 - .5f, y + 3), Offset(x + w / 2 + 1.5f, y + 3), .7f)
    }
    when (bloom) {
        1 -> drawOval(Color(0xFFFF8AB8), Offset(x - 2.5f, 40 - h - 4), androidx.compose.ui.geometry.Size(5f, 6f))
        2 -> { for (a in 0 until 5) { val ang = a * 72 * PI / 180 - PI / 2
            drawCircle(Color(0xFFFF6FA5), 2.8f, Offset(x + (cos(ang) * 3.4).toFloat(), 40 - h - 2 + (sin(ang) * 3.4).toFloat())) }
            drawCircle(Color(0xFFFFE08A), 1.8f, Offset(x, 40 - h - 2)) }
    }
}

private fun DrawScope.cacti(stage: Int) {
    sand()
    when (stage) {
        0 -> drawOval(Color(0xFF6B4F2A), Offset(-3f, 33f), androidx.compose.ui.geometry.Size(6f, 4f))
        1 -> drawRoundRect(Color(0xFF3C9A5A), Offset(-2.5f, 31f), androidx.compose.ui.geometry.Size(5f, 9f), androidx.compose.ui.geometry.CornerRadius(2.5f, 2.5f))
        2 -> cactus(0f, 14f, 7f, 0, 0)
        3 -> cactus(0f, 26f, 10f, 0, 0)
        4 -> cactus(0f, 30f, 10f, 1, 0)
        5 -> cactus(0f, 34f, 11f, 2, 1)
        6 -> cactus(0f, 36f, 11f, 2, 2)
        7 -> { cactus(-14f, 22f, 8f, 1, 2); cactus(8f, 36f, 11f, 2, 2) }
        8 -> { cactus(-20f, 18f, 7f, 0, 2); cactus(18f, 24f, 8f, 1, 1); cactus(0f, 38f, 11f, 2, 2) }
        9 -> { drawOval(Color(0xFF3A8FD6), Offset(-30f, 33f), androidx.compose.ui.geometry.Size(26f, 7f)); cactus(-24f, 18f, 7f, 0, 2); cactus(12f, 38f, 11f, 2, 2); cactus(28f, 20f, 7f, 1, 2) }
        else -> { drawOval(Color(0xFF3A8FD6), Offset(-30f, 33f), androidx.compose.ui.geometry.Size(26f, 7f))
            cactus(-26f, 20f, 7f, 1, 2); cactus(-8f, 28f, 9f, 1, 2); cactus(12f, 40f, 11f, 2, 2); cactus(29f, 22f, 7f, 1, 2)
            listOf(-34f, -18f, 2f, 22f, 34f).forEach { x -> drawCircle(Color(0xFFFFB3CF), 1.8f, Offset(x, 39f)) } }
    }
}

// --- Созвездие: ночной круг, звёзды и линии между ними ---

private val SKY_STARS = listOf(Offset(-18f, -2f), Offset(-6f, -14f), Offset(8f, -8f), Offset(18f, -20f), Offset(26f, -4f), Offset(14f, 8f), Offset(-2f, 12f))

private fun DrawScope.star(o: Offset, r: Float, glow: Boolean = false) {
    if (glow) drawCircle(Color(0x55BFD7FF), r * 3.2f, o)
    withTransform({ translate(o.x, o.y); scale(r, r, Offset.Zero) }) {
        drawPath(svg("M0 -2L.5 -.5 2 0 .5 .5 0 2-.5 .5-2 0-.5-.5z"), Color(0xFFFFF6D8))
    }
}

private fun DrawScope.constellation(stage: Int) {
    drawCircle(Brush.radialGradient(listOf(Color(0xFF26305A), Color(0xFF121833)), Offset(0f, -4f), 44f), 42f, Offset(0f, -2f))
    if (stage >= 6) withTransform({ rotate(-24f, Offset.Zero) }) { drawOval(Color(0x2AE6E0FF), Offset(-44f, -9f), androidx.compose.ui.geometry.Size(88f, 18f)) }
    if (stage >= 7) { drawCircle(Color(0x44A98BFF), 14f, Offset(-20f, 20f)); drawCircle(Color(0x33FF8AB8), 10f, Offset(-12f, 24f)) }
    if (stage >= 8) for (k in 0 until 40) {
        val a = k * .45; val r = 2 + k * .55
        drawCircle(Color(0xCCE6E0FF), .8f, Offset(22f + (cos(a) * r * .9).toFloat(), 22f + (sin(a) * r * .5).toFloat()))
    }
    if (stage >= 9) for (k in 0 until 26) drawCircle(Color(0xAAFFFFFF), .7f + (k % 3) * .3f, Offset(((k * 37) % 70 - 35).toFloat(), ((k * 53) % 60 - 34).toFloat()))
    val count = when (stage) { 0, 1 -> 1; 2 -> 2; 3 -> 3; else -> SKY_STARS.size }
    val lines = when (stage) { 0, 1 -> 0; 2 -> 1; 3 -> 3; else -> SKY_STARS.size - 1 }
    val stroke = Color(0x88BFD7FF)
    for (k in 0 until lines) {
        val a = SKY_STARS[k % count]; val b = SKY_STARS[(k + 1) % count]
        drawLine(stroke, a, b, .9f)
    }
    if (stage >= 5) drawLine(stroke, SKY_STARS[6], SKY_STARS[0], .9f)
    for (k in 0 until count) star(SKY_STARS[k], if (stage == 0) .9f else 1.3f + (if (k == 2) .6f else 0f) + stage * .05f, glow = stage >= 5 || k == 0)
    if (stage >= 10) drawCircle(Color(0x33FFF3C4), 40f, Offset(0f, -2f), style = Stroke(2f))
}

// --- Замок: от камня до королевства ---

private val STONE = Color(0xFF9AA0AA)
private val STONE_DARK = Color(0xFF6C727D)

private fun DrawScope.wall(x: Float, w: Float, h: Float) {
    drawRect(STONE, Offset(x, 40 - h), androidx.compose.ui.geometry.Size(w, h))
    var cx = x
    while (cx < x + w - 2) { drawRect(STONE, Offset(cx, 40 - h - 3), androidx.compose.ui.geometry.Size(3f, 3f)); cx += 6 }
    drawLine(STONE_DARK, Offset(x, 40 - h / 2), Offset(x + w, 40 - h / 2), .7f)
}

private fun DrawScope.tower(x: Float, w: Float, h: Float, flag: Boolean, roof: Boolean = false) {
    drawRect(STONE, Offset(x - w / 2, 40 - h), androidx.compose.ui.geometry.Size(w, h))
    drawRect(STONE_DARK, Offset(x - w / 2, 40 - h), androidx.compose.ui.geometry.Size(w, h), style = Stroke(.7f))
    if (roof) drawPath(svg("M${x - w / 2 - 2} ${40 - h}L$x ${40 - h - w}L${x + w / 2 + 2} ${40 - h}z"), Color(0xFFB0453A))
    else for (k in 0 until 3) drawRect(STONE, Offset(x - w / 2 + k * w / 2.5f, 40 - h - 3), androidx.compose.ui.geometry.Size(w / 5, 3f))
    drawRoundRect(Color(0xFF2A2F3A), Offset(x - 1.5f, 40 - h * .7f), androidx.compose.ui.geometry.Size(3f, 5f), androidx.compose.ui.geometry.CornerRadius(1.5f, 1.5f))
    if (flag) {
        val top = 40 - h - (if (roof) w else 3f)
        drawLine(Color(0xFF5A4636), Offset(x, top), Offset(x, top - 10), 1f)
        drawPath(svg("M$x ${top - 10}l8 2.5-8 2.5z"), Color(0xFFE5484D))
    }
}

private fun DrawScope.house(x: Float) {
    drawRect(Color(0xFFE8D8B8), Offset(x - 4, 34f), androidx.compose.ui.geometry.Size(8f, 6f))
    drawPath(svg("M${x - 5} 34L$x 29L${x + 5} 34z"), Color(0xFFB0453A))
}

private fun DrawScope.castle(stage: Int) {
    when (stage) {
        0 -> drawRoundRect(STONE, Offset(-4f, 35f), androidx.compose.ui.geometry.Size(8f, 5f), androidx.compose.ui.geometry.CornerRadius(1.5f, 1.5f))
        1 -> for (k in 0 until 5) drawRect(if (k % 2 == 0) STONE else STONE_DARK, Offset(-15f + k * 6, 36f), androidx.compose.ui.geometry.Size(6f, 4f))
        2 -> wall(-16f, 32f, 9f)
        3 -> { wall(-18f, 24f, 9f); tower(12f, 10f, 22f, false) }
        4 -> { wall(-12f, 24f, 10f); tower(-16f, 10f, 22f, false); tower(16f, 10f, 22f, false) }
        5 -> { wall(-12f, 24f, 12f); drawPath(svg("M-4 40v-6a4 4 0 0 1 8 0v6z"), Color(0xFF3A2A1A)); tower(-16f, 10f, 24f, false); tower(16f, 10f, 24f, false) }
        6 -> { wall(-12f, 24f, 12f); tower(0f, 12f, 32f, false, roof = true); tower(-17f, 10f, 24f, false); tower(17f, 10f, 24f, false) }
        7 -> { wall(-12f, 24f, 12f); tower(0f, 12f, 32f, true, roof = true); tower(-17f, 10f, 24f, true); tower(17f, 10f, 24f, true) }
        8 -> { wall(-30f, 60f, 12f); tower(0f, 13f, 34f, true, roof = true); tower(-20f, 10f, 26f, true); tower(20f, 10f, 26f, true); tower(-32f, 8f, 18f, false); tower(32f, 8f, 18f, false) }
        else -> {
            wall(-30f, 60f, 12f); tower(0f, 14f, 36f, true, roof = true); tower(-20f, 10f, 27f, true, roof = stage >= 10); tower(20f, 10f, 27f, true, roof = stage >= 10)
            tower(-32f, 8f, 18f, false); tower(32f, 8f, 18f, false)
            listOf(-26f, -14f, 14f, 26f).forEach { house(it) }
            if (stage >= 10) { drawPath(svg("M-7 -10l2-6 3 3 2-5 2 5 3-3 2 6z"), Color(0xFFFFD86B)); drawCircle(Color(0x33FFD86B), 12f, Offset(0f, -12f)) }
        }
    }
}

// --- Коралловый риф: вода, песок, кораллы, рыбки ---

private fun DrawScope.coral(x: Float, s: Float, color: Color) {
    val st = Stroke(1.6f + s * .5f, cap = StrokeCap.Round)
    drawPath(svg("M$x 40v${-6 * s}M$x ${40 - 3 * s}l${-3 * s} ${-3 * s}M$x ${40 - 4 * s}l${3 * s} ${-4 * s}M${x - 3 * s} ${40 - 6 * s}v${-2 * s}M${x + 3 * s} ${40 - 8 * s}v${-1.5f * s}"), color, style = st)
    drawCircle(color, .9f + s * .3f, Offset(x, 40 - 6 * s))
}

private fun DrawScope.fish(x: Float, y: Float, color: Color, left: Boolean = false) {
    withTransform({ translate(x, y); scale(if (left) -1f else 1f, 1f, Offset.Zero) }) {
        drawOval(color, Offset(-4f, -2f), androidx.compose.ui.geometry.Size(8f, 4f))
        drawPath(svg("M-4 0l-3-2.5v5z"), color)
        drawCircle(Color(0xFF16202A), .6f, Offset(2f, -.4f))
    }
}

private fun DrawScope.reef(stage: Int) {
    drawCircle(Brush.verticalGradient(listOf(Color(0x332E7DD6), Color(0x552E7DD6)), -40f, 44f), 42f, Offset(0f, -2f))
    drawOval(Color(0xFFC9A86A), Offset(-34f, 36f), androidx.compose.ui.geometry.Size(68f, 8f))
    val pink = Color(0xFFFF7A93); val orange = Color(0xFFFF9A3D); val violet = Color(0xFFB49CFF)
    when (stage) {
        0 -> drawCircle(pink, 2f, Offset(0f, 38f))
        1 -> coral(0f, 1f, pink)
        2 -> coral(0f, 1.8f, pink)
        3 -> coral(0f, 2.8f, pink)
        4 -> { coral(0f, 3f, pink); fish(14f, 10f, orange) }
        else -> {
            if (stage >= 5) listOf(-26f, 24f).forEach { x -> drawPath(svg("M$x 40q-3-8 0-14t0-14"), Color(0xFF3AA65C), style = Stroke(1.6f, cap = StrokeCap.Round)) }
            coral(0f, 3.2f, pink)
            if (stage >= 6) { coral(-14f, 2.2f, violet); coral(14f, 2.4f, orange) }
            if (stage >= 7) listOf(-8f, 20f).forEach { x -> drawPath(svg("M${x - 3} 40a3 3 0 0 1 6 0z"), Color(0xFFF1E6D0)) }
            if (stage >= 8) { drawOval(Color(0xFF3A8F6A), Offset(-30f, -14f), androidx.compose.ui.geometry.Size(14f, 9f)); drawCircle(Color(0xFF6BC28F), 2.6f, Offset(-14f, -10f)) }
            if (stage >= 9) { coral(-24f, 1.8f, orange); coral(26f, 2f, violet) }
            fish(14f, 6f, orange)
            if (stage >= 6) fish(-12f, -4f, Color(0xFFFFD86B), left = true)
            if (stage >= 10) { fish(18f, -16f, Color(0xFF7FD3FF), left = true); fish(-4f, -22f, pink)
                listOf(Offset(4f, -8f), Offset(6f, -14f), Offset(3f, -20f)).forEach { drawCircle(Color(0x88FFFFFF), 1.2f, it, style = Stroke(.6f)) } }
        }
    }
}
