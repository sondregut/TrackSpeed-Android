package com.trackspeed.android.ui.screens.profile

import androidx.compose.ui.graphics.Color

/** A local success pair for the included-access card, not a global green alias. */
internal data class ProfileAccessColors(val container: Color, val content: Color)

internal fun profileAccessColors(isDark: Boolean): ProfileAccessColors {
    val green = Color(0xFF00E676)
    return ProfileAccessColors(
        // Released iOS 134 successText ink needs a lighter tint on Android's page.
        container = green.copy(alpha = if (isDark) .12f else .08f),
        content = if (isDark) green else Color(0xFF1E7A57)
    )
}
