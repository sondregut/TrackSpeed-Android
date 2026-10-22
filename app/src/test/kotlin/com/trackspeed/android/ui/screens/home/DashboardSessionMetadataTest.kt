package com.trackspeed.android.ui.screens.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardSessionMetadataTest {
    @Test fun soloLapPersistenceUsesLapLabels() {
        assertTrue(DashboardSessionMetadata.usesLapLabels(numberOfPhones = 1, numberOfGates = 1))
    }

    @Test fun standingOnePhoneSprintUsesRunLabels() {
        // Standing 5m/10m/20m layouts retain their virtual start + finish/split gates.
        for (gates in listOf(2, 3, 4)) {
            assertFalse(DashboardSessionMetadata.usesLapLabels(numberOfPhones = 1, numberOfGates = gates))
        }
    }

    @Test fun flyingOnePhoneSprintUsesRunLabels() {
        for (gates in 2..5) {
            assertFalse(DashboardSessionMetadata.usesLapLabels(numberOfPhones = 1, numberOfGates = gates))
        }
    }

    @Test fun onePhoneShuttleAndAgilityUseRunLabels() {
        assertFalse(DashboardSessionMetadata.usesLapLabels(numberOfPhones = 1, numberOfGates = 2))
        assertFalse(DashboardSessionMetadata.usesLapLabels(numberOfPhones = 1, numberOfGates = 3))
    }

    @Test fun connectedSessionsNeverBecomeSoloLaps() {
        for (phones in 2..4) for (gates in 1..4) {
            assertFalse(DashboardSessionMetadata.usesLapLabels(numberOfPhones = phones, numberOfGates = gates))
        }
    }

    @Test fun missingOrInvalidCountsDoNotClaimLapSemantics() {
        for ((phones, gates) in listOf(1 to 0, 1 to -1, 0 to 1, -1 to 1)) {
            assertFalse(DashboardSessionMetadata.usesLapLabels(numberOfPhones = phones, numberOfGates = gates))
        }
    }
}
