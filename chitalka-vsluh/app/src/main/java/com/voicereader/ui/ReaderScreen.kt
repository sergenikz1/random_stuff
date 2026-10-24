package com.voicereader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings as GearIcon
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.voicereader.R
import com.voicereader.rewards.Reward
import com.voicereader.rewards.RewardKind
import com.voicereader.rewards.Rewards
import com.voicereader.ui.rewards.DayChip
import com.voicereader.ui.rewards.LiveBackground
import com.voicereader.ui.rewards.MedalRibbon
import com.voicereader.ui.rewards.drawChapterTransition
import com.voicereader.ui.rewards.sentenceHighlight
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.graphicsLayer
import com.voicereader.ui.rewards.ReaderLook
import com.voicereader.ui.rewards.RewardCelebrations
import com.voicereader.ui.rewards.lookOf
import androidx.compose.ui.unit.sp
import com.voicereader.data.MusicMode
import com.voicereader.data.Settings
import com.voicereader.data.TextWindow
import com.voicereader.player.PlayerEngine
import com.voicereader.player.PlayerState
import com.voicereader.tts.SystemTts
import com.voicereader.tts.TtsStatus
import com.voicereader.tts.VoiceKind
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    onBack: () -> Unit,
    onVoices: () -> Unit,
    onSpeedRead: () -> Unit,
    onAwards: (tab: Int) -> Unit,
    onAppearance: () -> Unit
) {
    val rewards by Rewards.state.collectAsState()
    val look = lookOf(rewards)
    val state by PlayerEngine.state.collectAsState()
    val settings by Settings.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showChapters by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    // В режиме «только текст» панель появляется по нажатию на текст и сама прячется.
    var barsVisible by remember { mutableStateOf(false) }
    val focusMode = settings.focusMode
    val context = LocalContext.current

    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn, state.isPlaying) {
        view.keepScreenOn = settings.keepScreenOn && state.isPlaying
        onDispose { view.keepScreenOn = false }
    }
    SystemBarsHidden(focusMode)
    BackHandler(enabled = focusMode) { Settings.update { it.copy(focusMode = false) } }
    LaunchedEffect(focusMode, barsVisible) {
        if (focusMode && barsVisible) { delay(4000); barsVisible = false }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            PlayerEngine.dismissMessage()
        }
    }

    val topBar: @Composable () -> Unit = {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = { PlayerEngine.pause(); onBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            },
            title = {
                Column {
                    Text(
                        state.title.ifBlank { stringResource(R.string.untitled) },
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
                Box(contentAlignment = Alignment.Center) {
                    if (state.preparing) CircularProgressIndicator(Modifier.size(40.dp), strokeWidth = 2.dp)
                    IconButton(onClick = { PlayerEngine.toggle() }, enabled = state.sentences.isNotEmpty()) {
                        Icon(
                            if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(if (state.isPlaying) R.string.pause else R.string.play)
                        )
                    }
                }
                IconButton(onClick = { showChapters = true }) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.contents))
                }
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Filled.GearIcon, contentDescription = stringResource(R.string.settings))
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { if (!focusMode) topBar() }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .then(if (look.background != null) Modifier.background(look.background) else Modifier)
                .padding(if (focusMode) PaddingValues(0.dp) else padding)
                .then(if (focusMode) Modifier.displayCutoutPadding() else Modifier)
        ) {
            look.live?.let { LiveBackground(it, Modifier.matchParentSize()) }
            // В режиме «только текст» лента наград не показывается.
            val ribbon = rewards.ribbonEnabled && !focusMode && state.sentences.isNotEmpty()
            when {
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                state.sentences.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(stringResource(R.string.book_not_loaded), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> BookText(
                    state, settings.fontSizeSp, settings.lineHeightMult, settings.textWindow, settings.autoScroll,
                    look = look,
                    bottomPadding = if (ribbon) 196.dp else 64.dp,
                    onSentenceClick = if (focusMode) { _ -> barsVisible = !barsVisible } else PlayerEngine::seekTo
                )
            }

            if (ribbon) {
                // Плавный переход от текста к ленте, в цвет выбранного фона.
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(170.dp).background(
                        Brush.verticalGradient(listOf(Color.Transparent, (look.backgroundColor ?: MaterialTheme.colorScheme.background).copy(alpha = 0.85f), (look.backgroundColor ?: MaterialTheme.colorScheme.background)))
                    )
                )
                MedalRibbon(rewards, Modifier.align(Alignment.BottomCenter).navigationBarsPadding(), ringFx = look.ring) { onAwards(0) }
            }
            if (!focusMode && state.sentences.isNotEmpty()) {
                DayChip(rewards, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (ribbon) 140.dp else 16.dp))
            }

            // Смена главы: переход из наград.
            look.chapter?.let { fx ->
                val chapterKey = state.chapterTitle
                val k = remember { Animatable(1f) }
                var first by remember { mutableStateOf(true) }
                LaunchedEffect(chapterKey) {
                    if (first) { first = false; return@LaunchedEffect }
                    if (!state.isPlaying) return@LaunchedEffect
                    k.snapTo(0f); k.animateTo(1f, tween(1100, easing = LinearEasing))
                }
                val pageColor = look.backgroundColor ?: MaterialTheme.colorScheme.background
                if (k.value < 1f) Canvas(Modifier.matchParentSize()) { drawChapterTransition(fx, k.value, pageColor) }
            }

            if (focusMode && barsVisible) {
                Box(Modifier.align(Alignment.TopCenter)) { topBar() }
            }

            val ttsStatus by PlayerEngine.systemTtsStatus.collectAsState()
            // Предупреждение о системном движке нужно только тогда, когда читает именно он.
            val usingSystemVoice = settings.activeProfile.kind != VoiceKind.NEURAL ||
                !state.language.startsWith("ru", ignoreCase = true)
            if (usingSystemVoice && ttsStatus != TtsStatus.READY && !state.loading) {
                TtsWarning(
                    status = ttsStatus,
                    onOpenSettings = { context.startActivity(PlayerEngine.ttsSettingsIntent()) },
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp)
                )
            }

            state.installProgress?.let { progress ->
                VoicePreparing(progress, Modifier.align(Alignment.Center))
            }
        }
    }

    RewardCelebrations(onApply = ::applyReward, celebrate = look.celebrate)

    if (showChapters) {
        ModalBottomSheet(onDismissRequest = { showChapters = false }) {
            ChapterList(state) { index ->
                PlayerEngine.seekTo(index)
                showChapters = false
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            ReaderSettings(
                state = state,
                onSpeedRead = { showSettings = false; PlayerEngine.pause(); onSpeedRead() },
                onFocusMode = { showSettings = false; barsVisible = false; Settings.update { it.copy(focusMode = !it.focusMode) } },
                onOpenVoices = { showSettings = false; onVoices() },
                onAwards = { showSettings = false; onAwards(0) },
                onAppearance = { showSettings = false; onAppearance() }
            )
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
    autoScroll: Boolean,
    look: ReaderLook,
    bottomPadding: Dp,
    onSentenceClick: (Int) -> Unit
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
        contentPadding = PaddingValues(18.dp, 12.dp, 18.dp, bottomPadding),
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
                look = look,
                onClick = { onSentenceClick(index) }
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
    look: ReaderLook,
    onClick: () -> Unit
) {
    // Цвет текста из наград: текущее предложение — в полную силу, остальное приглушено.
    val color = when {
        look.text != null -> look.text.copy(alpha = if (isActive) 1f else if (isRead) 0.4f else 0.66f)
        isActive -> ReaderPalette.active
        isRead -> ReaderPalette.dim
        else -> ReaderPalette.upcoming
    }

    val wordColor = if (look.light) Color(0xFF1E5BB8) else ReaderPalette.word
    // «Чернила»: озвучиваемое предложение проявляется.
    val ink = remember(text, isActive) { Animatable(if (isActive && look.highlight == "INK") 0f else 1f) }
    LaunchedEffect(text, isActive) { if (ink.value < 1f) ink.animateTo(1f, tween(700)) }
    val annotated: AnnotatedString = remember(text, wordRange, isActive, wordColor) {
        if (!isActive || wordRange == null) AnnotatedString(text)
        else buildAnnotatedString {
            append(text)
            val start = wordRange.first.coerceIn(0, text.length)
            val end = (wordRange.last + 1).coerceIn(start, text.length)
            if (end > start) {
                addStyle(SpanStyle(color = wordColor, fontWeight = FontWeight.Bold), start, end)
            }
        }
    }

    Text(
        text = annotated,
        color = color,
        fontSize = fontSize.sp,
        lineHeight = (fontSize * lineHeight).sp,
        fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
        fontFamily = look.font,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .graphicsLayer { alpha = .25f + .75f * ink.value }
            .sentenceHighlight(look.highlight, isActive, text, ReaderPalette.activeBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

/** Всё, что раньше было в нижней панели, плюс режимы чтения и вид текста — в одном листе настроек. */
@Composable
private fun ReaderSettings(
    state: PlayerState,
    onSpeedRead: () -> Unit,
    onFocusMode: () -> Unit,
    onOpenVoices: () -> Unit,
    onAwards: () -> Unit,
    onAppearance: () -> Unit
) {
    val settings by Settings.state.collectAsState()
    // Имена системных голосов появляются после загрузки списка движком — подписи обновятся сами.
    SystemTts.voices.collectAsState().value
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp, 0.dp, 20.dp, 28.dp)
    ) {
        Text(stringResource(R.string.settings_position), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        PositionControls(state)

        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.settings_mode), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onSpeedRead, modifier = Modifier.weight(1f), enabled = state.sentences.isNotEmpty()) {
                Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.speed_reading), maxLines = 1)
            }
            FilledTonalButton(onClick = onFocusMode, modifier = Modifier.weight(1f)) {
                Icon(
                    if (settings.focusMode) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(if (settings.focusMode) R.string.focus_mode_off else R.string.focus_mode_on), maxLines = 1)
            }
        }
        Text(
            stringResource(R.string.focus_mode_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )

        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.rewards_section), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onAwards, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.EmojiEvents, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.my_rewards), maxLines = 1)
            }
            FilledTonalButton(onClick = onAppearance, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Palette, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.appearance), maxLines = 1)
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.voice), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            settings.profiles.forEachIndexed { i, profile ->
                FilterChip(
                    selected = i == settings.activeVoice,
                    onClick = { PlayerEngine.selectVoice(i) },
                    label = { Text(SystemTts.labelOf(profile).substringBefore(" ·"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.weight(1f)
                )
            }
            IconButton(onClick = onOpenVoices) {
                Icon(Icons.Filled.RecordVoiceOver, contentDescription = stringResource(R.string.voices))
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.music_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MusicMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.musicMode == mode,
                    onClick = { Settings.update { it.copy(musicMode = mode) }; PlayerEngine.applyMusicMode() },
                    label = { Text(stringResource(mode.label), maxLines = 1) }
                )
            }
        }
        Text(
            stringResource(settings.musicMode.hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(18.dp))
        DisplaySettings()
    }
}

@Composable
private fun PositionControls(state: PlayerState) {
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
        modifier = Modifier.fillMaxWidth(),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
    Spacer(Modifier.height(4.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { PlayerEngine.prevChapter() }) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.prev_chapter))
        }
        FilledTonalIconButton(onClick = { PlayerEngine.prevSentence() }, modifier = Modifier.size(52.dp)) {
            Icon(Icons.Filled.FastRewind, contentDescription = stringResource(R.string.prev_sentence))
        }
        FilledIconButton(onClick = { PlayerEngine.toggle() }, modifier = Modifier.size(60.dp)) {
            Icon(
                if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(if (state.isPlaying) R.string.pause else R.string.play),
                modifier = Modifier.size(32.dp)
            )
        }
        FilledTonalIconButton(onClick = { PlayerEngine.nextSentence() }, modifier = Modifier.size(52.dp)) {
            Icon(Icons.Filled.FastForward, contentDescription = stringResource(R.string.next_sentence))
        }
        IconButton(onClick = { PlayerEngine.nextChapter() }) {
            Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.next_chapter))
        }
    }
}

@Composable
private fun ChapterList(state: PlayerState, onSelect: (Int) -> Unit) {
    val current = state.chapters.indexOfLast { it.startIndex <= state.index }
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.contents_count, state.chapters.size),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp, 4.dp, 16.dp, 8.dp)
        )
        LazyColumn(Modifier.heightIn(max = 460.dp)) {
            items(state.chapters.size) { i ->
                val ch = state.chapters[i]
                ListItem(
                    headlineContent = { Text(ch.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(stringResource(R.string.from_sentence, ch.startIndex + 1)) },
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
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.display_title), style = MaterialTheme.typography.titleMedium)

        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.display_window), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TextWindow.entries.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { w ->
                        FilterChip(
                            selected = settings.textWindow == w,
                            onClick = { Settings.update { s -> s.copy(textWindow = w) } },
                            label = { Text(stringResource(w.label)) }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.display_font_size, settings.fontSizeSp.roundToInt()), style = MaterialTheme.typography.labelLarge)
        Slider(
            value = settings.fontSizeSp,
            onValueChange = { v -> Settings.update { it.copy(fontSizeSp = v) } },
            valueRange = 14f..38f,
            steps = 11
        )

        Text(stringResource(R.string.display_line_height, "%.1f".format(settings.lineHeightMult)), style = MaterialTheme.typography.labelLarge)
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
            Text(stringResource(R.string.display_autoscroll))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = settings.keepScreenOn,
                onCheckedChange = { v -> Settings.update { it.copy(keepScreenOn = v) } }
            )
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.display_keep_screen))
        }
    }
}

@Composable
private fun TtsWarning(status: TtsStatus, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val text = when (status) {
        TtsStatus.NO_ENGINE -> stringResource(R.string.tts_no_engine)
        TtsStatus.NO_VOICE_DATA -> stringResource(R.string.tts_no_voice_data)
        TtsStatus.INITIALIZING -> stringResource(R.string.tts_initializing)
        TtsStatus.READY -> return
    }
    Card(modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall)
            if (status != TtsStatus.INITIALIZING) {
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.tts_open_settings)) }
            }
        }
    }
}

/** Первый запуск голоса: модель распаковывается из ассетов во внутреннюю память. */
@Composable
private fun VoicePreparing(progress: Float, modifier: Modifier = Modifier) {
    Card(modifier.padding(24.dp)) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(stringResource(R.string.voice_preparing), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.voice_preparing_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text("${(progress * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** «Применить» из окна нового звания: включает только что открытую награду. */
fun applyReward(reward: Reward) {
    when (reward.kind) {
        RewardKind.BACKGROUND -> Rewards.edit { it.copy(backgroundId = reward.id) }
        RewardKind.TEXT -> Rewards.edit { it.copy(textId = reward.id) }
        RewardKind.FONT -> reward.font?.let { f -> Rewards.edit { it.copy(font = f) } }
        RewardKind.GROW -> reward.grow?.let { g -> Rewards.edit { it.copy(growKind = g) } }
        RewardKind.ANIM -> reward.anim?.let { k -> Rewards.edit { it.copy(anims = it.anims + (k to reward.id)) } }
        RewardKind.VOICE -> {
            reward.voiceId?.let { id ->
                Settings.updateProfile(Settings.state.value.activeVoice) {
                    it.copy(kind = VoiceKind.NEURAL, neuralVoiceId = id, label = id)
                }
            }
            PlayerEngine.applyVoiceSettingsNow()
        }
    }
}
