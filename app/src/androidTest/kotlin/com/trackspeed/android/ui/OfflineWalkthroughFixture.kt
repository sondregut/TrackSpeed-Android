package com.trackspeed.android.ui

import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.trackspeed.android.data.local.TrackSpeedDatabase
import com.trackspeed.android.data.local.entities.RunEntity
import com.trackspeed.android.data.local.entities.TrainingSessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit review fixture, never part of the default test suite or a physical-device run.
 * The caller must keep the task emulator offline for its entire remaining lifetime.
 * Inserts only named synthetic records; never clears or replaces existing user records.
 */
class OfflineWalkthroughFixture {
    @Test fun seedSyntheticSessionForActualNavigation() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("offlineReviewFixture") == "true")
        assertEquals("ranchu", Build.HARDWARE)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(1, Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON))
        assertNull(context.getSystemService(ConnectivityManager::class.java).activeNetwork)
        val db = Room.databaseBuilder(context, TrackSpeedDatabase::class.java, "trackspeed.db").build()
        try {
            val id = "offline-review-20261024-synthetic"
            val time = 1792857600000L
            if (db.trainingSessionDao().getSession(id) == null) {
                db.trainingSessionDao().insert(TrainingSessionEntity(id = id, date = time,
                    name = "SYNTHETIC review session", notes = "Offline emulator UI fixture. Not measured timing.",
                    distance = 30.0, startType = "flying", numberOfPhones = 2,
                    createdAt = time, updatedAt = time))
            }
            listOf(2.93, 3.05).forEachIndexed { index, seconds ->
                val runId = "$id-run-${index + 1}"
                if (db.runDao().getRunById(runId) == null) {
                    db.runDao().insert(RunEntity(id = runId, sessionId = id,
                        athleteName = "Synthetic athlete", runNumber = index + 1,
                        timeSeconds = seconds, distance = 30.0, startType = "flying", numberOfPhones = 2,
                        createdAt = time + index * 60000))
                }
            }
            assertEquals(2, db.trainingSessionDao().getRunCount(id))
        } finally { db.close() }
    }
}
