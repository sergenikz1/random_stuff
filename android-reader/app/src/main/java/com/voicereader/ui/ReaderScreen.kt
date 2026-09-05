package com.voicereader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicereader.data.Settings
import com.voicereader.data.TextWindow
import com.voicereader.player.PlayerEngine
import com.voicereader.player.PlayerState
import com.voicereader.player.TtsStatus
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(onBack: () -> Unit, onVoices: () -> Unit) {
    val state by PlayerEngine.state.collectAsState()
    val settings by Settings.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showChapters by remember { mutableStateOf(false) }
    var showDisplay by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn, state.isPlaying) {
        view.keepScreenOn = settings.keepScreenOn && state.isPlaying
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            PlayerEngine.dismissMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { PlayerEngine.pause(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                title = {
                    Column {
                        Text(
                            state.title.ifBlank { "Книга" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (state.chapterTitle.isNotBlank()) {
                            Text(
                                state.chapterTitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showChapters = true }) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Оглавление")
                    }
                    IconButton(onClick = { showDisplay = true }) {
                        Icon(Icons.Filled.FormatSize, contentDescription = "Вид текста")
                    }
                }
            )
        },
        bottomBar = {
            ControlPanel(
                state = state,
                activeVoice = settings.activeVoice,
                voiceLabels = settings.profiles.map { it.label },
                onVoice = { PlayerEngine.selectVoice(it) },
                onOpenVoices = onVoices
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                state.sentences.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("Текст книги не загружен", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> BookText(state, settings.fontSizeSp, settings.lineHeightMult, settings.textWindow, settings.autoScroll)
            }

            if (state.tts != TtsStatus.READY && !state.loading) {
                TtsWarning(
                    status = state.tts,
                    onOpenSettings = { context.startActivity(PlayerEngine.ttsSettingsIntent()) },
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp)
                )
            }
        }
    }

    if (showChapters) {
        ModalBottomSheet(onDismissRequest = { showChapters = false }) {
            ChapterList(state) { index ->
                PlayerEngine.seekTo(index)
                showChapters = false
            }
        }
    }

    if (showDisplay) {
        ModalBottomSheet(onDismissRequest = { showDisplay = false }) {
            DisplaySettings()
        }
    }
}

/** Текст книги: озвучиваемое предложение — светлое, остальное приглушено. */
@Composable
private fun BookText(
    state: PlayerState,
    fontSize: Float,
    lineHeight: Float,
    window: TextWindow,
    autoScroll: Boolean
) {
    val size = state.sentences.size
    val from = if (window == TextWindow.ALL) 0
    else (state.index - window.before).coerceAtLeast(0)
    val to = if (window == TextWindow.ALL) size
    else (state.index + window.after + 1).coerceAtMost(size)

    val listState = rememberLazyListState()

    LaunchedEffect(state.index, window, autoScroll) {
        if (!autoScroll) return@LaunchedEffect
        val target = (state.index - from).coerceAtLeast(0)
        val visible = listState.layoutInfo.visibleItemsInfo
        val onScreen = visible.any { it.index == target }
        // Пока предложение на экране, дёргать список не нужно — читать было бы неудобно.
        if (!onScreen || visible.lastOrNull()?.index == target) {
            runCatching { listState.animateScrollToItem(target, -120) }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(to - from) { offset ->
            val index = from + offset
            SentenceItem(
                text = state.sentences[index],
                isActive = index == state.index,
                isRead = index < state.index,
                wordRange = if (index == state.index) state.wordRange else null,
                fontSize = fontSize,
                lineHeight = lineHeight,
                onClick = { PlayerEngine.seekTo(index) }
            )
        }
    }
}

@Composable
private fun SentenceItem(
    text: String,
    isActive: Boolean,
    isRead: Boolean,
    wordRange: IntRange?,
    fontSize: Float,
    lineHeight: Float,
    onClick: () -> Unit
) {
    val color = when {
        isActive -> ReaderPalette.active
        isRead -> ReaderPalette.dim
        else -> ReaderPalette.upcoming
    }

    val annotated: AnnotatedString = remember(text, wordRange, isActive) {
        if (!isActive || wordRange == null) AnnotatedString(text)
        else buildAnnotatedString {
            append(text)
            val start = wordRange.first.coerceIn(0, text.length)
            val end = (wordRange.last + 1).coerceIn(start, text.length)
            if (end > start) {
                addStyle(SpanStyle(color = ReaderPalette.word, fontWeight = FontWeight.Bold), start, end)
            }
        }
    }

    Text(
        text = annotated,
        color = color,
        fontSize = fontSize.sp,
        lineHeight = (fontSize * lineHeight).sp,
        fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(if (isActive) Modifier.background(ReaderPalette.activeBackground) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun ControlPanel(
    state: PlayerState,
    activeVoice: Int,
    voiceLabels: List<String>,
    onVoice: (Int) -> Unit,
    onOpenVoices: () -> Unit
) {
    Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {

            var dragging by remember { mutableStateOf(false) }
            var dragValue by remember { mutableFloatStateOf(0f) }
            val maxIndex = (state.sentences.size - 1).coerceAtLeast(0)
            val value = if (dragging) dragValue else state.index.toFloat()

            Slider(
                value = value.coerceIn(0f, maxIndex.toFloat()),
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = {
                    dragging = false
                    PlayerEngine.seekTo(dragValue.roundToInt())
                },
                valueRange = 0f..maxIndex.toFloat().coerceAtLeast(1f),
                enabled = state.sentences.isNotEmpty()
            )
            Text(
                "${value.roundToInt() + 1} / ${state.sentences.size}" +
                    if (state.sentences.isEmpty()) "" else "  ·  ${((value / maxIndex.coerceAtLeast(1)) * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { PlayerEngine.prevChapter() }) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Предыдущая глава")
                }
                FilledTonalIconButton(
                    onClick = { PlayerEngine.prevSentence() },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(Icons.Filled.FastRewind, contentDescription = "Предложение назад")
                }
                FilledIconButton(
                    onClick = { PlayerEngine.toggle() },
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "Пауза" else "Читать",
                        modifier = Modifier.size(36.dp)
                    )
                }
                FilledTonalIconButton(
                    onClick = { PlayerEngine.nextSentence() },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(Icons.Filled.FastForward, contentDescription = "Предложение вперёд")
                }
                IconButton(onClick = { PlayerEngine.nextChapter() }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Следующая глава")
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                voiceLabels.forEachIndexed { i, label ->
                    FilterChip(
                        selected = i == activeVoice,
                        onClick = { onVoice(i) },
                        label = {
                            Text(
                                label.substringBefore(" ·"),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                IconButton(onClick = onOpenVoices) {
                    Icon(Icons.Filled.FormatSize, contentDescription = "Настройка голосов")
                }
            }
        }
    }
}

@Composable
private fun ChapterList(state: PlayerState, onSelect: (Int) -> Unit) {
    val current = state.chapters.indexOfLast { it.startIndex <= state.index }
    Column(Modifier.fillMaxWidth()) {
        Text(
            "Оглавление · ${state.chapters.size}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp, 4.dp, 16.dp, 8.dp)
        )
        LazyColumn(Modifier.heightIn(max = 460.dp)) {
            items(state.chapters.size) { i ->
                val ch = state.chapters[i]
                ListItem(
                    headlineContent = { Text(ch.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("с предложения ${ch.startIndex + 1}") },
                    colors = if (i == current)
                        ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    else ListItemDefaults.colors(),
                    modifier = Modifier.clickable { onSelect(ch.startIndex) }
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun DisplaySettings() {
    val settings by Settings.state.collectAsState()
    Column(Modifier.fillMaxWidth().padding(20.dp, 4.dp, 20.dp, 28.dp)) {
        Text("Текст на экране", style = MaterialTheme.typography.titleMedium)

        Spacer(Modifier.height(14.dp))
        Text("Сколько текста показывать", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TextWindow.entries.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { w ->
                        FilterChip(
                            selected = settings.textWindow == w,
                            onClick = { Settings.update { s -> s.copy(textWindow = w) } },
                            label = { Text(w.label) }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Размер шрифта: ${settings.fontSizeSp.roundToInt()}", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = settings.fontSizeSp,
            onValueChange = { v -> Settings.update { it.copy(fontSizeSp = v) } },
            valueRange = 14f..38f,
            steps = 11
        )

        Text("Межстрочный интервал: ${"%.1f".format(settings.lineHeightMult)}", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = settings.lineHeightMult,
            onValueChange = { v -> Settings.update { it.copy(lineHeightMult = v) } },
            valueRange = 1.1f..2.2f,
            steps = 10
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = settings.autoScroll,
                onCheckedChange = { v -> Settings.update { it.copy(autoScroll = v) } }
            )
            Spacer(Modifier.width(12.dp))
            Text("Автопрокрутка за голосом")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = settings.keepScreenOn,
                onCheckedChange = { v -> Settings.update { it.copy(keepScreenOn = v) } }
            )
            Spacer(Modifier.width(12.dp))
            Text("Не гасить экран при чтении")
        }
    }
}

@Composable
private fun TtsWarning(status: TtsStatus, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val text = when (status) {
        TtsStatus.NO_ENGINE -> "Не найден движок синтеза речи. Установите его в настройках системы."
        TtsStatus.NO_VOICE_DATA -> "Для этого языка не загружены офлайн-голоса. Откройте настройки синтеза речи и скачайте пакет языка."
        TtsStatus.INITIALIZING -> "Инициализация синтеза речи…"
        TtsStatus.READY -> return
    }
    Card(modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall)
            if (status != TtsStatus.INITIALIZING) {
                TextButton(onClick = onOpenSettings) { Text("Открыть настройки речи") }
            }
        }
    }
}
