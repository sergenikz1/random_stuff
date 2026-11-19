package com.voicereader.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.voicereader.R
import com.voicereader.data.Settings
import com.voicereader.tts.VoiceCatalog
import com.voicereader.tts.VoiceKind
import com.voicereader.tts.VoiceVolume
import kotlin.math.roundToInt

@Composable
fun VoiceVolumeSlider(language: String) {
    val settings by Settings.state.collectAsState()
    val profile = settings.activeProfile
    val voice = VoiceCatalog.byId(profile.neuralVoiceId)
    val neural = profile.kind == VoiceKind.NEURAL && voice != null && VoiceCatalog.matchesLanguage(voice, language)
    val max = if (neural) VoiceVolume.MAX else VoiceVolume.SYSTEM_MAX
    val volume = settings.voiceVolume.coerceAtMost(max)
    val label = stringResource(R.string.voice_volume, (100 * volume).roundToInt())
    Spacer(Modifier.height(12.dp))
    Text(label, style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = volume,
        onValueChange = { value -> Settings.update { it.copy(voiceVolume = (value * 100).roundToInt() / 100f) } },
        valueRange = 0f..max,
        modifier = Modifier.semantics { contentDescription = label }
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("0%", style = MaterialTheme.typography.bodySmall)
        Text("${(max * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
    }
    Text(
        stringResource(if (neural) R.string.voice_volume_hint_neural else R.string.voice_volume_hint_system),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
