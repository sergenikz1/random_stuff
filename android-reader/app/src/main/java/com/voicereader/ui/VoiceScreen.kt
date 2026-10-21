package com.voicereader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voicereader.data.Settings
import com.voicereader.player.PlayerEngine
import com.voicereader.tts.InstalledVoice
import com.voicereader.tts.NeuralVoice
import com.voicereader.tts.SystemTts
import com.voicereader.tts.VoiceCatalog
import com.voicereader.tts.VoiceInstaller
import com.voicereader.tts.VoiceKind
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Три голосовых профиля. Каждый — либо встроенная нейросетевая модель,
 * либо голос системного движка (нужен для книг не на русском).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceScreen(onBack: () -> Unit) {
    val settings by Settings.state.collectAsState()
    val systemVoices by SystemTts.voices.collectAsState()
    val playerState by PlayerEngine.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var editing by remember { mutableStateOf(settings.activeVoice) }
    var installing by remember { mutableStateOf<String?>(null) }
    var installProgress by remember { mutableFloatStateOf(0f) }
    // Меняется после установки/удаления голоса, чтобы список перечитал состояние с диска.
    var installTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { SystemTts.refreshVoices() }

    val profile = settings.profiles[editing]

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                title = { Text("Голоса") }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    settings.profiles.forEachIndexed { i, p ->
                        FilterChip(
                            selected = editing == i,
                            onClick = { editing = i },
                            label = {
                                Text(
                                    SystemTts.labelOf(p),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingIcon = if (i == settings.activeVoice) {
                                { Icon(Icons.Filled.Check, contentDescription = "Активный") }
                            } else null,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            item {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text("Слот ${editing + 1}: ${SystemTts.labelOf(profile)}", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            when (profile.kind) {
                                VoiceKind.NEURAL -> VoiceCatalog.byId(profile.neuralVoiceId)?.description
                                    ?: "встроенная нейросетевая модель"
                                VoiceKind.SYSTEM -> profile.systemVoiceName?.let { name ->
                                    systemVoices.firstOrNull { it.name == name }
                                        ?.let { "системный голос · ${it.displayName}" } ?: name
                                } ?: "голос системного движка по умолчанию"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(14.dp))
                        Text("Скорость: ${"%.2f".format(profile.rate)}×", style = MaterialTheme.typography.labelLarge)
                        Slider(
                            value = profile.rate,
                            onValueChange = { v -> Settings.updateProfile(editing) { it.copy(rate = v) } },
                            onValueChangeFinished = { PlayerEngine.applyVoiceSettingsNow() },
                            valueRange = 0.5f..2.0f,
                            steps = 29
                        )

                        if (profile.kind == VoiceKind.SYSTEM) {
                            Text("Высота тона: ${"%.2f".format(profile.pitch)}", style = MaterialTheme.typography.labelLarge)
                            Slider(
                                value = profile.pitch,
                                onValueChange = { v -> Settings.updateProfile(editing) { it.copy(pitch = v) } },
                                onValueChangeFinished = { PlayerEngine.applyVoiceSettingsNow() },
                                valueRange = 0.6f..1.6f,
                                steps = 19
                            )
                        } else {
                            Text(
                                "Высота тона у нейросетевых голосов не регулируется — тембр задан самой моделью.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { PlayerEngine.selectVoice(editing) },
                            enabled = editing != settings.activeVoice
                        ) {
                            Text(if (editing == settings.activeVoice) "Уже активен" else "Сделать активным")
                        }
                    }
                }
            }

            item {
                Text(
                    "Встроенные голоса",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Работают полностью офлайн, звучат естественнее системного движка. " +
                        "Рассчитаны на русский текст.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(VoiceCatalog.neural.size) { i ->
                val voice = VoiceCatalog.neural[i]
                val ready = remember(installTick, voice.id) { VoiceInstaller.isVoiceReady(context, voice) }
                NeuralVoiceRow(
                    voice = voice,
                    selected = profile.kind == VoiceKind.NEURAL && profile.neuralVoiceId == voice.id,
                    ready = ready,
                    installing = installing == voice.id,
                    progress = installProgress,
                    onSelect = {
                        Settings.updateProfile(editing) {
                            it.copy(
                                label = voice.title,
                                kind = VoiceKind.NEURAL,
                                neuralVoiceId = voice.id
                            )
                        }
                        PlayerEngine.applyVoiceSettingsNow()
                    },
                    onInstall = {
                        installing = voice.id
                        installProgress = 0f
                        scope.launch {
                            runCatching {
                                VoiceInstaller.installVoice(context, voice) { installProgress = it }
                            }
                            installing = null
                            installTick++
                        }
                    },
                    onDelete = {
                        VoiceInstaller.uninstallVoice(context, voice)
                        installTick++
                    }
                )
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Системные голоса",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Нужны для книг на других языках. Показаны только те, что не требуют интернета.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val relevant = SystemTts.voicesFor(playerState.language)
            if (relevant.isEmpty()) {
                item {
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "Офлайн-голоса системного движка не найдены. Их можно скачать " +
                                    "в системных настройках синтеза речи.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(onClick = { context.startActivity(PlayerEngine.ttsSettingsIntent()) }) {
                                Text("Настройки синтеза речи")
                            }
                        }
                    }
                }
            }

            items(relevant.size) { i ->
                val voice = relevant[i]
                SystemVoiceRow(
                    voice = voice,
                    selected = profile.kind == VoiceKind.SYSTEM && profile.systemVoiceName == voice.name,
                    onClick = {
                        Settings.updateProfile(editing) {
                            it.copy(
                                label = voice.title,
                                kind = VoiceKind.SYSTEM,
                                systemVoiceName = voice.name
                            )
                        }
                        PlayerEngine.applyVoiceSettingsNow()
                    }
                )
            }

            item {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { context.startActivity(PlayerEngine.ttsSettingsIntent()) }) {
                    Text("Системные настройки синтеза речи")
                }
            }
        }
    }
}

@Composable
private fun NeuralVoiceRow(
    voice: NeuralVoice,
    selected: Boolean,
    ready: Boolean,
    installing: Boolean,
    progress: Float,
    onSelect: () -> Unit,
    onInstall: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = if (selected)
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        else CardDefaults.cardColors()
    ) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(voice.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        voice.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (ready) "готов к работе" else "${voice.approxBytes / 1_000_000} МБ · распакуется при первом запуске",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (selected) Icon(Icons.Filled.Check, contentDescription = "Выбран")
                else if (!ready) Icon(Icons.Filled.Download, contentDescription = null)
            }

            if (installing) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    "распаковка ${(progress * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!ready) {
                        TextButton(onClick = onInstall) { Text("Подготовить сейчас") }
                    } else {
                        TextButton(onClick = onDelete) { Text("Освободить место") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SystemVoiceRow(voice: InstalledVoice, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(voice.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                "${voice.locale} · ${voice.displayName} · качество ${voice.quality} · офлайн",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        trailingContent = { if (selected) Icon(Icons.Filled.Check, contentDescription = "Выбран") },
        colors = if (selected)
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        else ListItemDefaults.colors(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick)
    )
}
