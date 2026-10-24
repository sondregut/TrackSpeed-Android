package com.trackspeed.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────
// App theme enum
// ─────────────────────────────────────────────────────

enum class AppTheme(val key: String) {
    MIDNIGHT("midnight"),
    LIGHT("light"),
    DARKGOLD("gold");

    /** Legacy keys remain valid; only the released Light and Dark palettes are visible. */
    val supportedTheme: AppTheme
        get() = if (this == MIDNIGHT) DARKGOLD else this

    companion object {
        val visibleThemes: List<AppTheme> = listOf(LIGHT, DARKGOLD)

        /** Read-only normalization: loading an old preference never rewrites its stored value. */
        fun fromPreference(key: String): AppTheme = when (key) {
            "light", "system" -> LIGHT
            "midnight", "dark", "gold", "darkGold", "athleteMindset" -> DARKGOLD
            else -> DARKGOLD // Preserve Android's existing dark default for absent/unknown values.
        }
    }
}

// ─────────────────────────────────────────────────────
// Per-theme color set
// ─────────────────────────────────────────────────────

data class AppColors(
    val background: Color,
    val surface: Color,
    val gradientTop: Color,
    val gradientBottom: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val border: Color,
    val iconMuted: Color,
    val cardTop: Color,
    val cardBottom: Color,
    val featureCardTop: Color,
    val featureCardBottom: Color,
    val accent: Color,
    val primaryAction: Color,
    val onPrimaryAction: Color,
    val isDark: Boolean
)

// Shared neutral tokens from shipped iOS 1.0.23 (134), AppColors.swift.
// Brand-blue accent is retained for legacy controls with explicit white content;
// neutral action fill/ink are paired separately so callers can migrate safely.
val lightColors = AppColors(
    background = Color(0xFFF4F4F8),
    surface = Color(0xFFFCFCFC),
    gradientTop = Color(0xFFF4F4F8),
    gradientBottom = Color(0xFFF4F4F8),
    textPrimary = Color(0xFF202126),
    textSecondary = Color(0xFF5C5E66),
    textMuted = Color(0xFF6A6C74),
    border = Color(0xFFE7E8EC),
    iconMuted = Color(0xFF5C5E66),
    cardTop = Color(0xFFFCFCFC),
    cardBottom = Color(0xFFFCFCFC),
    featureCardTop = Color(0xFFFCFCFC),
    featureCardBottom = Color(0xFFFCFCFC),
    accent = AccentNavy,
    primaryAction = Color(0xFF202126),
    onPrimaryAction = Color.White,
    isDark = false
)

// The legacy name/key is intentionally retained; the supported palette is charcoal, not gold.
val darkGoldColors = AppColors(
    background = Color(0xFF1F1F24),
    surface = Color(0xFF2E3038),
    gradientTop = Color(0xFF1F1F24),
    gradientBottom = Color(0xFF1D1E22),
    textPrimary = Color(0xFFF4F5F7),
    textSecondary = Color(0xFFA8ABB4),
    textMuted = Color(0xFF979BA5),
    border = Color(0xFF444650),
    iconMuted = Color(0xFFA8ABB4),
    cardTop = Color(0xFF2E3038),
    cardBottom = Color(0xFF2A2C34),
    featureCardTop = Color(0xFF2E3038),
    featureCardBottom = Color(0xFF2A2C34),
    accent = AccentNavy,
    primaryAction = Color(0xFFF4F5F7),
    onPrimaryAction = Color(0xFF1A1A1B),
    isDark = true
)

/** Compatibility alias for older previews/callers; no third visible palette. */
val midnightColors = darkGoldColors

// ─────────────────────────────────────────────────────
// CompositionLocals
// ─────────────────────────────────────────────────────

val LocalAppTheme = staticCompositionLocalOf { AppTheme.MIDNIGHT }
val LocalAppColors = staticCompositionLocalOf { midnightColors }

// ─────────────────────────────────────────────────────
// M3 color schemes — values hardcoded per theme so they
// can be constructed outside of @Composable context.
// ─────────────────────────────────────────────────────

private val LightColorScheme = lightColorScheme(
    primary = lightColors.primaryAction,
    onPrimary = lightColors.onPrimaryAction,
    primaryContainer = Color(0xFFE7E8EC),
    onPrimaryContainer = lightColors.textPrimary,
    secondary = AccentGreen,
    onSecondary = Color.White,
    secondaryContainer = AccentGreenDark,
    onSecondaryContainer = Color.White,
    tertiary = AccentGold,
    background = lightColors.background,
    onBackground = lightColors.textPrimary,
    surface = lightColors.surface,
    onSurface = lightColors.textPrimary,
    surfaceVariant = Color(0xFFF6F7FB),
    onSurfaceVariant = lightColors.textSecondary,
    surfaceTint = Color.Transparent,
    surfaceBright = lightColors.surface,
    surfaceDim = lightColors.background,
    surfaceContainer = lightColors.surface,
    surfaceContainerHigh = lightColors.surface,
    surfaceContainerHighest = Color(0xFFF6F7FB),
    surfaceContainerLow = lightColors.background,
    surfaceContainerLowest = lightColors.surface,
    outline = Color(0xFFDCDDE3),
    outlineVariant = lightColors.border
)

private val DarkGoldColorScheme = darkColorScheme(
    primary = darkGoldColors.primaryAction,
    onPrimary = darkGoldColors.onPrimaryAction,
    primaryContainer = Color(0xFF3C3F49),
    onPrimaryContainer = darkGoldColors.textPrimary,
    secondary = AccentGreen,
    onSecondary = Color.White,
    secondaryContainer = AccentGreenDark,
    onSecondaryContainer = Color.White,
    tertiary = AccentGold,
    background = darkGoldColors.background,
    onBackground = darkGoldColors.textPrimary,
    surface = darkGoldColors.surface,
    onSurface = darkGoldColors.textPrimary,
    surfaceVariant = darkGoldColors.surface,
    onSurfaceVariant = darkGoldColors.textSecondary,
    surfaceTint = Color.Transparent,
    surfaceBright = Color(0xFF3C3F49),
    surfaceDim = darkGoldColors.background,
    surfaceContainer = darkGoldColors.surface,
    surfaceContainerHigh = darkGoldColors.surface,
    surfaceContainerHighest = Color(0xFF3C3F49),
    surfaceContainerLow = darkGoldColors.background,
    surfaceContainerLowest = Color(0xFF16171A),
    outline = darkGoldColors.border,
    outlineVariant = darkGoldColors.border
)

// ─────────────────────────────────────────────────────
// Theme composable
// ─────────────────────────────────────────────────────

@Composable
fun TrackSpeedTheme(
    appTheme: AppTheme = AppTheme.MIDNIGHT,
    content: @Composable () -> Unit
) {
    val supportedTheme = appTheme.supportedTheme
    val (colorScheme, appColors) = when (supportedTheme) {
        AppTheme.LIGHT -> Pair(LightColorScheme, lightColors)
        AppTheme.MIDNIGHT, AppTheme.DARKGOLD -> Pair(DarkGoldColorScheme, darkGoldColors)
    }

    CompositionLocalProvider(
        LocalAppTheme provides supportedTheme,
        LocalAppColors provides appColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

// ─────────────────────────────────────────────────────
// Extension accessor — use MaterialTheme.appColors in
// composables to access theme-adaptive colors directly.
// ─────────────────────────────────────────────────────

val MaterialTheme.appColors: AppColors
    @Composable
    @ReadOnlyComposable
    get() = LocalAppColors.current
