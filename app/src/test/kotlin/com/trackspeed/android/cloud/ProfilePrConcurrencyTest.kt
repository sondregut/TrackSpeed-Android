package com.trackspeed.android.cloud

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import com.trackspeed.android.data.model.FlyingDistance
import com.trackspeed.android.data.model.SportDiscipline
import com.trackspeed.android.data.repository.SettingsRepository
import com.trackspeed.android.ui.screens.profile.FlyingPrSyncSnapshot
import com.trackspeed.android.ui.screens.profile.ProfilePersonalBestSnapshot
import com.trackspeed.android.ui.screens.profile.ProfilePersonalBestStore
import com.trackspeed.android.ui.screens.profile.ProfilePersonalBestValues
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Actual queue/store and shared writer guard, with fake request completions.
 * No Supabase client, production DataStore, Android device, or network is used.
 */
class ProfilePrConcurrencyTest {
    private class MemoryStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        private val mutex = Mutex()
        val state = MutableStateFlow(initial)
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(state.value).also { state.value = it } }
    }

    private class Fixture(initial: Preferences = emptyPreferences()) {
        val data = MemoryStore(initial)
        val store = ProfilePersonalBestStore(data)
        val writer = Mutex()
        var account: String? = "account-a"
        var timing = false
        var remoteFlyingPr: Double? = null
        var writes = 0

        suspend fun measured(time: Double = 1.05, distance: FlyingDistance = FlyingDistance.METERS_10): FlyingPrSyncSnapshot {
            val capturedAccount = account
            return store.recordMeasuredFlyingPr(distance, time, capturedAccount) { account == capturedAccount }!!
        }

        suspend fun automatic(ticket: FlyingPrSyncSnapshot, response: suspend () -> Boolean = { true }): Boolean {
            val userId = ticket.accountId ?: return false
            val succeeded = writer.withProfileWriteLock(false, userId, { account }, canWrite = {
                !timing && store.isCurrentFlyingPr(ticket) && !timing
            }) {
                writes++
                response().also { if (it) remoteFlyingPr = ticket.timeSeconds }
            }
            // Same durable-before-send policy as SessionRepository: no reinsert on failure.
            return succeeded && store.acknowledgeFlyingPr(ticket)
        }

        suspend fun manual(
            snapshot: ProfilePersonalBestSnapshot,
            response: suspend () -> Boolean = { true }
        ): Boolean {
            val userId = account ?: return false
            val succeeded = writer.withProfileWriteLock(true, userId, { account }, canWrite = {
                !timing && store.isCurrent(snapshot) && !timing
            }) {
                writes++
                response().also { if (it) remoteFlyingPr = snapshot.values.flyingPrSeconds }
            }
            if (account != userId) return false
            if (succeeded) return store.acknowledgeUploaded(snapshot)
            store.retainPendingAfterFailure(snapshot)
            return false
        }
    }

    @Test fun failedAutomaticCompletionCannotReinsertRetryAfterManualClear() = runBlocking {
        withTimeout(2_000) {
            val f = Fixture()
            val ticket = f.measured()
            val release = CompletableDeferred<Boolean>()
            val automatic = async(start = CoroutineStart.UNDISPATCHED) { f.automatic(ticket) { release.await() } }
            assertEquals(1, f.writes)
            f.store.save(ProfilePersonalBestValues())
            val cleared = f.store.snapshot()
            val manual = async(start = CoroutineStart.UNDISPATCHED) { f.manual(cleared) }
            assertFalse(manual.isCompleted)
            release.complete(false)
            assertFalse(automatic.await())
            assertTrue(manual.await())
            assertNull(f.remoteFlyingPr)
            assertNull(f.store.pendingFlyingPr("account-a"))
            assertFalse(f.store.isCurrentFlyingPr(ticket))
            assertFalse(f.automatic(ticket))
            assertEquals(2, f.writes)
        }
    }

    @Test fun retryReadBeforeManualClearIsRejectedJustBeforeItsWrite() = runBlocking {
        val f = Fixture()
        f.measured()
        val captured = f.store.pendingFlyingPr("account-a")!!
        f.store.save(ProfilePersonalBestValues())
        assertTrue(f.manual(f.store.snapshot()))
        assertFalse(f.automatic(captured))
        assertEquals(1, f.writes)
        assertNull(f.remoteFlyingPr)
        assertNull(f.store.pendingFlyingPr("account-a"))
    }

    @Test fun successfulInFlightAutomaticWriteCompletesBeforeManualClear() = runBlocking {
        withTimeout(2_000) {
            val f = Fixture()
            val ticket = f.measured()
            val release = CompletableDeferred<Boolean>()
            val automatic = async(start = CoroutineStart.UNDISPATCHED) { f.automatic(ticket) { release.await() } }
            f.store.save(ProfilePersonalBestValues())
            val manual = async(start = CoroutineStart.UNDISPATCHED) { f.manual(f.store.snapshot()) }
            release.complete(true)
            // Remote request succeeded, but its stale local acknowledgement must fail.
            assertFalse(automatic.await())
            assertTrue(manual.await())
            assertEquals(2, f.writes)
            assertNull(f.remoteFlyingPr)
            assertNull(f.store.pendingFlyingPr("account-a"))
        }
    }

    @Test fun newMeasuredBestDuringManualUploadSurvivesItsOlderAcknowledgement() = runBlocking {
        withTimeout(2_000) {
            val f = Fixture()
            f.store.save(ProfilePersonalBestValues(primaryEvent = SportDiscipline.SPRINT_200M, personalRecordSeconds = 21.123456789))
            val clear = f.store.snapshot()
            val release = CompletableDeferred<Boolean>()
            val manual = async(start = CoroutineStart.UNDISPATCHED) { f.manual(clear) { release.await() } }
            val newer = f.measured(0.987654321)
            assertFalse(f.automatic(newer)) // Busy: durable ticket is retained.
            release.complete(true)
            assertFalse(manual.await()) // Must not acknowledge the new measured state.
            assertEquals(newer, f.store.pendingFlyingPr("account-a"))
            assertTrue(f.automatic(newer))
            assertEquals(0.987654321, f.remoteFlyingPr!!, 0.0)
            assertFalse(f.manual(clear)) // Captured old payload cannot run after auto success either.
            val current = f.store.snapshot()
            assertEquals(21.123456789, current.values.personalRecordSeconds!!, 0.0)
            assertEquals(SportDiscipline.SPRINT_200M, current.values.primaryEvent)
            assertTrue(f.manual(current))
            assertEquals(0.987654321, f.remoteFlyingPr!!, 0.0)
            assertNull(f.store.snapshot().revision)
            assertNull(f.store.pendingFlyingPr("account-a"))
        }
    }

    @Test fun measuredBestAfterAcknowledgedClearIsNotMistakenForStaleWork() = runBlocking {
        val f = Fixture()
        f.store.save(ProfilePersonalBestValues())
        val oldManual = f.store.snapshot()
        assertTrue(f.manual(oldManual))
        val newer = f.measured(2.123456789, FlyingDistance.METERS_20)
        assertTrue(f.automatic(newer))
        assertFalse(f.manual(oldManual))
        assertEquals(2.123456789, f.remoteFlyingPr!!, 0.0)
        assertEquals(FlyingDistance.METERS_20, f.store.load().flyingDistance)
    }

    @Test fun lateAutomaticAcknowledgementCannotRemoveNewerMeasuredTicket() = runBlocking {
        withTimeout(2_000) {
            val f = Fixture()
            val first = f.measured(1.1)
            val release = CompletableDeferred<Boolean>()
            val upload = async(start = CoroutineStart.UNDISPATCHED) { f.automatic(first) { release.await() } }
            val newer = f.measured(1.0)
            release.complete(true)
            assertFalse(upload.await())
            assertEquals(newer, f.store.pendingFlyingPr("account-a"))
            assertTrue(f.automatic(newer))
            assertEquals(1.0, f.remoteFlyingPr!!, 0.0)
        }
    }

    @Test fun accountSwitchAndTeardownRejectOldTicketWithoutClearingNewAccountWork() = runBlocking {
        val f = Fixture()
        val previous = f.measured()
        f.account = "account-b"
        assertFalse(f.automatic(previous))
        assertNull(f.store.pendingFlyingPr("account-b"))
        SettingsRepository(f.data).clearUserScopedFields()
        val current = f.measured() // Same values, different local revision and owner.
        assertFalse(f.store.acknowledgeFlyingPr(previous))
        assertEquals(current, f.store.pendingFlyingPr("account-b"))
        assertTrue(f.automatic(current))
        assertEquals(1, f.writes)
    }

    @Test fun timingStartWhileManualWaitsDefersItAndPreservesTheQueue() = runBlocking {
        withTimeout(2_000) {
            val f = Fixture()
            f.store.save(ProfilePersonalBestValues())
            val snapshot = f.store.snapshot()
            f.writer.lock()
            val manual = async(start = CoroutineStart.UNDISPATCHED) { f.manual(snapshot) }
            f.timing = true
            f.writer.unlock()
            assertFalse(manual.await())
            assertEquals(0, f.writes)
            assertEquals(snapshot, f.store.snapshot())
            val measured = f.measured()
            assertFalse(f.automatic(measured))
            assertEquals(measured, f.store.pendingFlyingPr("account-a"))
            f.timing = false
            assertTrue(f.automatic(measured))
        }
    }

    @Test fun failureRetainsOriginalTicketAndOnlyBetterSameDistanceMeasurementsReplaceIt() = runBlocking {
        val f = Fixture()
        val ticket = f.measured()
        assertFalse(f.automatic(ticket) { false })
        assertEquals(ticket, f.store.pendingFlyingPr("account-a"))
        assertNull(f.store.recordMeasuredFlyingPr(FlyingDistance.METERS_20, 1.0, f.account))
        assertNull(f.store.recordMeasuredFlyingPr(FlyingDistance.METERS_10, 1.1, f.account))
        assertNull(f.store.recordMeasuredFlyingPr(FlyingDistance.METERS_10, Double.NaN, f.account))
        assertNull(f.store.recordMeasuredFlyingPr(FlyingDistance.METERS_10, 0.9, f.account) { false })
        assertEquals(ticket, f.store.pendingFlyingPr("account-a"))
    }

    @Test fun legacyRetryAdoptsOnlyMatchingCanonicalValuesAndGuestTicketIsNotRebound() = runBlocking {
        fun legacy(time: Double, raw: String) = preferencesOf(
            stringPreferencesKey("pending_flying_pr_sync") to raw,
            stringPreferencesKey("flying_distance") to "10m",
            doublePreferencesKey("flying_pr") to time
        )
        val valid = Fixture(legacy(1.05, "10m:1.05"))
        val adopted = valid.store.pendingFlyingPr("account-a")!!
        assertEquals(adopted, valid.store.pendingFlyingPr("account-a"))
        assertTrue(valid.automatic(adopted))
        val stale = Fixture(legacy(1.0, "10m:1.05"))
        assertNull(stale.store.pendingFlyingPr("account-a"))
        assertNull(stale.data.state.value[stringPreferencesKey("pending_flying_pr_sync")])
        val guest = Fixture().apply { account = null }
        val local = guest.measured()
        guest.account = "account-b"
        assertNull(guest.store.pendingFlyingPr("account-b"))
        assertFalse(guest.automatic(local))
        assertEquals(1.05, guest.store.load().flyingPrSeconds!!, 0.0)
    }

    @Test fun suspendingRelevanceCheckRechecksAccountAndExceptionReleasesWriter() = runBlocking {
        withTimeout(2_000) {
            val mutex = Mutex()
            var account = "account-a"
            val guardEntered = CompletableDeferred<Unit>()
            val releaseGuard = CompletableDeferred<Unit>()
            var writes = 0
            val guarded = async(start = CoroutineStart.UNDISPATCHED) {
                mutex.withProfileWriteLock(false, "account-a", { account }, canWrite = {
                    guardEntered.complete(Unit)
                    releaseGuard.await()
                    true
                }) { writes++; true }
            }
            guardEntered.await()
            account = "account-b"
            releaseGuard.complete(Unit)
            assertFalse(guarded.await())
            assertEquals(0, writes)
            try {
                mutex.withProfileWriteLock(false, "account-b", { account }) {
                    throw IllegalStateException("synthetic failure")
                }
                fail("Expected synthetic failure")
            } catch (_: IllegalStateException) {
                assertFalse(mutex.isLocked)
            }
            assertTrue(mutex.withProfileWriteLock(true, "account-b", { account }) { true })
        }
    }

    @Test fun cancellingWaiterDoesNotUnlockWriterAndAccountChangesPreventWrites() = runBlocking {
        withTimeout(2_000) {
            val mutex = Mutex(locked = true)
            var account = "account-a"
            var writes = 0
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
                mutex.withProfileWriteLock(true, "account-a", { account }) { writes++; true }
            }
            cancelled.cancelAndJoin()
            assertTrue(mutex.isLocked)
            val switched = async(start = CoroutineStart.UNDISPATCHED) {
                mutex.withProfileWriteLock(true, "account-a", { account }) { writes++; true }
            }
            account = "account-b"
            mutex.unlock()
            assertFalse(switched.await())
            assertEquals(0, writes)
            assertFalse(mutex.isLocked)
        }
    }
}
