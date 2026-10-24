package com.trackspeed.android.ui.screens.profile

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.trackspeed.android.data.model.FlyingDistance
import com.trackspeed.android.data.model.SportDiscipline
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProfilePersonalBestStoreTest {
    private class MemoryStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        val state = MutableStateFlow(initial)
        var writes = 0
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val next = transform(state.value)
            writes++
            state.value = next
            return next
        }
    }

    @Test fun saveIsAtomicPreservesPrecisionAndDoesNotTouchUnrelatedSettings() = runBlocking {
        val unrelated = stringPreferencesKey("unrelated")
        val oldRetry = stringPreferencesKey("pending_flying_pr_sync")
        val data = MemoryStore(preferencesOf(unrelated to "keep", oldRetry to "10m:0.99"))
        val store = ProfilePersonalBestStore(data)
        val values = ProfilePersonalBestValues(SportDiscipline.MIDDLE_1500M, 210.123456789,
            FlyingDistance.METERS_20, 2.0123456789)
        store.save(values)
        assertEquals(1, data.writes)
        assertEquals(values, store.load())
        assertEquals("1500m", data.state.value[stringPreferencesKey("primary_event")])
        assertEquals("MIDDLE_DISTANCE", data.state.value[stringPreferencesKey("sportCategory")])
        assertEquals("20m", data.state.value[stringPreferencesKey("flying_distance")])
        assertEquals(true, data.state.value[booleanPreferencesKey("pending_profile_sync")])
        assertNull(data.state.value[oldRetry])
        assertEquals("keep", data.state.value[unrelated])
    }

    @Test fun clearRemovesBothRecordsFlyingDistanceAndStaleAutomaticRetry() = runBlocking {
        val data = MemoryStore(preferencesOf(
            doublePreferencesKey("personal_record") to 10.5,
            doublePreferencesKey("flying_pr") to 1.025,
            stringPreferencesKey("flying_distance") to "10m",
            stringPreferencesKey("pending_flying_pr_sync") to "10m:1.025"
        ))
        val store = ProfilePersonalBestStore(data)
        val cleared = ProfilePersonalBestValues(primaryEvent = SportDiscipline.SPRINT_200M)
        store.save(cleared)
        assertEquals(1, data.writes)
        assertEquals(cleared, store.load())
        listOf("personal_record", "flying_pr").forEach { assertNull(data.state.value[doublePreferencesKey(it)]) }
        listOf("flying_distance", "pending_flying_pr_sync").forEach { assertNull(data.state.value[stringPreferencesKey(it)]) }
        assertEquals(true, data.state.value[booleanPreferencesKey("pending_profile_sync")])
    }

    @Test fun invalidSavePerformsNoWriteAndPreservesPreviousRecord() = runBlocking {
        val original = preferencesOf(doublePreferencesKey("personal_record") to 10.5)
        val data = MemoryStore(original)
        val store = ProfilePersonalBestStore(data)
        listOf(
            ProfilePersonalBestValues(personalRecordSeconds = Double.NaN),
            ProfilePersonalBestValues(personalRecordSeconds = 0.0),
            ProfilePersonalBestValues(flyingPrSeconds = 1.1),
            ProfilePersonalBestValues(flyingDistance = FlyingDistance.METERS_30)
        ).forEach { invalid ->
            try { store.save(invalid); fail("Invalid values were saved") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, data.writes)
        assertEquals(original, data.state.value)
    }

    @Test fun olderUploadCannotAcknowledgeANewerManualEdit() = runBlocking {
        val data = MemoryStore()
        val store = ProfilePersonalBestStore(data)
        store.save(ProfilePersonalBestValues(personalRecordSeconds = 10.5))
        val first = store.snapshot()
        store.save(ProfilePersonalBestValues(personalRecordSeconds = 10.25))
        val second = store.snapshot()
        assertNotEquals(first.revision, second.revision)
        assertFalse(store.acknowledgeUploaded(first))
        assertEquals(second, store.snapshot())
        assertEquals(true, data.state.value[booleanPreferencesKey("pending_profile_sync")])
        assertTrue(store.acknowledgeUploaded(second))
        assertNull(store.snapshot().revision)
        assertNull(data.state.value[booleanPreferencesKey("pending_profile_sync")])
        assertEquals(10.25, store.load().personalRecordSeconds!!, 0.0)
    }

    @Test fun defaultUploadCannotClearAManualEditThatArrivedInFlight() = runBlocking {
        val data = MemoryStore()
        val store = ProfilePersonalBestStore(data)
        val defaultUpload = store.snapshot()
        assertNull(defaultUpload.revision)
        store.save(ProfilePersonalBestValues())
        val manualClear = store.snapshot()
        // Values are deliberately equal: the revision still distinguishes intent.
        assertEquals(defaultUpload.values, manualClear.values)
        assertFalse(store.acknowledgeUploaded(defaultUpload))
        assertEquals(manualClear, store.snapshot())
        assertEquals(true, data.state.value[booleanPreferencesKey("pending_profile_sync")])
    }

    @Test fun unchangedValuesWithANewerRevisionStillRemainPending() = runBlocking {
        val data = MemoryStore()
        val store = ProfilePersonalBestStore(data)
        val values = ProfilePersonalBestValues(personalRecordSeconds = 10.5)
        store.save(values)
        val first = store.snapshot()
        store.save(values)
        assertFalse(store.acknowledgeUploaded(first))
        assertNotEquals(first.revision, store.snapshot().revision)
    }

    @Test fun failureRetainsQueueAndAccountTeardownPreventsStaleAcknowledgement() = runBlocking {
        val data = MemoryStore()
        val store = ProfilePersonalBestStore(data)
        store.save(ProfilePersonalBestValues(personalRecordSeconds = 10.5))
        val uploaded = store.snapshot()
        store.retainPendingAfterFailure(uploaded)
        assertEquals(uploaded, store.snapshot())
        assertEquals(true, data.state.value[booleanPreferencesKey("pending_profile_sync")])
        com.trackspeed.android.data.repository.SettingsRepository(data).clearUserScopedFields()
        assertNull(store.snapshot().revision)
        assertFalse(store.acknowledgeUploaded(uploaded))
        store.retainPendingAfterFailure(uploaded)
        assertNull(data.state.value[booleanPreferencesKey("pending_profile_sync")])
        assertEquals(ProfilePersonalBestValues(), store.load())
    }

}
