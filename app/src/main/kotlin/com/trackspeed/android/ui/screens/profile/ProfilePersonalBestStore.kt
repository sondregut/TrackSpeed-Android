package com.trackspeed.android.ui.screens.profile

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.trackspeed.android.data.model.FlyingDistance
import com.trackspeed.android.data.model.SportDiscipline
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** One atomic local ordering boundary for explicit and measured profile PRs.
 * Queue revisions stay local. This store performs no network operations.
 */
@Singleton
class ProfilePersonalBestStore @Inject constructor(private val dataStore: DataStore<Preferences>) {
    val values: Flow<ProfilePersonalBestValues> = dataStore.data.map(::readValues)

    suspend fun load(): ProfilePersonalBestValues = values.first()

    suspend fun snapshot(): ProfilePersonalBestSnapshot {
        val prefs = dataStore.data.first()
        return ProfilePersonalBestSnapshot(readValues(prefs), prefs[ManualEditRevision], prefs[StateRevision])
    }

    suspend fun isCurrent(snapshot: ProfilePersonalBestSnapshot): Boolean = matches(dataStore.data.first(), snapshot)

    /** A late upload may acknowledge only the exact local PR state it sent. */
    suspend fun acknowledgeUploaded(snapshot: ProfilePersonalBestSnapshot): Boolean {
        var acknowledged = false
        dataStore.edit { prefs ->
            if (matches(prefs, snapshot)) {
                prefs.remove(ManualEditRevision)
                prefs.remove(PendingProfileSync)
                // A full-profile upload also carried this exact measured PR.
                clearFlyingQueue(prefs)
                acknowledged = true
            }
        }
        return acknowledged
    }

    suspend fun retainPendingAfterFailure(snapshot: ProfilePersonalBestSnapshot) {
        dataStore.edit { prefs ->
            if (matches(prefs, snapshot)) prefs[PendingProfileSync] = true
        }
    }

    private fun matches(prefs: Preferences, snapshot: ProfilePersonalBestSnapshot): Boolean =
        prefs[ManualEditRevision] == snapshot.revision &&
            prefs[StateRevision] == snapshot.stateRevision && readValues(prefs) == snapshot.values

    private fun readValues(prefs: Preferences): ProfilePersonalBestValues {
        val flying = prefs[FlyingPr]?.takeIf { it.isFinite() && it > 0 }
        val distance = prefs[FlyingDistanceKey]?.let(FlyingDistance::fromRawValue)
        return ProfilePersonalBestValues(
            primaryEvent = prefs[PrimaryEvent]?.let(SportDiscipline::fromRawValue) ?: SportDiscipline.SPRINT_100M,
            personalRecordSeconds = prefs[PersonalRecord]?.takeIf { it.isFinite() && it > 0 },
            flyingDistance = distance.takeIf { flying != null },
            flyingPrSeconds = flying.takeIf { distance != null }
        )
    }

    suspend fun save(values: ProfilePersonalBestValues) {
        require(values.isValid) { "Invalid profile personal best values" }
        dataStore.edit { prefs ->
            prefs[PrimaryEvent] = values.primaryEvent.rawValue
            prefs[SportCategory] = values.primaryEvent.category.name
            values.personalRecordSeconds?.let { prefs[PersonalRecord] = it } ?: prefs.remove(PersonalRecord)
            values.flyingPrSeconds?.let { prefs[FlyingPr] = it } ?: prefs.remove(FlyingPr)
            values.flyingDistance?.let { prefs[FlyingDistanceKey] = it.rawValue } ?: prefs.remove(FlyingDistanceKey)
            clearFlyingQueue(prefs)
            prefs[PendingProfileSync] = true
            val revision = UUID.randomUUID().toString()
            prefs[ManualEditRevision] = revision
            prefs[StateRevision] = revision
        }
    }

    /** Persist a genuinely better measured PR and its retry ticket together.
     * Do this before any upload; a failed callback must never reinsert a ticket.
     * The account check runs inside the atomic edit after any DataStore wait.
     */
    suspend fun recordMeasuredFlyingPr(
        distance: FlyingDistance,
        timeSeconds: Double,
        accountId: String?,
        isAccountCurrent: () -> Boolean = { true }
    ): FlyingPrSyncSnapshot? {
        if (!timeSeconds.isFinite() || timeSeconds <= 0) return null
        var queued: FlyingPrSyncSnapshot? = null
        dataStore.edit { prefs ->
            if (!isAccountCurrent()) return@edit
            val tracked = prefs[FlyingDistanceKey]?.let(FlyingDistance::fromRawValue)
            val previous = prefs[FlyingPr]?.takeIf { it.isFinite() && it > 0 }
            if (tracked != null && tracked != distance) return@edit
            if (tracked == distance && previous != null && previous <= timeSeconds) return@edit
            val revision = UUID.randomUUID().toString()
            prefs[FlyingDistanceKey] = distance.rawValue
            prefs[FlyingPr] = timeSeconds
            prefs[StateRevision] = revision
            prefs[PendingFlyingPrSync] = "${distance.rawValue}:$timeSeconds"
            accountId?.takeIf { it.isNotBlank() }?.let { prefs[PendingFlyingPrAccount] = it }
                ?: prefs.remove(PendingFlyingPrAccount)
            queued = FlyingPrSyncSnapshot(distance, timeSeconds, revision, accountId?.takeIf { it.isNotBlank() })
        }
        return queued
    }

    /** Adopt only valid pre-revision retries that still equal canonical values.
     * New guest tickets remain local; the existing authenticated full-profile
     * flow owns guest-to-account import. Never rebind a ticket from another user.
     */
    suspend fun pendingFlyingPr(accountId: String): FlyingPrSyncSnapshot? {
        if (accountId.isBlank()) return null
        val initial = dataStore.data.first()
        if (initial[PendingFlyingPrSync].isNullOrBlank()) return null
        if (initial[StateRevision] != null) {
            return readFlyingTicket(initial)?.takeIf { it.accountId == accountId }
        }
        var result: FlyingPrSyncSnapshot? = null
        dataStore.edit { prefs ->
            if (prefs[StateRevision] == null) {
                val parts = prefs[PendingFlyingPrSync]?.split(':')
                val distance = parts?.takeIf { it.size == 2 }?.firstOrNull()?.let(FlyingDistance::fromRawValue)
                val seconds = parts?.takeIf { it.size == 2 }?.lastOrNull()?.toDoubleOrNull()
                if (distance == null || seconds == null || !seconds.isFinite() || seconds <= 0 ||
                    prefs[FlyingDistanceKey] != distance.rawValue || prefs[FlyingPr] != seconds) {
                    clearFlyingQueue(prefs)
                    return@edit
                }
                prefs[StateRevision] = UUID.randomUUID().toString()
                prefs[PendingFlyingPrAccount] = accountId
            }
            result = readFlyingTicket(prefs)?.takeIf { it.accountId == accountId }
        }
        return result
    }

    suspend fun isCurrentFlyingPr(ticket: FlyingPrSyncSnapshot): Boolean =
        readFlyingTicket(dataStore.data.first()) == ticket

    suspend fun acknowledgeFlyingPr(ticket: FlyingPrSyncSnapshot): Boolean {
        var acknowledged = false
        dataStore.edit { prefs ->
            if (readFlyingTicket(prefs) == ticket) {
                clearFlyingQueue(prefs)
                acknowledged = true
            }
        }
        return acknowledged
    }

    private fun readFlyingTicket(prefs: Preferences): FlyingPrSyncSnapshot? {
        val revision = prefs[StateRevision] ?: return null
        val parts = prefs[PendingFlyingPrSync]?.split(':') ?: return null
        if (parts.size != 2) return null
        val distance = FlyingDistance.fromRawValue(parts[0]) ?: return null
        val seconds = parts[1].toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return null
        if (prefs[FlyingDistanceKey] != distance.rawValue || prefs[FlyingPr] != seconds) return null
        return FlyingPrSyncSnapshot(distance, seconds, revision, prefs[PendingFlyingPrAccount])
    }

    private fun clearFlyingQueue(prefs: MutablePreferences) {
        prefs.remove(PendingFlyingPrSync)
        prefs.remove(PendingFlyingPrAccount)
    }

    private companion object {
        val PrimaryEvent = stringPreferencesKey("primary_event")
        val PersonalRecord = doublePreferencesKey("personal_record")
        val FlyingDistanceKey = stringPreferencesKey("flying_distance")
        val FlyingPr = doublePreferencesKey("flying_pr")
        val SportCategory = stringPreferencesKey("sportCategory")
        val PendingProfileSync = booleanPreferencesKey("pending_profile_sync")
        val PendingFlyingPrSync = stringPreferencesKey("pending_flying_pr_sync")
        val PendingFlyingPrAccount = stringPreferencesKey("pending_flying_pr_account")
        val ManualEditRevision = stringPreferencesKey("pending_profile_pr_revision")
        val StateRevision = stringPreferencesKey("profile_pr_state_revision")
    }
}

/** Revisions and account association are local queue metadata, never payload fields. */
data class ProfilePersonalBestSnapshot(
    val values: ProfilePersonalBestValues,
    val revision: String?,
    val stateRevision: String? = null
)

data class FlyingPrSyncSnapshot(
    val distance: FlyingDistance,
    val timeSeconds: Double,
    val revision: String,
    val accountId: String?
)
