package com.trackspeed.android.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class AppThemePreferenceTest {
    @Test fun legacyDarkPreferencesResolveToTheVisibleDarkChoice() {
        listOf("midnight", "dark", "gold", "darkGold", "athleteMindset").forEach { savedKey ->
            assertEquals(savedKey, AppTheme.DARKGOLD, AppTheme.fromPreference(savedKey))
        }
        assertEquals(AppTheme.DARKGOLD, AppTheme.MIDNIGHT.supportedTheme)
    }

    @Test fun lightAndLegacySystemResolveToLight() {
        assertEquals(AppTheme.LIGHT, AppTheme.fromPreference("light"))
        assertEquals(AppTheme.LIGHT, AppTheme.fromPreference("system"))
    }

    @Test fun supportedSelectionsRoundTripWithoutRenamingExistingStorageKeys() {
        assertEquals(listOf(AppTheme.LIGHT, AppTheme.DARKGOLD), AppTheme.visibleThemes)
        assertEquals("midnight", AppTheme.MIDNIGHT.key)
        assertEquals("gold", AppTheme.DARKGOLD.key)
        AppTheme.visibleThemes.forEach { selected ->
            assertEquals(selected, AppTheme.fromPreference(selected.key))
        }
    }

    @Test fun unknownAndAbsentPreferencesKeepAndroidsExistingDarkFallback() {
        listOf("", "unknown-future-theme").forEach { savedKey ->
            assertEquals(AppTheme.MIDNIGHT.supportedTheme, AppTheme.fromPreference(savedKey))
        }
    }
}
