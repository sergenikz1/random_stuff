package com.voicereader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
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

/**
 * Три голосовых профиля. Каждый — системный офлайн-голос плюс своя высота и скорость,
 * поэтому выбор из трёх есть даже на устройствах с единственным установленным голосом.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceScreen(onBack: () -> Unit) {
    val settings by Settings.state.collectAsState()
    val allVoices by PlayerEngine.voices.collectAsState()
    val context = LocalContext.current
    var editing by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) { PlayerEngine.refreshVoices() }

    val voices = remember(allVoices, PlayerEngine.state.value.language) {
        PlayerEngine.voicesForCurrentBook()
    }

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
                            label = { Text("Голос ${i + 1}", maxLines = 1) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            item {
                val profile = settings.profiles[editing]
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text(profile.label, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            profile.voiceName?.let { name ->
                                voices.firstOrNull { it.name == name }?.displayName ?: name
                            } ?: "Голос движка по умолчанию",
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

                        Text("Высота тона: ${"%.2f".format(profile.pitch)}", style = MaterialTheme.typography.labelLarge)
                        Slider(
                            value = profile.pitch,
                            onValueChange = { v -> Settings.updateProfile(editing) { it.copy(pitch = v) } },
                            onValueChangeFinished = { PlayerEngine.applyVoiceSettingsNow() },
                            valueRange = 0.6f..1.6f,
                            steps = 19
                        )

                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                PlayerEngine.selectVoice(editing)
                            }) { Text("Сделать активным") }
                            OutlinedButton(onClick = {
                                Settings.updateProfile(editing) {
                                    it.copy(pitch = 1f, rate = 1f)
                                }
                                PlayerEngine.applyVoiceSettingsNow()
                            }) { Text("Сбросить") }
                        }
                    }
                }
            }

            item {
                Text(
                    "Системный голос для профиля «Голос ${editing + 1}»",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (voices.isEmpty()) {
                item {
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "Офлайн-голоса не найдены. Откройте системные настройки синтеза речи " +
                                    "и скачайте языковой пакет — после этого голоса появятся здесь.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(onClick = { context.startActivity(PlayerEngine.ttsSettingsIntent()) }) {
                                Text("Настройки синтеза речи")
                            }
                        }
                    }
                }
            }

            items(voices.size) { i ->
                val voice = voices[i]
                VoiceRow(
                    voice = voice,
                    selected = settings.profiles[editing].voiceName == voice.name,
                    onClick = {
                        Settings.updateProfile(editing) { it.copy(voiceName = voice.name) }
                        PlayerEngine.applyVoiceSettingsNow()
                    }
                )
            }

            item {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { context.startActivity(PlayerEngine.ttsSettingsIntent()) }) {
                    Text("Системные настройки синтеза речи")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Показаны только голоса, работающие без интернета. Качество звучания зависит от " +
                        "движка: у Google TTS лучшие результаты дают голоса с пометкой высокого качества.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun VoiceRow(voice: InstalledVoice, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(voice.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text("${voice.locale} · качество ${voice.quality} · офлайн")
        },
        trailingContent = {
            if (selected) Icon(Icons.Filled.Check, contentDescription = "Выбран")
        },
        colors = if (selected)
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        else ListItemDefaults.colors(),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
    )
}
