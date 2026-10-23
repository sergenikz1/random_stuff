package com.voicereader.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF0B1220),
    secondary = Color(0xFF9FD3C7),
    background = Color(0xFF101216),
    onBackground = Color(0xFFE6EAF2),
    surface = Color(0xFF171A20),
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF1E222B),
    onSurfaceVariant = Color(0xFFB4BCCB),
    outline = Color(0xFF39404E),
    error = Color(0xFFF2B8B5)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2C5FA8),
    background = Color(0xFFFAF8F4),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDEBE6)
)

/** Цвета текста в читалке: прочитанное приглушено, озвучиваемое — светлое. */
object ReaderPalette {
    val dim = Color(0xFF5C6577)
    val upcoming = Color(0xFF97A1B4)
    val active = Color(0xFFFFFFFF)
    val activeBackground = Color(0x1F8AB4F8)
    val word = Color(0xFFAFCBFF)
}

@Composable
fun VoiceReaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else DarkColors,
        content = content
    )
}
