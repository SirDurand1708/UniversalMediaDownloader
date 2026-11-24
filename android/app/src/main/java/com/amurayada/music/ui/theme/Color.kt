package com.amurayada.music.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

// Google Play Music inspired vibrant colors
val Orange80 = Color(0xFFFFB74D)
val DeepPurple80 = Color(0xFFB39DDB)
val Teal80 = Color(0xFF80CBC4)

val Orange40 = Color(0xFFFF6F00)
val DeepPurple40 = Color(0xFF5E35B1)
val Teal40 = Color(0xFF00897B)

// Additional accent colors
val Pink = Color(0xFFE91E63)
val Amber = Color(0xFFFFC107)
val Cyan = Color(0xFF00BCD4)

// Surface colors
val SurfaceDark = Color(0xFF121212)
val SurfaceLight = Color(0xFFF5F7FB)

@Immutable
data class AuraAdaptivePalette(
    val isDark: Boolean,
    val cardBg: Color,
    val cardBgActive: Color,
    val border: Color,
    val accent: Color,
    val onAccent: Color,
    val accentGreen: Color,
    val danger: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val chipUnselectedBg: Color,
    val chipUnselectedBorder: Color,
    val trackBg: Color
)

val DarkAuraPalette = AuraAdaptivePalette(
    isDark = true,
    cardBg = Color(0xFF141923),
    cardBgActive = Color(0xFF14242E),
    border = Color(0xFF263248),
    accent = Color(0xFF00E5FF),
    onAccent = Color.Black,
    accentGreen = Color(0xFF00E676),
    danger = Color(0xFFFF5252),
    textPrimary = Color.White,
    textSecondary = Color(0xFF9AA4AF),
    chipUnselectedBg = Color(0xFF141923),
    chipUnselectedBorder = Color(0xFF263248),
    trackBg = Color(0xFF263248)
)

val LightAuraPalette = AuraAdaptivePalette(
    isDark = false,
    cardBg = Color(0xFFFFFFFF),
    cardBgActive = Color(0xFFE0F7FA),
    border = Color(0xFFDDE4EE),
    accent = Color(0xFF0097A7),
    onAccent = Color.White,
    accentGreen = Color(0xFF00A854),
    danger = Color(0xFFD32F2F),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF526071),
    chipUnselectedBg = Color(0xFFF1F5F9),
    chipUnselectedBorder = Color(0xFFCBD5E1),
    trackBg = Color(0xFFE2E8F0)
)

val LocalAuraPalette = staticCompositionLocalOf { DarkAuraPalette }

@Composable
fun rememberAuraPalette(): AuraAdaptivePalette {
    val bg = MaterialTheme.colorScheme.background
    val surface = MaterialTheme.colorScheme.surface
    val isDark = if (bg == Color.Transparent) {
        surface.luminance() < 0.5f
    } else {
        bg.luminance() < 0.5f
    }
    return remember(isDark) {
        if (isDark) DarkAuraPalette else LightAuraPalette
    }
}