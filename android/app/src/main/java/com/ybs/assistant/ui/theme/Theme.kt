package com.ybs.assistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AccentBlue = Color(0xFF5B8DEF)

private val DarkColors = darkColorScheme(
    primary = AccentBlue,
    secondary = Color(0xFF8C8C88),
    background = Color(0xFF181818),
    surface = Color(0xFF212121),
    surfaceVariant = Color(0xFF2B2B2B),
    onBackground = Color(0xFFECECEC),
    onSurface = Color(0xFFECECEC),
)

private val LightColors = lightColorScheme(
    primary = AccentBlue,
    secondary = Color(0xFF6B6B68),
    background = Color(0xFFFAF9F6),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF0EEE9),
    onBackground = Color(0xFF1E1E1E),
    onSurface = Color(0xFF1E1E1E),
)

@Composable
fun AIAssistantTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
