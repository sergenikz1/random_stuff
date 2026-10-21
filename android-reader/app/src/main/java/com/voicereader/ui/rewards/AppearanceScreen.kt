package com.voicereader.ui.rewards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicereader.R
import com.voicereader.data.Settings
import com.voicereader.player.PlayerEngine
import com.voicereader.rewards.GrowKind
import com.voicereader.rewards.RanksCatalog
import com.voicereader.rewards.Rarity
import com.voicereader.rewards.ReaderFont
import com.voicereader.rewards.Reward
import com.voicereader.rewards.RewardKind
import com.voicereader.rewards.Rewards
import com.voicereader.rewards.VoiceManner
import com.voicereader.tts.VoiceCatalog
import com.voicereader.tts.VoiceKind

private val Surface = Color(0xFF171A20)
private val Muted = Color(0xFFB4BCCB)
private val Primary = Color(0xFF8AB4F8)

fun Modifier.clickableNoRipple(onClick: () -> Unit) = composed {
    clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val state by Rewards.state.collectAsState()
    val settings by Settings.state.collectAsState()
    val player by PlayerEngine.state.collectAsState()
    val look = lookOf(state)
    val rewards = RanksCatalog.REWARDS
    fun of(kind: RewardKind) = rewards.filter { it.kind == kind }
    fun count(kind: RewardKind) = of(kind).count { state.unlocked(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = {
                    Column {
                        Text(stringResource(R.string.appearance))
                        Text(stringResource(R.string.appearance_sub), fontSize = 11.sp, color = Muted)
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
            // Предпросмотр страницы с выбранным оформлением
            Column(
                Modifier
                    .padding(horizontal = 14.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(look.background ?: Brush.linearGradient(listOf(Color(0xFF101216), Color(0xFF101216))))
                    .border(1.dp, Color(0xFF2E3542), RoundedCornerShape(20.dp))
                    .padding(16.dp, 12.dp)
            ) {
                Text(stringResource(R.string.preview).uppercase(), fontSize = 11.sp, letterSpacing = 1.5.sp, color = Muted.copy(alpha = 0.7f))
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.preview_text), color = look.text ?: Color(0xFFE6EAF2), fontFamily = look.font, fontSize = 17.sp, lineHeight = 25.sp)
            }

            Section(stringResource(R.string.kind_background), "${count(RewardKind.BACKGROUND)} / ${of(RewardKind.BACKGROUND).size}")
            TileRow {
                item { Tile(stringResource(R.string.default_option), null, state.backgroundId == null, true, { Rewards.edit { it.copy(backgroundId = null) } }) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF101216))) } }
                of(RewardKind.BACKGROUND).forEach { r ->
                    item { Tile(stringResource(r.title), r.rarity, state.backgroundId == r.id, state.unlocked(r), { Rewards.edit { it.copy(backgroundId = r.id) } }) {
                        Box(Modifier.fillMaxSize().background(rewardBrush(r))) } }
                }
            }

            Section(stringResource(R.string.kind_text), "${count(RewardKind.TEXT)} / ${of(RewardKind.TEXT).size}")
            TileRow {
                item { Tile(stringResource(R.string.text_white), null, state.textId == null, true, { Rewards.edit { it.copy(textId = null) } }) { Sample(Color(0xFFE6EAF2)) } }
                of(RewardKind.TEXT).forEach { r ->
                    item { Tile(stringResource(r.title), r.rarity, state.textId == r.id, state.unlocked(r), { Rewards.edit { it.copy(textId = r.id) } }) { Sample(Color(r.color)) } }
                }
            }

            Section(stringResource(R.string.kind_font), "${count(RewardKind.FONT)} / ${of(RewardKind.FONT).size}")
            TileRow {
                item { Tile(stringResource(R.string.font_default), null, state.font == ReaderFont.DEFAULT, true, { Rewards.edit { it.copy(font = ReaderFont.DEFAULT) } }) { Sample(Color(0xFFE6EAF2), dark = false) } }
                of(RewardKind.FONT).forEach { r ->
                    val f = r.font ?: ReaderFont.DEFAULT
                    item { Tile(stringResource(r.title), r.rarity, state.font == f, state.unlocked(r), { Rewards.edit { it.copy(font = f) } }) {
                        Sample(Color(0xFFE6EAF2), dark = false, font = f) } }
                }
            }

            Section(stringResource(R.string.kind_voice), stringResource(R.string.voice_listen_hint))
            val active = settings.activeProfile
            TileRow {
                VoiceCatalog.neural.forEach { voice ->
                    val reward = rewards.firstOrNull { it.voiceId == voice.id }
                    val unlocked = reward == null || state.unlocked(reward)
                    val selected = active.kind == VoiceKind.NEURAL && active.neuralVoiceId == voice.id
                    item {
                        Tile(stringResource(voice.titleRes), reward?.rarity, selected, unlocked, {
                            Settings.updateProfile(settings.activeVoice) { p -> p.copy(kind = VoiceKind.NEURAL, neuralVoiceId = voice.id, label = voice.id) }
                            PlayerEngine.applyVoiceSettingsNow()
                        }, onPlay = if (unlocked) ({ PlayerEngine.previewVoice(voice.id) }) else null) {
                            Box(Modifier.fillMaxSize().background(if (reward?.rarity == Rarity.LEGENDARY) Color(0xFF3A2A10) else Color(0xFF1B2029)), contentAlignment = Alignment.Center) {
                                VoiceWave(if (reward?.rarity == Rarity.LEGENDARY) Color(0xFFFFB340) else Primary, Modifier.size(60.dp, 28.dp))
                            }
                        }
                    }
                }
            }
            player.installProgress?.let {
                LinearProgressIndicator(progress = { it }, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp).fillMaxWidth())
            }
            Text(stringResource(R.string.manner_title), fontSize = 13.sp, color = Muted, modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 4.dp))
            TileRow {
                VoiceManner.entries.forEach { manner ->
                    val reward = rewards.firstOrNull { it.manner == manner }
                    val unlocked = reward == null || state.unlocked(reward)
                    item {
                        Tile(stringResource(manner.title), reward?.rarity, state.manner == manner, unlocked, {
                            Rewards.edit { it.copy(manner = manner) }; PlayerEngine.applyVoiceSettingsNow()
                        }, onPlay = if (unlocked) ({ PlayerEngine.previewVoice(active.neuralVoiceId, manner) }) else null) {
                            Box(Modifier.fillMaxSize().background(Color(0xFF272042)), contentAlignment = Alignment.Center) {
                                Text(when (manner) { VoiceManner.NORMAL -> "1×"; VoiceManner.STORYTELLER -> "0.9× …"; VoiceManner.ANNOUNCER -> "1.1×" }, color = Color(0xFFE8DEFF), fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }

            Section(stringResource(R.string.kind_grow_long), "")
            TileRow {
                GrowKind.entries.forEach { kind ->
                    val reward = rewards.firstOrNull { it.grow == kind }
                    item {
                        Tile(stringResource(kind.title), reward?.rarity, state.growKind == kind, state.unlocked(kind), { Rewards.edit { it.copy(growKind = kind) } }) {
                            Box(Modifier.fillMaxSize().background(Color(0xFF16201A)), contentAlignment = Alignment.Center) { GrowthArt(kind, 5, Modifier.size(56.dp)) }
                        }
                    }
                }
            }

            Section(stringResource(R.string.rewards_settings), "")
            ToggleRow(stringResource(R.string.toggle_ribbon), state.ribbonEnabled) { v -> Rewards.edit { it.copy(ribbonEnabled = v) } }
            ToggleRow(stringResource(R.string.toggle_daily), state.dailyEnabled) { v -> Rewards.edit { it.copy(dailyEnabled = v) } }
            ToggleRow(stringResource(R.string.toggle_celebrations), state.celebrations) { v -> Rewards.edit { it.copy(celebrations = v) } }
            Text(stringResource(R.string.rewards_privacy), fontSize = 12.sp, color = Muted, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun Section(title: String, hint: String) {
    Row(Modifier.fillMaxWidth().padding(14.dp, 16.dp, 14.dp, 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(hint, fontSize = 11.sp, color = Muted)
    }
}

@Composable
private fun TileRow(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
private fun Sample(color: Color, dark: Boolean = true, font: ReaderFont = ReaderFont.DEFAULT) {
    Box(Modifier.fillMaxSize().background(if (dark) Color(0xFF101216) else Color(0xFF1B2029)), contentAlignment = Alignment.Center) {
        Text("Аа", color = color, fontSize = 22.sp, fontWeight = if (dark) FontWeight.Bold else FontWeight.Normal, fontFamily = fontFamily(font))
    }
}

@Composable
private fun Tile(
    label: String, rarity: Rarity?, selected: Boolean, unlocked: Boolean, onSelect: () -> Unit,
    onPlay: (() -> Unit)? = null, swatch: @Composable () -> Unit
) {
    val border = when {
        selected -> Primary
        rarity == Rarity.LEGENDARY -> Color(0xFFB07A1E)
        rarity == Rarity.RARE -> Color(0xFF5B47A8)
        else -> Color.Transparent
    }
    Box(
        Modifier
            .width(100.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Surface)
            .border(if (selected) 2.dp else 1.5.dp, border, RoundedCornerShape(16.dp))
            .clickableNoRipple { if (unlocked) onSelect() }
            .padding(8.dp)
    ) {
        Column(Modifier.alpha(if (unlocked) 1f else 0.45f), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(11.dp))) { swatch() }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (rarity != null) Box(Modifier.size(6.dp).clip(CircleShape).background(rarityColor(rarity)))
                if (rarity != null) Spacer(Modifier.width(4.dp))
                Text(label, fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center, maxLines = 2)
            }
        }
        when {
            selected -> Box(Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircleShape).background(Primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, null, tint = Color(0xFF0B1220), modifier = Modifier.size(14.dp))
            }
            !unlocked -> Icon(Icons.Filled.Lock, stringResource(R.string.locked), tint = Color(0xFF9AA3B2), modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(14.dp))
        }
        if (onPlay != null) Box(
            Modifier.align(Alignment.TopStart).padding(top = 34.dp).size(24.dp).clip(CircleShape).background(Primary).clickable(onClick = onPlay),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.PlayArrow, stringResource(R.string.listen), tint = Color(0xFF0B1220), modifier = Modifier.size(16.dp)) }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 14.sp)
    }
}
