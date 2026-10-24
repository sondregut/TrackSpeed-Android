package com.trackspeed.android.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Static accent colors ──────────────────────────────────────────────────────

val AccentNavy = Color(0xFF5C8DB8)
val AccentNavyDark = Color(0xFF4A7A9E)
val AccentNavyLight = Color(0xFF7BA1BB)

val AccentGreen = Color(0xFF22C55E)
val AccentGreenDark = Color(0xFF1DA750)

/** Yellow-gold used for warnings and the M3 tertiary role. */
val AccentGold = Color(0xFFFEAA21)

/** Legacy bronze retained for source compatibility; no longer a visible app theme. */
val GoldPrimary = Color(0xFFC8A880)

// ── Static backgrounds (safe to use in non-composable contexts) ───────────────

val BackgroundDark = Color(0xFF1F1F24)
val BackgroundLight = Color(0xFFF4F4F8)
val SurfaceLight = Color(0xFFFCFCFC)
val OnBackgroundLight = Color(0xFF202126)

// ── Theme-adaptive colors ─────────────────────────────────────────────────────
// All properties below delegate to LocalAppColors.current so they automatically
// reflect the supported Light / Dark palette, including legacy saved theme aliases.
// They may only be read inside a @Composable scope.

val TextPrimary: Color
    @Composable get() = LocalAppColors.current.textPrimary

val TextSecondary: Color
    @Composable get() = LocalAppColors.current.textSecondary

val TextMuted: Color
    @Composable get() = LocalAppColors.current.textMuted

/** Tertiary text level — same as TextMuted, exposed as a distinct semantic name. */
val TextTertiary: Color
    @Composable get() = LocalAppColors.current.textMuted

val OnBackgroundDark: Color
    @Composable get() = LocalAppColors.current.textPrimary

val BackgroundGradientTop: Color
    @Composable get() = LocalAppColors.current.gradientTop

val BackgroundGradientBottom: Color
    @Composable get() = LocalAppColors.current.gradientBottom

val SurfaceDark: Color
    @Composable get() = LocalAppColors.current.surface

val GunmetalCardTop: Color
    @Composable get() = LocalAppColors.current.cardTop

val GunmetalCardBottom: Color
    @Composable get() = LocalAppColors.current.cardBottom

val FeatureCardTop: Color
    @Composable get() = LocalAppColors.current.featureCardTop

val FeatureCardBottom: Color
    @Composable get() = LocalAppColors.current.featureCardBottom

val BorderSubtle: Color
    @Composable get() = LocalAppColors.current.border

val IconMuted: Color
    @Composable get() = LocalAppColors.current.iconMuted

// ── Composable aliases ────────────────────────────────────────────────────────
// These exist to avoid import-alias boilerplate in screen files.

/** Legacy brand accent. New neutral CTAs must pair Material primary with onPrimary. */
val AccentBlue: Color
    @Composable get() = LocalAppColors.current.accent

/** Theme-adaptive surface — used as DropdownMenu / chip container color. */
val CardBackground: Color
    @Composable get() = LocalAppColors.current.surface

/** Theme-adaptive divider color — delegates to border token. */
val DividerColor: Color
    @Composable get() = LocalAppColors.current.border

// ── Timing display (static) ───────────────────────────────────────────────────

val TimerGreen = Color(0xFF00E676)
val TimerRed = Color(0xFFFF5252)
val TimerYellow = Color(0xFFFFD600)

// ── Status banner colors (static) ─────────────────────────────────────────────

val StatusRed = Color(0xFFE53935)
val StatusGreen = Color(0xFF43A047)

// ── Athlete colors (static) ───────────────────────────────────────────────────

val AthleteBlue = Color(0xFF2196F3)
val AthleteRed = Color(0xFFF44336)
val AthleteGreen = Color(0xFF4CAF50)
val AthleteOrange = Color(0xFFFF9800)
val AthletePurple = Color(0xFF9C27B0)
val AthleteTeal = Color(0xFF009688)
val AthletePink = Color(0xFFE91E63)
val AthleteYellow = Color(0xFFFFEB3B)

// ── Legacy aliases ────────────────────────────────────────────────────────────
// Preserved for call-sites outside of the theme migration scope.

/** Theme-adaptive primary accent (same as AccentBlue). */
val Primary: Color
    @Composable get() = LocalAppColors.current.accent

val PrimaryDark = AccentNavyDark
val PrimaryLight = AccentNavyLight

val Secondary = AccentGreen
val SecondaryDark = AccentGreenDark
val Tertiary = AccentGold

/** Theme-adaptive card surface (same as CardBackground). */
val CardDark: Color
    @Composable get() = LocalAppColors.current.surface
