package com.andymitch.dzr.ui.theme

import com.andymitch.dzr.presentation.viewmodel.ColorSchemePair

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

fun accentColorScheme(accent: Color): ColorSchemePair {
    val neutral = generateMonochromeColorSchemeFromSeed(accent)
    fun ColorScheme.withAccent(container: Color, onContainer: Color) = copy(
        primary = accent, onPrimary = Color.White,
        tertiary = accent, onTertiary = Color.White,
        primaryContainer = container, onPrimaryContainer = onContainer,
        tertiaryContainer = container, onTertiaryContainer = onContainer
    )
    return ColorSchemePair(
        light = neutral.light.withAccent(lerp(accent, Color.White, 0.75f), Color.Black),
        dark = neutral.dark.withAccent(lerp(accent, Color.Black, 0.45f), Color.White)
    )
}

fun ColorScheme.pureBlack() = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0B0B),
    surfaceContainer = Color(0xFF121212),
    surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = Color(0xFF222222),
    surfaceBright = Color(0xFF262626)
)
