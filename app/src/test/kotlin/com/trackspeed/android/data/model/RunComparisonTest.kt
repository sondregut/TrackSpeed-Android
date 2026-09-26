package com.trackspeed.android.data.model

import com.trackspeed.android.data.local.entities.RunEntity
import com.trackspeed.android.data.local.entities.AthleteEntity
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class RunComparisonTest {
    private fun run(time: Double = 2.0) = RunEntity(
        sessionId = "session", runNumber = 1, timeSeconds = time, distance = 20.0,
        startType = "flying", numberOfPhones = 2
    )

    @Test fun bestRequiresSameDistanceAndTimingMethod() {
        assertEquals(1.9, comparableBestTime(listOf(run(), run(1.9))))
        assertNull(comparableBestTime(listOf(run(), run().copy(distance = 10.0))))
        assertNull(comparableBestTime(listOf(run(), run().copy(startType = "touchRelease"))))
        assertNull(comparableBestTime(listOf(run(), run().copy(numberOfPhones = 1))))
        assertEquals(2.0, comparableBestTime(listOf(run(), run().copy(numberOfPhones = 3))))
    }

    @Test fun incompleteUnknownAndWatchBetaRunsAreNotBestEvidence() {
        for (candidate in listOf(run(0.0), run(-1.0), run(Double.NaN), run(Double.POSITIVE_INFINITY),
            run().copy(numberOfPhones = 0), run().copy(distance = 0.0),
            run().copy(cloudSyncPolicy = "localOnlyWatchBeta"))) {
            assertNull(candidate.comparisonKey())
            assertNull(comparableBestTime(listOf(run(), candidate)))
        }
    }

    @Test fun fractionalDistanceBestKeyDoesNotChangeWithPhoneLanguage() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val german = AthleteEntity.prKey(18.3, "inFrame")
            Locale.setDefault(Locale.US)
            assertEquals(german, AthleteEntity.prKey(18.3, "inFrame"))
            assertEquals("inFrame_18.3m", german)
        } finally { Locale.setDefault(previous) }
    }
}
