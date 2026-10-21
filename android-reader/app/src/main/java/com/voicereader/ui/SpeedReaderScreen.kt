package com.voicereader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicereader.data.Settings
import com.voicereader.player.PlayerEngine
import com.voicereader.text.SpeedWords
import kotlinx.coroutines.delay

private val SpeedBackground = Color(0xFF000000)
private val SpeedGuide = Color(0xFF3A3A3A)
private val SpeedWord = Color(0xFFF2F2F2)
private val SpeedPivot = Color(0xFFE5484D)
private val SpeedLabel = Color(0xFF8A8A8A)

/** Доля ширины экрана, где стоит метка опорной буквы — чуть левее центра. */
private const val FOCUS_X = 0.4f

/** Заставка новой главы: 2 с проявление, 1 с на экране, 2 с исчезновение. */
private const val BANNER_FADE_MS = 2000
private const val BANNER_HOLD_MS = 1000
private const val BANNER_TOTAL_MS = BANNER_FADE_MS * 2 + BANNER_HOLD_MS

/**
 * Быстрое чтение: слова книги по одному в фиксированной точке, опорная буква — красная,
 * взгляд не бегает по строке. Нажатие на экран — пауза/продолжить.
 */
@Composable
fun SpeedReaderScreen(onBack: () -> Unit) {
    val state by PlayerEngine.state.collectAsState()
    val settings by Settings.state.collectAsState()

    var sentence by remember { mutableIntStateOf(state.index) }
    var word by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var shownSinceStart by remember { mutableIntStateOf(0) }
    // Название главы, которое сейчас проявляется на месте слова; null — заставки нет.
    var banner by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { PlayerEngine.pause() }
    BackHandler { playing = false; onBack() }
    SystemBarsHidden(true)
    val view = LocalView.current
    DisposableEffect(playing) {
        view.keepScreenOn = playing
        onDispose { view.keepScreenOn = false }
    }

    val words = remember(state.sentences, sentence) {
        SpeedWords.split(state.sentences.getOrElse(sentence) { "" })
    }
    val current = words.getOrElse(word) { "" }
    val wpm = if (settings.speedRamp) SpeedWords.rampedWpm(settings.speedWpm, shownSinceStart) else settings.speedWpm

    fun moveToSentence(target: Int) {
        sentence = target.coerceIn(0, (state.sentences.size - 1).coerceAtLeast(0))
        word = 0
        banner = null
        PlayerEngine.seekTo(sentence)
    }

    /** Переход к следующему предложению по ходу чтения: на границе главы — заставка с её названием. */
    fun advanceTo(next: Int) {
        moveToSentence(next)
        banner = state.chapters.firstOrNull { it.startIndex == next && next > 0 }?.title
    }

    LaunchedEffect(playing, sentence, word, banner) {
        if (!playing) return@LaunchedEffect
        if (banner != null) {
            delay(BANNER_TOTAL_MS.toLong())
            banner = null
            return@LaunchedEffect
        }
        if (words.isEmpty()) {
            if (sentence + 1 < state.sentences.size) advanceTo(sentence + 1) else playing = false
            return@LaunchedEffect
        }
        delay(SpeedWords.durationMs(current, wpm, endOfSentence = word == words.size - 1))
        shownSinceStart++
        when {
            word + 1 < words.size -> word++
            sentence + 1 < state.sentences.size -> advanceTo(sentence + 1)
            else -> playing = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(SpeedBackground)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (state.sentences.isNotEmpty()) {
                    if (!playing) shownSinceStart = 0
                    playing = !playing
                }
            }
    ) {
        Column(
            Modifier.fillMaxWidth().align(Alignment.Center).offset(y = (-60).dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) {
                WordFrame(if (banner != null) "" else current, Modifier.fillMaxSize())
                banner?.let { title ->
                    // Пауза снимает заставку с экрана, после продолжения она проиграется заново.
                    ChapterBanner(title, animate = playing)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "$wpm слов/мин",
                color = SpeedLabel,
                fontSize = 14.sp,
                fontStyle = FontStyle.Italic
            )
        }

        if (!playing) {
            PausedControls(
                title = state.title,
                sentenceText = state.sentences.getOrElse(sentence) { "" },
                position = "${sentence + 1} / ${state.sentences.size}",
                wpm = settings.speedWpm,
                ramp = settings.speedRamp,
                onBack = onBack,
                onPlay = { if (state.sentences.isNotEmpty()) { shownSinceStart = 0; playing = true } },
                onPrev = { moveToSentence(if (word > 0) sentence else sentence - 1) },
                onNext = { moveToSentence(sentence + 1) },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** Название новой главы: плавно проявляется, держится и так же плавно гаснет. */
@Composable
private fun ChapterBanner(title: String, animate: Boolean) {
    val alpha = remember(title, animate) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(title, animate) {
        if (!animate) return@LaunchedEffect
        alpha.animateTo(1f, tween(BANNER_FADE_MS, easing = LinearEasing))
        delay(BANNER_HOLD_MS.toLong())
        alpha.animateTo(0f, tween(BANNER_FADE_MS, easing = LinearEasing))
    }
    Text(
        title,
        color = SpeedWord,
        fontSize = 26.sp,
        fontFamily = FontFamily.Serif,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).alpha(alpha.value)
    )
}

/** Две горизонтальные направляющие с метками и слово, выровненное опорной буквой по метке. */
@Composable
private fun WordFrame(word: String, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val pivot = SpeedWords.pivot(word)
    Canvas(modifier) {
        val stroke = 2.dp.toPx()
        val tick = 12.dp.toPx()
        val top = stroke / 2
        val bottom = size.height - stroke / 2
        val focusX = size.width * FOCUS_X
        drawLine(SpeedGuide, Offset(0f, top), Offset(size.width, top), stroke)
        drawLine(SpeedGuide, Offset(0f, bottom), Offset(size.width, bottom), stroke)
        drawLine(SpeedGuide, Offset(focusX, top), Offset(focusX, top + tick), stroke)
        drawLine(SpeedGuide, Offset(focusX, bottom), Offset(focusX, bottom - tick), stroke)
        if (word.isEmpty()) return@Canvas

        val text = buildAnnotatedString {
            append(word)
            if (pivot < word.length) addStyle(SpanStyle(color = SpeedPivot), pivot, pivot + 1)
        }
        val margin = 16.dp.toPx()
        var fontSize = 40f
        var layout = measurer.measure(text, TextStyle(color = SpeedWord, fontSize = fontSize.sp, fontFamily = FontFamily.Serif), softWrap = false)
        // Длинное слово уменьшаем, чтобы оно целиком помещалось в строку.
        while (layout.size.width > size.width - 2 * margin && fontSize > 18f) {
            fontSize -= 2f
            layout = measurer.measure(text, TextStyle(color = SpeedWord, fontSize = fontSize.sp, fontFamily = FontFamily.Serif), softWrap = false)
        }
        val box = layout.getBoundingBox(pivot.coerceIn(0, word.length - 1))
        var x = focusX - box.center.x
        x = x.coerceAtMost(size.width - margin - layout.size.width).coerceAtLeast(margin)
        val y = (size.height - layout.size.height) / 2
        drawText(layout, topLeft = Offset(x, y))
    }
}

@Composable
private fun PausedControls(
    title: String,
    sentenceText: String,
    position: String,
    wpm: Int,
    ramp: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.systemBarsPadding().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = SpeedWord)
            }
            Column(Modifier.weight(1f)) {
                Text(title.ifBlank { "Книга" }, color = SpeedWord, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium)
                Text("Быстрое чтение · $position", color = SpeedLabel, style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            sentenceText,
            color = SpeedLabel,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Spacer(Modifier.height(12.dp))
        Surface(color = Color(0xFF141414), shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Скорость", color = SpeedWord, modifier = Modifier.weight(1f))
                    IconButton(onClick = { setSpeed(wpm - 25) }) {
                        Icon(Icons.Filled.Remove, contentDescription = "Медленнее", tint = SpeedWord)
                    }
                    Text("$wpm слов/мин", color = SpeedWord, fontWeight = FontWeight.Medium)
                    IconButton(onClick = { setSpeed(wpm + 25) }) {
                        Icon(Icons.Filled.Add, contentDescription = "Быстрее", tint = SpeedWord)
                    }
                }
                Slider(
                    value = wpm.toFloat(),
                    onValueChange = { setSpeed(it.toInt()) },
                    valueRange = Settings.SPEED_MIN.toFloat()..Settings.SPEED_MAX.toFloat(),
                    steps = (Settings.SPEED_MAX - Settings.SPEED_MIN) / 25 - 1
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = ramp, onCheckedChange = { v -> Settings.update { it.copy(speedRamp = v) } })
                    Spacer(Modifier.width(12.dp))
                    Text("Плавный разгон с половины скорости", color = SpeedWord, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(onClick = onPrev, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Filled.FastRewind, contentDescription = "Предложение назад")
                    }
                    FilledIconButton(onClick = onPlay, modifier = Modifier.size(64.dp)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Читать", modifier = Modifier.size(34.dp))
                    }
                    FilledTonalIconButton(onClick = onNext, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Filled.FastForward, contentDescription = "Предложение вперёд")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Нажмите на экран, чтобы поставить на паузу или продолжить",
                    color = SpeedLabel,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}

private fun setSpeed(value: Int) {
    val rounded = (value / 25) * 25
    Settings.update { it.copy(speedWpm = rounded.coerceIn(Settings.SPEED_MIN, Settings.SPEED_MAX)) }
}
