package com.placeholder.hrvbridge.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Design tokens from the HRV Bridge design system (docs/ui-implementation-brief.md). */
@Immutable
data class HrvColors(
    val bg: Color,
    val surface: Color,
    val key: Color,
    val ink: Color,
    val ink2: Color,
    val line: Color,
    val lineStrong: Color,
    /** Recording and uploaded only. Never text. */
    val accent: Color,
    val dark: Boolean,
)

val LightHrvColors = HrvColors(
    bg = Color(0xFFFDFDFC), surface = Color(0xFFFFFFFF), key = Color(0xFFF1F1EF), ink = Color(0xFF1A1A1A),
    ink2 = Color(0xFF5F5F5C), line = Color(0xFFE2E2DF), lineStrong = Color(0xFF8A8A86), accent = Color(0xFFE2561C),
    dark = false,
)

val DarkHrvColors = HrvColors(
    bg = Color(0xFF131312), surface = Color(0xFF1C1C1A), key = Color(0xFF2B2A28), ink = Color(0xFFECEAE4),
    ink2 = Color(0xFFA2A09A), line = Color(0xFF36352F), lineStrong = Color(0xFF74726C), accent = Color(0xFFFF6A2B),
    dark = true,
)

val LocalHrvColors = staticCompositionLocalOf { LightHrvColors }

/** Readout styles outside the Material type scale. All in sp, so they follow font scaling. */
object Readout {
    val hr = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    val timer = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
    val value = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
}

object Space {
    val margin = 24.dp
    val dialToReadout = 36.dp
    val dialToMetrics = 32.dp
    val stepsToDial = 12.dp
    val bottom = 28.dp
}

private val HrvTypography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
        bodyMedium = t.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal),
        bodySmall = t.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
        labelMedium = t.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
    )
}

/** Light and dark follow the system. No dynamic colour. */
@Composable
fun HrvTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) DarkHrvColors else LightHrvColors
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = c.ink, onPrimary = c.bg,
        background = c.bg, onBackground = c.ink,
        surface = c.surface, onSurface = c.ink, onSurfaceVariant = c.ink2,
        surfaceContainer = c.bg, surfaceContainerHigh = c.surface, surfaceContainerHighest = c.surface,
        surfaceContainerLow = c.surface, surfaceContainerLowest = c.surface,
        outlineVariant = c.line, outline = c.lineStrong,
        tertiary = c.accent,
        secondaryContainer = c.key, onSecondaryContainer = c.ink,
    )
    CompositionLocalProvider(LocalHrvColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = HrvTypography, content = content)
    }
}

val hrv: HrvColors
    @Composable get() = LocalHrvColors.current
