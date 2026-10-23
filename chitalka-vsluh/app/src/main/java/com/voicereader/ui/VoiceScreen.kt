package com.voicereader.ui

import androidx.compose.ui.res.stringResource
import com.voicereader.R
import com.voicereader.rewards.RanksCatalog
import com.voicereader.rewards.Rewards

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
    val rewards by Rewards.state.collectAsState()
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                title = { Text(stringResource(R.string.voices)) }
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
                                { Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.active)) }
                            } else null,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            item {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.voice_slot, editing + 1, SystemTts.labelOf(profile)), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            when (profile.kind) {
                                VoiceKind.NEURAL -> VoiceCatalog.byId(profile.neuralVoiceId)?.let { stringResource(it.descriptionRes) }
                                    ?: stringResource(R.string.voice_builtin_model)
                                VoiceKind.SYSTEM -> profile.systemVoiceName?.let { name ->
                                    systemVoices.firstOrNull { it.name == name }
                                        ?.let { stringResource(R.string.voice_system_named, it.displayName) } ?: name
                                } ?: stringResource(R.string.voice_system_default)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(14.dp))
                        Text(stringResource(R.string.voice_speed, "%.2f".format(profile.rate)), style = MaterialTheme.typography.labelLarge)
                        Slider(
                            value = profile.rate,
                            onValueChange = { v -> Settings.updateProfile(editing) { it.copy(rate = v) } },
                            onValueChangeFinished = { PlayerEngine.applyVoiceSettingsNow() },
                            valueRange = 0.5f..2.0f,
                            steps = 29
                        )

                        if (profile.kind == VoiceKind.SYSTEM) {
                            Text(stringResource(R.string.voice_pitch, "%.2f".format(profile.pitch)), style = MaterialTheme.typography.labelLarge)
                            Slider(
                                value = profile.pitch,
                                onValueChange = { v -> Settings.updateProfile(editing) { it.copy(pitch = v) } },
                                onValueChangeFinished = { PlayerEngine.applyVoiceSettingsNow() },
                                valueRange = 0.6f..1.6f,
                                steps = 19
                            )
                        } else {
                            Text(
                                stringResource(R.string.voice_pitch_neural),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { PlayerEngine.selectVoice(editing) },
                            enabled = editing != settings.activeVoice
                        ) {
                            Text(stringResource(if (editing == settings.activeVoice) R.string.voice_already_active else R.string.voice_make_active))
                        }
                    }
                }
            }

            item {
                Text(
                    stringResource(R.string.voices_builtin),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.voices_builtin_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // «Сима» — награда за звание: до него голос в списке не показывается.
            val available = VoiceCatalog.neural.filter { v ->
                RanksCatalog.REWARDS.none { it.voiceId == v.id && !rewards.unlocked(it) }
            }
            items(available.size) { i ->
                val voice = available[i]
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
                                label = voice.id,
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
                    stringResource(R.string.voices_system),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.voices_system_hint),
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
                                stringResource(R.string.voices_system_none),
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(onClick = { context.startActivity(PlayerEngine.ttsSettingsIntent()) }) {
                                Text(stringResource(R.string.tts_settings))
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
                    Text(stringResource(R.string.tts_settings_system))
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
                    Text(stringResource(voice.titleRes), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(voice.descriptionRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (ready) stringResource(R.string.voice_ready) else stringResource(R.string.voice_unpack_size, voice.approxBytes / 1_000_000),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (selected) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.selected))
                else if (!ready) Icon(Icons.Filled.Download, contentDescription = null)
            }

            if (installing) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.voice_unpacking, (progress * 100).roundToInt()),
                    style = MaterialTheme.typography.labelSmall
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!ready) {
                        TextButton(onClick = onInstall) { Text(stringResource(R.string.voice_prepare)) }
                    } else {
                        TextButton(onClick = onDelete) { Text(stringResource(R.string.voice_free_space)) }
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
                stringResource(R.string.voice_system_row, voice.locale, voice.displayName, voice.quality),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        trailingContent = { if (selected) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.selected)) },
        colors = if (selected)
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        else ListItemDefaults.colors(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick)
    )
}
