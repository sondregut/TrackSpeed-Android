package com.trackspeed.android.protocol

import kotlinx.serialization.json.double
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimingMessageCodecTest {

    @Test
    fun clockPacketsUseWireIdenticalLightweightByteCodec() {
        val messages = listOf(
            TimingMessage(
                seq = 41,
                senderId = "android-reference",
                sessionId = "mixed-session",
                targetDeviceId = "ios-requester",
                payload = TimingPayload.SyncPing(
                    pingId = "PING-1",
                    t1Nanos = 1_234_567_890L,
                    requesterId = "android-reference"
                ),
                createdAtNanos = 1_234_567_891L
            ),
            TimingMessage(
                seq = 42,
                senderId = "android-reference",
                sessionId = "mixed-session",
                targetDeviceId = "ios-requester",
                payload = TimingPayload.SyncPong(
                    pingId = "PING-2",
                    t1Nanos = 2_000_000_000L,
                    t2Nanos = 2_010_000_000L,
                    t3Nanos = 2_010_500_000L,
                    requesterId = "ios-requester"
                ),
                createdAtNanos = 2_010_500_001L
            )
        )

        messages.forEach { message ->
            val canonicalJson = TimingMessageCodec.json.parseToJsonElement(
                TimingMessageCodec.encodeToString(message)
            )
            val clockBytes = TimingMessageCodec.encodeToBytes(message)
            val lightweightJson = TimingMessageCodec.json.parseToJsonElement(
                clockBytes.toString(Charsets.UTF_8)
            )

            assertEquals(canonicalJson, lightweightJson)
            assertEquals(message, TimingMessageCodec.decodeFromBytes(clockBytes))
            assertEquals(message, TimingMessageCodec.decodeFromString(clockBytes.toString(Charsets.UTF_8)))
        }
    }

    @Test
    fun syncPingTemplateStampsTimestampWithoutSerializationInTimedRegion() {
        val message = TimingMessage(
            seq = 43,
            senderId = "android-reference",
            sessionId = "mixed-session",
            payload = TimingPayload.SyncPing(
                pingId = "PING-BOUNDARY",
                t1Nanos = 0L,
                requesterId = "android-reference"
            ),
            createdAtNanos = 10L
        )

        val stampedTimestamp = 123_456_789_012_345L
        val bytes = TimingMessageCodec.prepareSyncPingTimestampTemplate(message)
            .stamp(stampedTimestamp)
        val decoded = TimingMessageCodec.decodeFromBytes(bytes)

        assertEquals(
            stampedTimestamp,
            (decoded.payload as TimingPayload.SyncPing).t1Nanos
        )
        val expectedPayload = (message.payload as TimingPayload.SyncPing)
            .copy(t1Nanos = stampedTimestamp)
        assertEquals(message.copy(payload = expectedPayload), decoded)
        assertTrue(bytes.toString(Charsets.UTF_8).contains("\"t1Nanos\":    $stampedTimestamp"))
    }

    @Test
    fun syncPongTemplateStampsTransmitTimestampAfterSerialization() {
        val message = TimingMessage(
            seq = 44,
            senderId = "android-reference",
            sessionId = "mixed-session",
            payload = TimingPayload.SyncPong(
                pingId = "PONG-BOUNDARY",
                t1Nanos = 100L,
                t2Nanos = 200L,
                t3Nanos = 0L,
                requesterId = "android-requester"
            ),
            createdAtNanos = 10L
        )

        val stampedTimestamp = 123_456_789_012_345L
        val bytes = TimingMessageCodec.prepareSyncPongTimestampTemplate(message)
            .stamp(stampedTimestamp)
        val decoded = TimingMessageCodec.decodeFromBytes(bytes)

        assertEquals(
            stampedTimestamp,
            (decoded.payload as TimingPayload.SyncPong).t3Nanos
        )
        val expectedPayload = (message.payload as TimingPayload.SyncPong)
            .copy(t3Nanos = stampedTimestamp)
        assertEquals(message.copy(payload = expectedPayload), decoded)
        assertTrue(bytes.toString(Charsets.UTF_8).contains("\"t3Nanos\":    $stampedTimestamp"))
    }

    @Test
    fun thumbnailUpdateRequiresAcknowledgement() {
        val message = TimingMessage.createCritical(
            seq = 1,
            senderId = "sender",
            sessionId = "session",
            payload = TimingPayload.ThumbnailUpdate(
                eventId = "event",
                gateId = "gate",
                role = TimingRole.FINISH_LINE,
                thumbnailData = "image"
            ),
            createdAtNanos = 1L
        )

        assertTrue(message.requiresAck)
    }

    @Test
    fun protocolVersionMatchesCurrentIosWireVersion() {
        assertEquals(7, TIMING_PROTOCOL_VERSION)
        assertEquals(9_000L, TimingProtocolSequence.PRE_SESSION_ROLE_ASSIGNMENT)
        assertEquals(9_001L, TimingProtocolSequence.PRE_SESSION_SYNC_COMPLETE)
        assertEquals(9_002L, TimingProtocolSequence.PRE_SESSION_SYNC_FAILED)
    }

    @Test
    fun sessionConfigRoundTripsStableHostAndTimingGateIdentities() {
        val config = TimingSessionConfig(
            distance = 60.0,
            startType = "flying",
            numberOfGates = 2,
            hostRole = TimingRole.FINISH_LINE,
            hostDeviceId = "host-stable-id",
            timingGateDeviceIds = listOf("start-stable-id", "host-stable-id")
        )
        val encoded = TimingMessageCodec.encodeToString(
            TimingMessage(
                seq = 1,
                senderId = "host-stable-id",
                sessionId = "session",
                payload = TimingPayload.SessionConfig(config),
                createdAtNanos = 1
            )
        )
        val decoded = TimingMessageCodec.decodeFromString(encoded)
            .payload as TimingPayload.SessionConfig

        assertEquals("host-stable-id", decoded.config.hostDeviceId)
        assertEquals(listOf("start-stable-id", "host-stable-id"), decoded.config.timingGateDeviceIds)
    }

    @Test
    fun protocolCompatibilityFailsClosedForDifferentEnvelopeSemantics() {
        assertTrue(TimingProtocolCompatibility.accepts(TIMING_PROTOCOL_VERSION))
        assertFalse(TimingProtocolCompatibility.accepts(TIMING_PROTOCOL_VERSION + 1))
        assertFalse(TimingProtocolCompatibility.accepts(TIMING_PROTOCOL_VERSION - 1))
    }

    @Test
    fun physicalGattTargetExceptionOnlyAcceptsMatchingStableIdentityAnnouncement() {
        val request = TimingPayload.RoleRequest(
            preferredRole = TimingRole.START_LINE,
            deviceId = "stable-joiner"
        )
        assertTrue(
            TimingBootstrapPolicy.acceptsPhysicallyTargetedRoleRequest(
                payload = request,
                senderId = "stable-joiner",
                hasDirectEncryptedGattRoute = true
            )
        )
        assertFalse(
            TimingBootstrapPolicy.acceptsPhysicallyTargetedRoleRequest(
                payload = request,
                senderId = "different-sender",
                hasDirectEncryptedGattRoute = true
            )
        )
        assertFalse(
            TimingBootstrapPolicy.acceptsPhysicallyTargetedRoleRequest(
                payload = TimingPayload.StartTiming(),
                senderId = "stable-joiner",
                hasDirectEncryptedGattRoute = true
            )
        )
        assertFalse(
            TimingBootstrapPolicy.acceptsPhysicallyTargetedRoleRequest(
                payload = request,
                senderId = "stable-joiner",
                hasDirectEncryptedGattRoute = false
            )
        )
    }

    @Test
    fun encryptedClientRouteOnlyAcceptsReservedPreSessionRoleAssignment() {
        val assignment = TimingPayload.RoleAssigned(
            role = TimingRole.START_LINE,
            targetDeviceId = "ios-local-central-id"
        )

        assertTrue(
            TimingBootstrapPolicy.acceptsPhysicallyTargetedPreSessionRoleAssignment(
                payload = assignment,
                sequence = TimingProtocolSequence.PRE_SESSION_ROLE_ASSIGNMENT,
                hasDirectEncryptedGattRoute = true
            )
        )
        assertFalse(
            TimingBootstrapPolicy.acceptsPhysicallyTargetedPreSessionRoleAssignment(
                payload = assignment,
                sequence = TimingProtocolSequence.PRE_SESSION_ROLE_ASSIGNMENT + 1,
                hasDirectEncryptedGattRoute = true
            )
        )
        assertFalse(
            TimingBootstrapPolicy.acceptsPhysicallyTargetedPreSessionRoleAssignment(
                payload = TimingPayload.StartTiming(),
                sequence = TimingProtocolSequence.PRE_SESSION_ROLE_ASSIGNMENT,
                hasDirectEncryptedGattRoute = true
            )
        )
        assertFalse(
            TimingBootstrapPolicy.acceptsPhysicallyTargetedPreSessionRoleAssignment(
                payload = assignment,
                sequence = TimingProtocolSequence.PRE_SESSION_ROLE_ASSIGNMENT,
                hasDirectEncryptedGattRoute = false
            )
        )
    }

    @Test
    fun encryptedClientRouteNormalizesOnlyPreSessionPhysicalTargets() {
        val physicalAssignment = TimingMessage(
            seq = TimingProtocolSequence.PRE_SESSION_ROLE_ASSIGNMENT,
            senderId = "ios-host",
            sessionId = "session",
            targetDeviceId = "ios-local-central-id",
            payload = TimingPayload.RoleAssigned(
                role = TimingRole.START_LINE,
                targetDeviceId = "ios-local-central-id"
            ),
            createdAtNanos = 1L
        )

        val normalized = TimingBootstrapPolicy.normalizePreSessionRoleAssignmentTarget(
            message = physicalAssignment,
            localDeviceId = "android-stable-id",
            hasDirectEncryptedGattRoute = true
        )
        assertEquals("android-stable-id", normalized.targetDeviceId)
        assertEquals(
            "android-stable-id",
            (normalized.payload as TimingPayload.RoleAssigned).targetDeviceId
        )

        assertEquals(
            physicalAssignment,
            TimingBootstrapPolicy.normalizePreSessionRoleAssignmentTarget(
                message = physicalAssignment,
                localDeviceId = "android-stable-id",
                hasDirectEncryptedGattRoute = false
            )
        )
        val nonBootstrapAssignment = physicalAssignment.copy(seq = physicalAssignment.seq + 1)
        assertEquals(
            nonBootstrapAssignment,
            TimingBootstrapPolicy.normalizePreSessionRoleAssignmentTarget(
                message = nonBootstrapAssignment,
                localDeviceId = "android-stable-id",
                hasDirectEncryptedGattRoute = true
            )
        )
    }

    @Test
    fun encodesPayloadUsingSwiftCodableSingleKeyShape() {
        val message = TimingMessage(
            seq = 42L,
            senderId = "11111111-1111-1111-1111-111111111111",
            sessionId = "22222222-2222-2222-2222-222222222222",
            payload = TimingPayload.CrossingEvent(
                gateId = "finish-gate",
                role = TimingRole.FINISH_LINE,
                gateIndex = 1,
                timestampNanos = 123_456_789L,
                confidence = 0.97,
                thumbnailData = "aGVsbG8="
            ),
            createdAtNanos = 987_654_321L
        )

        val encoded = TimingMessageCodec.encodeToString(message)
        val root = TimingMessageCodec.json.parseToJsonElement(encoded).jsonObject
        val payload = root.getValue("payload").jsonObject

        assertEquals(setOf("crossingEvent"), payload.keys)
        assertFalse(encoded.contains("\"type\""))
        assertEquals(
            "finishLine",
            payload.getValue("crossingEvent").jsonObject.getValue("role").jsonPrimitive.content
        )
    }

    @Test
    fun decodesIosStylePayloadWithOmittedOptionalEnvelopeFields() {
        val decoded = TimingMessageCodec.decodeFromString(
            """
            {
              "protocolVersion": 7,
              "seq": 7,
              "senderId": "11111111-1111-1111-1111-111111111111",
              "sessionId": "22222222-2222-2222-2222-222222222222",
              "payload": {
                "thumbnailMetadata": {
                  "eventId": "run-gate-123",
                  "gateId": "finish-gate",
                  "role": "finishLine",
                  "gateIndex": 1,
                  "gatePosition": 0.5,
                  "velocityPxPerSec": 180.25,
                  "crossingDirection": "L->R",
                  "workWidth": 160,
                  "thumbnailDebug": {
                    "chosenFramePick": "exact"
                  }
                }
              },
              "createdAtNanos": 987654321
            }
            """.trimIndent()
        )

        val payload = decoded.payload as TimingPayload.ThumbnailMetadata
        assertEquals("run-gate-123", payload.eventId)
        assertEquals(TimingRole.FINISH_LINE, payload.role)
        assertEquals("exact", payload.thumbnailDebug?.jsonObject?.get("chosenFramePick")?.jsonPrimitive?.content)
        assertEquals(null, decoded.messageId)
        assertEquals(null, decoded.targetDeviceId)
    }

    @Test
    fun distanceConfigUsesIosCompatibleStringObjectKeys() {
        val message = TimingMessage(
            seq = 8L,
            senderId = "11111111-1111-1111-1111-111111111111",
            sessionId = "22222222-2222-2222-2222-222222222222",
            payload = TimingPayload.DistanceConfigChanged(
                gateDistances = mapOf(0 to 0.0, 1 to 60.0)
            ),
            createdAtNanos = 123L
        )

        val encoded = TimingMessageCodec.encodeToString(message)
        val payload = TimingMessageCodec.json.parseToJsonElement(encoded)
            .jsonObject
            .getValue("payload")
            .jsonObject
            .getValue("distanceConfigChanged")
            .jsonObject
        val gateDistances = payload.getValue("gateDistances").jsonObject

        assertEquals(0.0, gateDistances.getValue("0").jsonPrimitive.double, 0.0)
        assertEquals(60.0, gateDistances.getValue("1").jsonPrimitive.double, 0.0)

        val decoded = TimingMessageCodec.decodeFromString(encoded)
        val decodedPayload = decoded.payload as TimingPayload.DistanceConfigChanged
        assertEquals(60.0, decodedPayload.gateDistances.getValue(1), 0.0)
    }

    @Test
    fun everyCurrentPayloadCaseRoundTripsWithItsWireCaseName() {
        val debug = buildJsonObject { put("chosenFramePick", "exact") }
        val status = GateStatusInfo(
            isCalibrated = true,
            isArmed = true,
            isClear = true,
            isPrebufferReady = true,
            isStable = true,
            gatePosition = 0.5,
            batteryLevel = 91
        )
        val segment = SegmentSplit(0, 1, "start", "finish", 1_000, 10.0, 1_000, 10.0)
        val syncEvent = SyncableTimingEvent("event", "crossingEvent", "gate", 1, 100, null, 1.2, 7)
        val payloads = listOf(
            "sessionConfig" to TimingPayload.SessionConfig(TimingSessionConfig(60.0, "flying", 3, TimingRole.START_LINE, 60, 7, true)),
            "sessionConfigAck" to TimingPayload.SessionConfigAck(),
            "roleRequest" to TimingPayload.RoleRequest(TimingRole.FINISH_LINE, "device"),
            "roleAssigned" to TimingPayload.RoleAssigned(TimingRole.LAP_GATE, "device"),
            "gateAssigned" to TimingPayload.GateAssigned(GateAssignment(TimingRole.LAP_GATE, 1, 30.0, "device")),
            "roleAssignedAck" to TimingPayload.RoleAssignedAck(TimingRole.LAP_GATE),
            "gateAssignedAck" to TimingPayload.GateAssignedAck(1),
            "ack" to TimingPayload.Ack("message"),
            "nack" to TimingPayload.Nack("message", "reason"),
            "heartbeatPing" to TimingPayload.HeartbeatPing(),
            "heartbeatPong" to TimingPayload.HeartbeatPong(9),
            "syncPing" to TimingPayload.SyncPing("ping", 1, "device"),
            "syncPong" to TimingPayload.SyncPong("ping", 1, 2, 3, "device"),
            "androidTransportCapabilities" to
                TimingPayload.AndroidTransportCapabilities(wifiAwareClockSync = true),
            "roleConfirmed" to TimingPayload.RoleConfirmed(TimingRole.START_LINE),
            "syncRequest" to TimingPayload.SyncRequest(),
            "syncComplete" to TimingPayload.SyncComplete(-100, 1.5),
            "countdown" to TimingPayload.Countdown(3, 60.0),
            "armed" to TimingPayload.Armed(),
            "startEvent" to TimingPayload.StartEvent(100, "AA=="),
            "finishResult" to TimingPayload.FinishResult(200, 1.2, "AA=="),
            "abort" to TimingPayload.Abort("reason"),
            "newRun" to TimingPayload.NewRun(),
            "cancelRun" to TimingPayload.CancelRun(),
            "sessionEnded" to TimingPayload.SessionEnded("done"),
            "calibrateRequest" to TimingPayload.CalibrateRequest(),
            "startTiming" to TimingPayload.StartTiming(),
            "calibrateAll" to TimingPayload.CalibrateAll(),
            "calibrationStatus" to TimingPayload.CalibrationStatus("gate", true, null),
            "armAll" to TimingPayload.ArmAll(),
            "armedAck" to TimingPayload.ArmedAck("gate", TimingRole.FINISH_LINE),
            "disarmAll" to TimingPayload.DisarmAll(),
            "startRun" to TimingPayload.StartRun(3),
            "gateStatus" to TimingPayload.GateStatus("gate", status),
            "crossingEvent" to TimingPayload.CrossingEvent("gate", TimingRole.FINISH_LINE, 2, 300, 0.98, "AA=="),
            "timingResultBroadcast" to TimingPayload.TimingResultBroadcast(400, 1.0, "start", "finish"),
            "multiGateResult" to TimingPayload.MultiGateResult(400, listOf(segment), 1.0),
            "adjustGateLine" to TimingPayload.AdjustGateLine("gate", 0.45),
            "supabaseSession" to TimingPayload.SupabaseSession("cloud"),
            "hybridSessionInfo" to TimingPayload.HybridSessionInfo("cloud", -10, 2.0),
            "startTypeChanged" to TimingPayload.StartTypeChanged("motion"),
            "distanceConfigChanged" to TimingPayload.DistanceConfigChanged(mapOf(0 to 0.0, 1 to 60.0)),
            "pauseDetection" to TimingPayload.PauseDetection(),
            "resumeDetection" to TimingPayload.ResumeDetection(),
            "debugPing" to TimingPayload.DebugPing(500),
            "debugPong" to TimingPayload.DebugPong(500),
            "audioSyncData" to TimingPayload.AudioSyncData("{}"),
            "thumbnailUpdate" to TimingPayload.ThumbnailUpdate("event", "gate", TimingRole.START_LINE, "AA=="),
            "thumbnailMetadata" to TimingPayload.ThumbnailMetadata("event", "gate", TimingRole.START_LINE, 0, 0.5f, 180f, "L->R", 160, debug),
            "eventSync" to TimingPayload.EventSync("event", "run"),
            "eventSyncResponse" to TimingPayload.EventSyncResponse(listOf(syncEvent), "event"),
            "configVersion" to TimingPayload.ConfigVersion(3, "distance"),
            "calibrationUpdate" to TimingPayload.CalibrationUpdate(TimingRole.FINISH_LINE, 0.5f, -180f, "R->L", 160, debug),
            "adjustmentUpdate" to TimingPayload.AdjustmentUpdate("run", "finish", 0.55, 6.12, -0.01, "[]")
        )

        payloads.forEachIndexed { index, (expectedCaseName, expectedPayload) ->
            val message = TimingMessage(
                seq = index.toLong(),
                senderId = "sender",
                sessionId = "session",
                payload = expectedPayload,
                createdAtNanos = 123
            )
            val encoded = TimingMessageCodec.encodeToString(message)
            val payloadObject = TimingMessageCodec.json.parseToJsonElement(encoded)
                .jsonObject.getValue("payload").jsonObject

            assertEquals(setOf(expectedCaseName), payloadObject.keys)
            assertEquals(expectedPayload, TimingMessageCodec.decodeFromString(encoded).payload)
        }
    }
}
