package com.trackspeed.android.ui.screens.race

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunIdentityRegistryTest {
    @Test
    fun aliasesResolveTransitivelyAfterCanonicalIdentityChanges() {
        val registry = RunIdentityRegistry()

        registry.registerAlias("cloud-start", "provisional")
        registry.registerAlias("provisional", "canonical-finish")

        assertEquals("canonical-finish", registry.resolve("cloud-start"))
        assertEquals("canonical-finish", registry.resolve("provisional"))
        assertTrue(registry.isSameRun("cloud-start", "canonical-finish"))
    }

    @Test
    fun aCycleCannotTrapResolution() {
        val registry = RunIdentityRegistry()

        registry.registerAlias("first", "second")
        registry.registerAlias("second", "first")

        assertEquals(registry.resolve("first"), registry.resolve("second"))
    }
}
