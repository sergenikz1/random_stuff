package com.voicereader.ui

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.voicereader.R
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Android даёт обычным приложениям управление общей громкостью мультимедиа. */
@Composable
fun MusicVolumeSlider() {
    val context = LocalContext.current
    val audio = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    var volume by remember { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    LaunchedEffect(audio, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                delay(400)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    val label = stringResource(R.string.music_volume, (100f * volume / max).roundToInt())
    Text(label, style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = volume.toFloat(),
        onValueChange = { value ->
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, value.roundToInt().coerceIn(0, max), 0)
            volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        },
        valueRange = 0f..max.toFloat(),
        steps = max - 1,
        enabled = !audio.isVolumeFixed,
        modifier = Modifier.semantics { contentDescription = label }
    )
    Text(
        stringResource(R.string.music_volume_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
