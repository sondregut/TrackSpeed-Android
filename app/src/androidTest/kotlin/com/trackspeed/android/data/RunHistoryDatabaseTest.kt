package com.trackspeed.android.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trackspeed.android.data.local.TrackSpeedDatabase
import com.trackspeed.android.data.local.entities.RunEntity
import com.trackspeed.android.data.local.entities.TrainingSessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RunHistoryDatabaseTest {
    private lateinit var database: TrackSpeedDatabase

    @Before fun open() {
        database = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
            TrackSpeedDatabase::class.java).build()
        runBlocking { database.trainingSessionDao().insert(TrainingSessionEntity(
            id = "test", date = 1L, distance = 20.0, startType = "flying"
        )) }
    }

    @After fun close() = database.close()

    private fun run(id: String, time: Double, startType: String = "flying", phones: Int = 2,
                    athlete: String? = null) = RunEntity(id = id, sessionId = "test", runNumber = 1,
        timeSeconds = time, distance = 20.0, startType = startType, numberOfPhones = phones,
        athleteId = athlete)

    @Test fun bestQueriesSeparateMethodAndAthleteAndRejectInvalidTiming() = runBlocking {
        val dao = database.runDao()
        listOf(run("flying", 2.0), run("touch", 1.0, "touchRelease"), run("solo", .8, phones = 1),
            run("other-athlete", .5, athlete = "other"), run("invalid", 0.0)).forEach { dao.insert(it) }
        assertEquals(2.0, dao.getPersonalBest(20.0, "flying", 2, null))
        assertEquals(1.0, dao.getSeasonBest(20.0, 0L, "touchRelease", 2, null))
        assertEquals(.5, dao.getPersonalBest(20.0, "flying", 2, "other"))
        assertNull(dao.getSessionSummaries().first().single().bestTime)
        assertNull(dao.getGlobalBestTime().first())
    }

    @Test fun repeatedRunsPersistAndDeleteWithoutLeavingFalseBest() = runBlocking {
        val dao = database.runDao()
        repeat(50) { index -> dao.insert(run("run-$index", 2.0 + index / 100.0).copy(runNumber = index + 1)) }
        assertEquals(50, dao.getSessionSummaries().first().single().runCount)
        assertEquals(2.0, dao.getSessionSummaries().first().single().bestTime)
        dao.deleteById("run-0")
        assertEquals(2.01, dao.getPersonalBest(20.0, "flying", 2, null))
    }

    @Test fun watchBetaAndUnknownCloudTopologyNeverPolluteBests() = runBlocking {
        val dao = database.runDao()
        dao.insert(run("gate", 2.0))
        dao.insert(run("watch", .5).copy(cloudSyncPolicy = "localOnlyWatchBeta"))
        dao.insert(run("unknown", .3, phones = 0))
        assertEquals(2.0, dao.getPersonalBest(20.0, "flying", 2, null))
        assertNull(dao.getSessionSummaries().first().single().bestTime)
    }

    @Test fun changingDistanceClearsPreviouslyAwardedBadges() = runBlocking {
        val dao = database.runDao()
        dao.insert(run("edit", 2.0).copy(isPersonalBest = true, isSeasonBest = true))
        dao.updateDistance("edit", 30.0)
        val updated = dao.getRunById("edit")!!
        assertFalse(updated.isPersonalBest)
        assertFalse(updated.isSeasonBest)
        assertEquals(30.0, updated.distance, 0.0)
    }
}
