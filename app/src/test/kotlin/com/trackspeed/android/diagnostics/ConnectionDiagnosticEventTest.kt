package com.trackspeed.android.diagnostics

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.UUID

class ConnectionDiagnosticEventTest {
    private val owner = UUID.fromString("10000000-0000-4000-8000-000000000001")
    private val environment = ConnectionDiagnosticEnvironment("1.0.10", 11, "Pixel 7a", "16", 36, "android-parity", "0123456789abcdef")

    private fun event() = ConnectionDiagnosticEvent(
        UUID.randomUUID(), ConnectionDiagnosticFlow.CLOCK_SYNC, ConnectionDiagnosticTransport.WIFI_AWARE,
        ConnectionDiagnosticPhase.ATTEMPT, ConnectionDiagnosticOutcome.STARTED, occurredAt = Instant.parse("2026-10-22T16:11:00Z")
    )

    @Test fun androidUsesMetadataNotIosVersionOrRadioAndNameColumns() {
        val record = event().bind(owner, environment)
        val objectValue = Json { encodeDefaults = true }.parseToJsonElement(Json { encodeDefaults = true }.encodeToString(record)).jsonObject
        assertEquals("android", objectValue.getValue("metadata").jsonObject.getValue("platform").jsonPrimitive.content)
        assertEquals("16", objectValue.getValue("metadata").jsonObject.getValue("os_version").jsonPrimitive.content)
        listOf("ios_version", "remote_device_id", "remote_peer_name", "local_device_name").forEach { assertFalse(objectValue.containsKey(it)) }
        assertEquals(record.attemptId, record.deviceId)
        assertNotEquals(record.deviceId, event().bind(owner, environment).deviceId)
        assertTrue(record.isSanitized())
    }

    @Test fun eventIdentityIsStableButRetriesCanHaveSeparateIdsWithinOneAttempt() {
        val event = event()
        assertEquals(event.bind(owner, environment).id, event.bind(owner, environment).id)
        assertEquals(event.attemptId, event.copy(id = UUID.randomUUID(), retry = 1).attemptId)
        assertNotEquals(event.id, event.copy(id = UUID.randomUUID(), retry = 1).id)
    }

    @Test fun callerCannotSmuggleIdentifiersOrUrlsThroughEnvironmentLabels() {
        val values = listOf("AA:BB:CC:DD:EE:FF", "192.168.1.20", "person@example.test", "https://host/path?token=x", "Bearer secret", "x".repeat(100))
        values.forEach { unsafe ->
            val record = event().bind(owner, environment.copy(deviceModel = unsafe, osVersion = unsafe, buildMarker = unsafe))
            assertEquals("unknown", record.deviceModel)
            assertEquals("unknown", record.metadata.osVersion)
            assertFalse(Json.encodeToString(record).contains(unsafe))
        }
    }

    @Test fun malformedPersistedValuesAreRejected() {
        val good = event().bind(owner, environment)
        assertFalse(good.copy(reason = "peer AA:BB:CC:DD:EE:FF failed").isSanitized())
        assertFalse(good.copy(phase = "https://host/token").isSanitized())
        assertFalse(good.copy(metadata = good.metadata.copy(errorDomain = "private-name")).isSanitized())
    }

    @Test fun numericMetricsPreserveQualityWithoutPublishingInvalidNumbers() {
        val record = event().copy(elapsedMs = Long.MAX_VALUE, retry = -1, uncertaintyMs = 11.86, minRttMs = Double.NaN,
            jitterMs = Double.POSITIVE_INFINITY, acceptedSamples = -2).bind(owner, environment)
        assertEquals(Int.MAX_VALUE, record.durationMs)
        assertEquals(11.86, record.metadata.uncertaintyMs!!, 0.0)
        assertNull(record.metadata.minRttMs)
        assertNull(record.metadata.jitterMs)
        assertEquals(0, record.metadata.acceptedSamples)
        assertEquals(0, record.retryCount)
        assertTrue(record.isSanitized())
    }

    @Test fun outerAttemptUsesMonotonicDurationAndSharedCorrelationAcrossTransportFallback() {
        var nanos = 1_000_000L
        val events = mutableListOf<ConnectionDiagnosticEvent>()
        val attempt = ConnectionDiagnosticAttempt(ConnectionDiagnosticFlow.JOIN, { nanos }, { events += it })
        attempt.record(ConnectionDiagnosticPhase.ATTEMPT, ConnectionDiagnosticOutcome.STARTED)
        nanos += 8_000_000
        attempt.record(ConnectionDiagnosticPhase.CLOCK_SYNC, ConnectionDiagnosticOutcome.FALLBACK, ConnectionDiagnosticTransport.WIFI_AWARE)
        nanos += 5_000_000
        attempt.record(ConnectionDiagnosticPhase.CLOCK_SYNC, ConnectionDiagnosticOutcome.SUCCEEDED, ConnectionDiagnosticTransport.BLE)
        assertEquals(listOf(0, 1, 2), events.map { it.sequence })
        assertEquals(listOf(0L, 8L, 13L), events.map { it.elapsedMs })
        assertEquals(1, events.map { it.attemptId }.distinct().size)
        assertEquals(3, events.map { it.id }.distinct().size)
    }

    @Test fun failingDiagnosticSinkCannotThrowIntoConnectionOperation() {
        val attempt = ConnectionDiagnosticAttempt(ConnectionDiagnosticFlow.CONNECTION, { 0L }, { throw IllegalStateException("sink") })
        attempt.record(ConnectionDiagnosticPhase.ATTEMPT, ConnectionDiagnosticOutcome.FAILED)
    }

    @Test fun oldCompletionCannotUnlockNewOrOverlappingClockSampling() {
        var idleCount = 0
        val gate = ConnectionDiagnosticSamplingGate { idleCount++ }
        val first = gate.begin()
        val second = gate.begin()
        gate.end(first)
        assertTrue(gate.isActive)
        assertEquals(0, idleCount)
        gate.end(first) // duplicate/late completion
        assertTrue(gate.isActive)
        gate.end(second)
        assertFalse(gate.isActive)
        assertEquals(1, idleCount)
    }

    @Test fun samplingCancellationReleasesOnlyItsOwnToken() {
        val gate = ConnectionDiagnosticSamplingGate()
        val outer = gate.begin()
        try {
            val nested = gate.begin()
            try { throw kotlinx.coroutines.CancellationException("cancel") }
            finally { gate.end(nested) }
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertTrue(gate.isActive)
        gate.end(outer)
        assertFalse(gate.isActive)
    }

    @Test fun failingIdleNotificationCannotChangeSamplerCompletion() {
        val gate = ConnectionDiagnosticSamplingGate { throw IllegalStateException("sink") }
        gate.end(gate.begin())
        assertFalse(gate.isActive)
    }

    @Test fun vanishedReferenceHoldExpiresWithoutReleasingRunningLocalSampler() {
        var now = 0L
        val gate = ConnectionDiagnosticSamplingGate(monotonicMillis = { now })
        val reference = gate.begin(maxHoldMs = 180_000)
        val requester = gate.begin()
        now = 180_000
        assertFalse(gate.isActive(reference))
        assertTrue(gate.isActive(requester))
        gate.end(requester)
        assertFalse(gate.isActive)
    }

    @Test fun lateExpiredReferenceCompletionCannotReleaseReplacementHold() {
        var now = 0L
        val gate = ConnectionDiagnosticSamplingGate(monotonicMillis = { now })
        val old = gate.begin(maxHoldMs = 100)
        now = 101
        val replacement = gate.begin(maxHoldMs = 100)
        gate.end(old)
        assertTrue(gate.isActive(replacement))
        now = 201
        assertFalse(gate.isActive)
    }
}
