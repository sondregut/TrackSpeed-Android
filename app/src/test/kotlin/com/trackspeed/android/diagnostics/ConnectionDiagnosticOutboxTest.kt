package com.trackspeed.android.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID

class ConnectionDiagnosticOutboxTest {
    @get:Rule val temporary = TemporaryFolder()
    private val ownerA = UUID.fromString("10000000-0000-4000-8000-000000000001")
    private val ownerB = UUID.fromString("10000000-0000-4000-8000-000000000002")
    private val environment = ConnectionDiagnosticEnvironment("1.0.10", 11, "Pixel 7a", "16", 36, "parity", "abcdef0123456789")
    private var now = 1_000L
    private fun record(owner: UUID = ownerA) = ConnectionDiagnosticEvent(
        UUID.randomUUID(), ConnectionDiagnosticFlow.CONNECTION, ConnectionDiagnosticTransport.BLE,
        ConnectionDiagnosticPhase.ATTEMPT, ConnectionDiagnosticOutcome.STARTED,
        occurredAt = Instant.parse("2026-10-22T16:11:00Z")
    ).bind(owner, environment)
    private fun receipt(record: ConnectionDiagnosticRecord) = ConnectionDiagnosticServerReceipt(record.id, record.userId, "2026-10-22T16:12:00Z")
    private fun box(dir: File = temporary.newFolder(), limits: ConnectionDiagnosticOutbox.Limits = ConnectionDiagnosticOutbox.Limits()) = ConnectionDiagnosticOutbox(dir, { now }, limits)
    private fun uploader(action: suspend (ConnectionDiagnosticRecord) -> ConnectionDiagnosticServerReceipt) = object : ConnectionDiagnosticUploader {
        override suspend fun upload(record: ConnectionDiagnosticRecord) = action(record)
    }

    @Test fun pendingSurvivesRestartAndDuplicateIsNotAdded() {
        val dir = temporary.newFolder()
        val first = box(dir)
        val record = record()
        assertEquals(ConnectionDiagnosticOutbox.EnqueueResult.QUEUED, first.enqueue(record))
        val restarted = box(dir)
        assertEquals(record, restarted.snapshot(ownerA.toString()).entries.single().record)
        assertEquals(ConnectionDiagnosticOutbox.EnqueueResult.DUPLICATE, restarted.enqueue(record))
    }

    @Test fun inFlightStaysOnDiskAndCannotBeEvictedOrCleared() = runBlocking {
        val dir = temporary.newFolder()
        val limits = ConnectionDiagnosticOutbox.Limits(maxEvents = 1, ttlMs = 100)
        val box = box(dir, limits)
        val record = record()
        box.enqueue(record)
        box.drain(uploader {
            assertEquals(ConnectionDiagnosticOutbox.Stage.IN_FLIGHT, box(dir, limits).snapshot(ownerA.toString()).entries.single().stage)
            now += 200
            box.expire()
            box.discardPending()
            assertEquals(ConnectionDiagnosticOutbox.EnqueueResult.FULL, box.enqueue(record()))
            assertEquals(record.id, box.snapshot(ownerA.toString()).entries.single().record.id)
            receipt(it)
        }, { ownerA.toString() }, { true })
        assertTrue(box.snapshot(ownerA.toString()).entries.isEmpty())
        assertEquals(ConnectionDiagnosticOutbox.ReceiptStatus.ACCEPTED, box.snapshot(ownerA.toString()).receipts.single().status)
    }

    @Test fun retryBackoffIsPersistedAndOtherReadyEventsAreNotBlocked() = runBlocking {
        val dir = temporary.newFolder()
        val box = box(dir)
        val fail = record()
        val pass = record()
        box.enqueue(fail); box.enqueue(pass)
        box.drain(uploader {
            if (it.id == fail.id) throw ConnectionDiagnosticUploadException(ConnectionDiagnosticUploadError.NETWORK)
            receipt(it)
        }, { ownerA.toString() }, { true })
        val restarted = box(dir)
        val pending = restarted.snapshot(ownerA.toString()).entries.single()
        assertEquals(1, pending.retryCount)
        assertEquals(3_000L, pending.nextAttemptAtMs)
        assertEquals(ConnectionDiagnosticUploadError.NETWORK, pending.lastError)
        var calls = 0
        restarted.drain(uploader { calls++; receipt(it) }, { ownerA.toString() }, { true })
        assertEquals(0, calls)
        now = 3_000
        restarted.drain(uploader { calls++; receipt(it) }, { ownerA.toString() }, { true })
        assertEquals(1, calls)
    }

    @Test fun lostResponseRetainsSameUuidAndReceiptPreventsDuplicateReenqueue() = runBlocking {
        val dir = temporary.newFolder()
        val box = box(dir)
        val record = record()
        val received = mutableSetOf<String>()
        box.enqueue(record)
        box.drain(uploader { received.add(it.id); throw IOException("synthetic raw error must never persist") }, { ownerA.toString() }, { true })
        assertFalse(File(dir, "outbox.json").readText().contains("synthetic"))
        now += 2_000
        val restarted = box(dir)
        restarted.drain(uploader { assertTrue(received.contains(it.id)); receipt(it) }, { ownerA.toString() }, { true })
        assertEquals(1, received.size)
        assertEquals(ConnectionDiagnosticOutbox.EnqueueResult.DUPLICATE, restarted.enqueue(record))
        assertEquals("2026-10-22T16:12:00Z", restarted.snapshot(ownerA.toString()).receipts.single().serverCreatedAt)
    }

    @Test fun userAuthIsolationSurvivesAccountSwitchAndNoAuth() = runBlocking {
        val box = box()
        val a = record(ownerA); val b = record(ownerB)
        box.enqueue(a); box.enqueue(b)
        var owner: String? = null
        val uploaded = mutableListOf<String>()
        val client = uploader { uploaded += it.userId; receipt(it) }
        box.drain(client, { owner }, { true })
        assertTrue(uploaded.isEmpty())
        owner = ownerB.toString()
        box.drain(client, { owner }, { true })
        assertEquals(listOf(ownerB.toString()), uploaded)
        assertEquals(a.id, box.snapshot(ownerA.toString()).entries.single().record.id)
        assertTrue(box.snapshot(ownerB.toString()).entries.isEmpty())
        assertTrue(box.snapshot(ownerA.toString()).receipts.isEmpty())
    }

    @Test fun wrongOwnerOrInvalidServerReceiptIsNeverAccepted() = runBlocking {
        val box = box()
        val record = record()
        box.enqueue(record)
        box.drain(uploader { receipt(it).copy(userId = ownerB.toString()) }, { ownerA.toString() }, { true })
        assertEquals(ConnectionDiagnosticUploadError.UNCONFIRMED, box.snapshot(ownerA.toString()).entries.single().lastError)
        assertTrue(box.snapshot(ownerA.toString()).receipts.isEmpty())
        now += 2_000
        box.drain(uploader { receipt(it).copy(createdAt = "not a date") }, { ownerA.toString() }, { true })
        assertTrue(box.snapshot(ownerA.toString()).receipts.isEmpty())
    }

    @Test fun timingOrConsentGateNeverCallsNetwork() = runBlocking {
        val box = box()
        box.enqueue(record())
        box.drain(uploader { fail("Network must stay paused"); receipt(it) }, { ownerA.toString() }, { false })
        assertEquals(ConnectionDiagnosticOutbox.Stage.PENDING, box.snapshot(ownerA.toString()).entries.single().stage)
        box.discardPending()
        assertEquals(ConnectionDiagnosticOutbox.ReceiptStatus.DISABLED, box.snapshot(ownerA.toString()).receipts.single().status)
    }

    @Test fun cancellationRetainsInFlightRecordAndPropagates() = runBlocking {
        val dir = temporary.newFolder()
        val box = box(dir)
        box.enqueue(record())
        var cancelled = false
        try { box.drain(uploader { throw CancellationException("cancel") }, { ownerA.toString() }, { true }) }
        catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(ConnectionDiagnosticOutbox.Stage.IN_FLIGHT, box(dir).snapshot(ownerA.toString()).entries.single().stage)
        box.drain(uploader { receipt(it) }, { ownerA.toString() }, { true })
        assertTrue(box.snapshot(ownerA.toString()).entries.isEmpty())
    }

    @Test fun countBytesTtlAndReceiptsAreBounded() {
        val limits = ConnectionDiagnosticOutbox.Limits(maxEvents = 2, maxReceipts = 2, ttlMs = 100)
        val box = box(limits = limits)
        val first = record(); box.enqueue(first); box.enqueue(record()); box.enqueue(record())
        assertEquals(2, box.snapshot(ownerA.toString()).entries.size)
        assertEquals(first.id, box.snapshot(ownerA.toString()).receipts.single().eventId)
        assertEquals(ConnectionDiagnosticOutbox.ReceiptStatus.EVICTED, box.snapshot(ownerA.toString()).receipts.single().status)
        now += 100
        box.expire()
        assertTrue(box.snapshot(ownerA.toString()).entries.isEmpty())
        assertEquals(2, box.snapshot(ownerA.toString()).receipts.size)
        val tiny = box(limits = ConnectionDiagnosticOutbox.Limits(maxBytes = 10))
        assertEquals(ConnectionDiagnosticOutbox.EnqueueResult.TOO_LARGE, tiny.enqueue(record()))
    }

    @Test fun invalidFreeTextRecordNeverEntersOutbox() {
        val box = box()
        assertEquals(ConnectionDiagnosticOutbox.EnqueueResult.INVALID, box.enqueue(record().copy(reason = "AA:BB:CC:DD:EE:FF")))
        assertTrue(box.snapshot(ownerA.toString()).entries.isEmpty())
    }

    @Test fun payloadByteBudgetEvictsOldestEvenWhenCountLimitIsNotReached() {
        val first = record()
        val bytes = Json { encodeDefaults = true }.encodeToString(first).toByteArray(Charsets.UTF_8).size
        val box = box(limits = ConnectionDiagnosticOutbox.Limits(maxEvents = 100, maxBytes = bytes * 2 - 1))
        box.enqueue(first)
        val second = record()
        box.enqueue(second)
        assertEquals(second.id, box.snapshot(ownerA.toString()).entries.single().record.id)
        assertEquals(first.id, box.snapshot(ownerA.toString()).receipts.single().eventId)
    }

    @Test fun expiryCanRunWhileSignedOutAndReceiptsAlsoExpire() {
        val limits = ConnectionDiagnosticOutbox.Limits(ttlMs = 100)
        val box = box(limits = limits)
        box.enqueue(record())
        now += 100
        box.expire()
        assertTrue(box.snapshot(ownerA.toString()).entries.isEmpty())
        assertEquals(1, box.snapshot(ownerA.toString()).receipts.size)
        now += 100
        box.expire()
        assertTrue(box.snapshot(ownerA.toString()).receipts.isEmpty())
    }

    @Test fun corruptedStateIsBoundedlyQuarantinedWithoutUploadingIt() {
        val dir = temporary.newFolder()
        box(dir).enqueue(record())
        File(dir, "outbox.json").writeText("{broken")
        val recovered = box(dir)
        assertTrue(recovered.snapshot(ownerA.toString()).entries.isEmpty())
        assertEquals("{broken", File(dir, "outbox.corrupt").readText())
        recovered.enqueue(record())
        assertEquals(1, recovered.snapshot(ownerA.toString()).entries.size)
    }
}
