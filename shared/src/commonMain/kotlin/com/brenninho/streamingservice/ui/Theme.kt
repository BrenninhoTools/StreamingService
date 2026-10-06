package com.brenninho.streamingservice.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Brand colors match the app icon gradient (violet -> blue).
private val Violet = Color(0xFF7C3AED)
private val Blue = Color(0xFF2563EB)

private val LightColors = lightColorScheme(
    primary = Violet,
    secondary = Blue,
    tertiary = Color(0xFFE11D48),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB69CFF),
    secondary = Color(0xFF8AB4FF),
    tertiary = Color(0xFFFF6B8A),
    background = Color(0xFF0F0E17),
    surface = Color(0xFF0F0E17),
)

internal val LiveRed = Color(0xFFE11D48)

@Composable
fun StreamingTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
