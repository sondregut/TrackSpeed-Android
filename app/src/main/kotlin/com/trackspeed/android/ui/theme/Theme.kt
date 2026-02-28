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
    DARKGOLD("gold")
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
    val isDark: Boolean
)

val midnightColors = AppColors(
    background = Color(0xFF191919),
    surface = Color(0xFF252525),
    gradientTop = Color(0xFF1A1A1A),
    gradientBottom = Color(0xFF0A0A0A),
    textPrimary = Color(0xFFFDFDFD),
    textSecondary = Color(0xFF9B9A97),
    textMuted = Color(0xFF787774),
    border = Color(0xFF3D3D3D),
    iconMuted = Color(0xFF7BA1BB),
    cardTop = Color(0xFF1F1F1F),
    cardBottom = Color(0xFF1A1A1A),
    featureCardTop = Color(0xFF302A53),
    featureCardBottom = Color(0xFF234353),
    accent = Color(0xFF5C8DB8),
    isDark = true
)

val lightColors = AppColors(
    background = Color(0xFFF5F5F7),
    surface = Color(0xFFFFFFFF),
    gradientTop = Color(0xFFF5F5F7),
    gradientBottom = Color(0xFFEEEEEF),
    textPrimary = Color(0xFF1D1312),
    textSecondary = Color(0xFF8E8C8B),
    textMuted = Color(0xFFABABAB),
    border = Color(0xFFE8E8EA),
    iconMuted = Color(0xFF8E8C8B),
    cardTop = Color(0xFFFFFFFF),
    cardBottom = Color(0xFFF7F7F9),
    featureCardTop = Color(0xFFEEF2F7),
    featureCardBottom = Color(0xFFE3EAF5),
    accent = Color(0xFF5C8DB8),
    isDark = false
)

val darkGoldColors = AppColors(
    background = Color(0xFF1E1E1F),
    surface = Color(0xFF2A2A2C),
    gradientTop = Color(0xFF1A1A1B),
    gradientBottom = Color(0xFF141415),
    textPrimary = Color(0xFFEDE9E4),
    textSecondary = Color(0xFFC9C1B9),
    textMuted = Color(0xFF9E948B),
    border = Color(0xFF51443A),
    iconMuted = Color(0xFFA18973),
    cardTop = Color(0xFF2A2A2C),
    cardBottom = Color(0xFF242426),
    featureCardTop = Color(0xFF3A2E20),
    featureCardBottom = Color(0xFF2A2016),
    accent = GoldPrimary,
    isDark = true
)

// ─────────────────────────────────────────────────────
// CompositionLocals
// ─────────────────────────────────────────────────────

val LocalAppTheme = staticCompositionLocalOf { AppTheme.MIDNIGHT }
val LocalAppColors = staticCompositionLocalOf { midnightColors }

// ─────────────────────────────────────────────────────
// M3 color schemes — values hardcoded per theme so they
// can be constructed outside of @Composable context.
// ─────────────────────────────────────────────────────

private val MidnightColorScheme = darkColorScheme(
    primary = Color(0xFF5C8DB8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF4A7A9E),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = AccentGreen,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = AccentGreenDark,
    onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = AccentGold,
    background = Color(0xFF191919),
    onBackground = Color(0xFFFDFDFD),
    surface = Color(0xFF252525),
    onSurface = Color(0xFFFDFDFD),
    surfaceVariant = Color(0xFF252525),
    onSurfaceVariant = Color(0xFF9B9A97),
    outline = Color(0xFF3D3D3D),
    outlineVariant = Color(0xFF3D3D3D)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF5C8DB8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF7BA1BB),
    onPrimaryContainer = Color(0xFF1D1312),
    secondary = AccentGreen,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = AccentGreenDark,
    onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = AccentGold,
    background = Color(0xFFF5F5F7),
    onBackground = Color(0xFF1D1312),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1D1312),
    surfaceVariant = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF8E8C8B),
    outline = Color(0xFFE8E8EA),
    outlineVariant = Color(0xFFE8E8EA)
)

private val DarkGoldColorScheme = darkColorScheme(
    primary = GoldPrimary,
    onPrimary = Color(0xFF1E1E1F),
    primaryContainer = Color(0xFFA18973),
    onPrimaryContainer = Color(0xFF1E1E1F),
    secondary = AccentGreen,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = AccentGreenDark,
    onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = AccentGold,
    background = Color(0xFF1E1E1F),
    onBackground = Color(0xFFEDE9E4),
    surface = Color(0xFF2A2A2C),
    onSurface = Color(0xFFEDE9E4),
    surfaceVariant = Color(0xFF2A2A2C),
    onSurfaceVariant = Color(0xFFC9C1B9),
    outline = Color(0xFF51443A),
    outlineVariant = Color(0xFF51443A)
)

// ─────────────────────────────────────────────────────
// Theme composable
// ─────────────────────────────────────────────────────

@Composable
fun TrackSpeedTheme(
    appTheme: AppTheme = AppTheme.MIDNIGHT,
    content: @Composable () -> Unit
) {
    val (colorScheme, appColors) = when (appTheme) {
        AppTheme.MIDNIGHT -> Pair(MidnightColorScheme, midnightColors)
        AppTheme.LIGHT -> Pair(LightColorScheme, lightColors)
        AppTheme.DARKGOLD -> Pair(DarkGoldColorScheme, darkGoldColors)
    }

    CompositionLocalProvider(
        LocalAppTheme provides appTheme,
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
