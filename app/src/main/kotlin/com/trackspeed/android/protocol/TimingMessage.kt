package com.trackspeed.android.protocol

import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.json.*

// ---------------------------------------------------------------------------
// Protocol version – must match iOS kTimingProtocolVersion
// ---------------------------------------------------------------------------

const val TIMING_PROTOCOL_VERSION: Int = 3

// ---------------------------------------------------------------------------
// TimingRole – mirrors iOS TimingRole enum (raw‐value String Codable)
// ---------------------------------------------------------------------------

@Serializable
enum class TimingRole(val value: String) {
    @SerialName("startLine") START_LINE("startLine"),
    @SerialName("finishLine") FINISH_LINE("finishLine"),
    @SerialName("lapGate") LAP_GATE("lapGate"),
    @SerialName("controlOnly") CONTROL_ONLY("controlOnly");

    val displayName: String
        get() = when (this) {
            START_LINE -> "Start"
            FINISH_LINE -> "Finish"
            LAP_GATE -> "Lap"
            CONTROL_ONLY -> "Control"
        }

    val requiresCamera: Boolean
        get() = this != CONTROL_ONLY
}

// ---------------------------------------------------------------------------
// GateAssignment – mirrors iOS GateAssignment struct
// ---------------------------------------------------------------------------

@Serializable
data class GateAssignment(
    val role: TimingRole,
    val gateIndex: Int,
    val distanceFromStart: Double,
    val targetDeviceId: String? = null
) {
    val displayName: String
        get() = when (role) {
            TimingRole.START_LINE -> "Start"
            TimingRole.FINISH_LINE -> "Finish"
            TimingRole.LAP_GATE -> "Gate $gateIndex"
            TimingRole.CONTROL_ONLY -> "Control"
        }

    companion object {
        fun start() = GateAssignment(TimingRole.START_LINE, 0, 0.0)
        fun finish(gateIndex: Int, distance: Double) =
            GateAssignment(TimingRole.FINISH_LINE, gateIndex, distance)
        fun intermediate(gateIndex: Int, distanceFromStart: Double) =
            GateAssignment(TimingRole.LAP_GATE, gateIndex, distanceFromStart)
    }
}

// ---------------------------------------------------------------------------
// TimingSessionConfig – mirrors iOS TimingSessionConfig struct
// ---------------------------------------------------------------------------

@Serializable
data class TimingSessionConfig(
    val distance: Double,
    val startType: String,
    val numberOfGates: Int,
    val hostRole: TimingRole,
    val fpsMode: Int = 240,
    val protocolVersion: Int = TIMING_PROTOCOL_VERSION
)

// ---------------------------------------------------------------------------
// GateStatusInfo – mirrors iOS GateStatusInfo struct
// ---------------------------------------------------------------------------

@Serializable
data class GateStatusInfo(
    val isCalibrated: Boolean,
    val isArmed: Boolean,
    val isClear: Boolean,
    val isPrebufferReady: Boolean,
    val isStable: Boolean,
    val gatePosition: Double,
    val batteryLevel: Int? = null
) {
    val canArm: Boolean get() = isCalibrated && isClear && isPrebufferReady && isStable
    val isReady: Boolean get() = canArm && isArmed
}

// ---------------------------------------------------------------------------
// SegmentSplit – mirrors iOS SegmentSplit struct
// ---------------------------------------------------------------------------

@Serializable
data class SegmentSplit(
    val fromGateIndex: Int,
    val toGateIndex: Int,
    val fromGateId: String,
    val toGateId: String,
    val splitNanos: Long,
    val distanceMeters: Double,
    val cumulativeSplitNanos: Long,
    val cumulativeDistanceMeters: Double
) {
    val speedMps: Double
        get() {
            if (splitNanos <= 0) return 0.0
            val seconds = splitNanos.toDouble() / 1_000_000_000.0
            return distanceMeters / seconds
        }
}

// ---------------------------------------------------------------------------
// SyncableTimingEvent – mirrors iOS SyncableTimingEvent struct
// ---------------------------------------------------------------------------

@Serializable
data class SyncableTimingEvent(
    val eventId: String,
    val eventType: String,
    val gateId: String,
    val gateIndex: Int,
    val timestampNanos: Long,
    val splitNanos: Long? = null,
    val uncertaintyMs: Double? = null,
    val seq: Long
)

// ---------------------------------------------------------------------------
// TimingPayload – sealed hierarchy matching iOS Payload enum
//
// Swift's auto-synthesized Codable for enums with associated values encodes as:
//   { "caseName": { "label1": val1, "label2": val2, ... } }
// Unit cases (no associated values) encode as:
//   { "caseName": {} }
//
// kotlinx.serialization's default polymorphic encoding uses a "type"
// discriminator which does NOT match Swift's format. We use a custom
// serializer (TimingPayloadSerializer) to produce the correct wire format.
// ---------------------------------------------------------------------------

@Serializable(with = TimingPayloadSerializer::class)
sealed class TimingPayload {

    // ── Handshake Messages ──────────────────────────────────────────────

    @Serializable
    data class SessionConfig(
        val config: TimingSessionConfig
    ) : TimingPayload()

    @Serializable
    class SessionConfigAck : TimingPayload() {
        override fun equals(other: Any?) = other is SessionConfigAck
        override fun hashCode() = "SessionConfigAck".hashCode()
    }

    @Serializable
    data class RoleRequest(
        val preferredRole: TimingRole? = null,
        val deviceId: String
    ) : TimingPayload()

    @Serializable
    data class RoleAssigned(
        val role: TimingRole,
        val targetDeviceId: String? = null
    ) : TimingPayload()

    @Serializable
    data class GateAssigned(
        val assignment: GateAssignment
    ) : TimingPayload()

    @Serializable
    data class RoleAssignedAck(
        val role: TimingRole
    ) : TimingPayload()

    @Serializable
    data class GateAssignedAck(
        val gateIndex: Int
    ) : TimingPayload()

    // ── ACK / Retry Messages ────────────────────────────────────────────

    @Serializable
    data class Ack(
        val messageId: String
    ) : TimingPayload()

    @Serializable
    data class Nack(
        val messageId: String? = null,
        val reason: String
    ) : TimingPayload()

    // ── Heartbeat Messages ──────────────────────────────────────────────

    @Serializable
    class HeartbeatPing : TimingPayload() {
        override fun equals(other: Any?) = other is HeartbeatPing
        override fun hashCode() = "HeartbeatPing".hashCode()
    }

    @Serializable
    data class HeartbeatPong(
        val pingSeq: Long
    ) : TimingPayload()

    // ── Clock Sync Messages ─────────────────────────────────────────────

    @Serializable
    data class SyncPing(
        val pingId: String,
        val t1Nanos: Long,
        val requesterId: String
    ) : TimingPayload()

    @Serializable
    data class SyncPong(
        val pingId: String,
        val t1Nanos: Long,
        val t2Nanos: Long,
        val t3Nanos: Long,
        val requesterId: String
    ) : TimingPayload()

    // ── Session Control Messages ────────────────────────────────────────

    @Serializable
    data class RoleConfirmed(
        val role: TimingRole
    ) : TimingPayload()

    @Serializable
    class SyncRequest : TimingPayload() {
        override fun equals(other: Any?) = other is SyncRequest
        override fun hashCode() = "SyncRequest".hashCode()
    }

    @Serializable
    data class SyncComplete(
        val offsetNanos: Long,
        val uncertaintyMs: Double
    ) : TimingPayload()

    @Serializable
    data class Countdown(
        val remaining: Int,
        val distance: Double? = null
    ) : TimingPayload()

    @Serializable
    class Armed : TimingPayload() {
        override fun equals(other: Any?) = other is Armed
        override fun hashCode() = "Armed".hashCode()
    }

    // ── Timing Events ───────────────────────────────────────────────────

    @Serializable
    data class StartEvent(
        val monotonicNanos: Long,
        val thumbnailData: String? = null
    ) : TimingPayload()

    @Serializable
    data class FinishResult(
        val splitNanos: Long,
        val uncertaintyMs: Double,
        val imageData: String? = null
    ) : TimingPayload()

    @Serializable
    data class Abort(
        val reason: String
    ) : TimingPayload()

    @Serializable
    class NewRun : TimingPayload() {
        override fun equals(other: Any?) = other is NewRun
        override fun hashCode() = "NewRun".hashCode()
    }

    @Serializable
    class CancelRun : TimingPayload() {
        override fun equals(other: Any?) = other is CancelRun
        override fun hashCode() = "CancelRun".hashCode()
    }

    @Serializable
    data class SessionEnded(
        val reason: String
    ) : TimingPayload()

    @Serializable
    class CalibrateRequest : TimingPayload() {
        override fun equals(other: Any?) = other is CalibrateRequest
        override fun hashCode() = "CalibrateRequest".hashCode()
    }

    // ── Host-Controlled Session Messages ────────────────────────────────

    @Serializable
    class StartTiming : TimingPayload() {
        override fun equals(other: Any?) = other is StartTiming
        override fun hashCode() = "StartTiming".hashCode()
    }

    @Serializable
    class CalibrateAll : TimingPayload() {
        override fun equals(other: Any?) = other is CalibrateAll
        override fun hashCode() = "CalibrateAll".hashCode()
    }

    @Serializable
    data class CalibrationStatus(
        val gateId: String,
        val success: Boolean,
        val error: String? = null
    ) : TimingPayload()

    @Serializable
    class ArmAll : TimingPayload() {
        override fun equals(other: Any?) = other is ArmAll
        override fun hashCode() = "ArmAll".hashCode()
    }

    @Serializable
    data class ArmedAck(
        val gateId: String,
        val role: TimingRole
    ) : TimingPayload()

    @Serializable
    class DisarmAll : TimingPayload() {
        override fun equals(other: Any?) = other is DisarmAll
        override fun hashCode() = "DisarmAll".hashCode()
    }

    @Serializable
    data class StartRun(
        val countdownSeconds: Int
    ) : TimingPayload()

    @Serializable
    data class GateStatus(
        val gateId: String,
        val status: GateStatusInfo
    ) : TimingPayload()

    @Serializable
    data class CrossingEvent(
        val gateId: String,
        val role: TimingRole,
        val gateIndex: Int,
        val timestampNanos: Long,
        val confidence: Double,
        val thumbnailData: String? = null
    ) : TimingPayload()

    @Serializable
    data class TimingResultBroadcast(
        val splitNanos: Long,
        val uncertaintyMs: Double,
        val startGateId: String,
        val finishGateId: String
    ) : TimingPayload()

    @Serializable
    data class MultiGateResult(
        val totalSplitNanos: Long,
        val segments: List<SegmentSplit>,
        val uncertaintyMs: Double
    ) : TimingPayload()

    @Serializable
    data class AdjustGateLine(
        val gateId: String,
        val position: Double
    ) : TimingPayload()

    // ── Supabase Hybrid Messages ────────────────────────────────────────

    @Serializable
    data class SupabaseSession(
        val sessionId: String
    ) : TimingPayload()

    @Serializable
    data class HybridSessionInfo(
        val sessionId: String,
        val clockOffsetNanos: Long,
        val uncertaintyMs: Double
    ) : TimingPayload()

    // ── Mid-Session Configuration Changes ───────────────────────────────

    @Serializable
    data class StartTypeChanged(
        val startType: String
    ) : TimingPayload()

    @Serializable
    data class DistanceConfigChanged(
        val gateDistances: Map<Int, Double>
    ) : TimingPayload()

    @Serializable
    class PauseDetection : TimingPayload() {
        override fun equals(other: Any?) = other is PauseDetection
        override fun hashCode() = "PauseDetection".hashCode()
    }

    @Serializable
    class ResumeDetection : TimingPayload() {
        override fun equals(other: Any?) = other is ResumeDetection
        override fun hashCode() = "ResumeDetection".hashCode()
    }

    // ── Debug Messages ──────────────────────────────────────────────────

    @Serializable
    data class DebugPing(
        val timestamp: Long
    ) : TimingPayload()

    @Serializable
    data class DebugPong(
        val originalTimestamp: Long
    ) : TimingPayload()

    // ── Audio Sync Messages ─────────────────────────────────────────────

    @Serializable
    data class AudioSyncData(
        val json: String
    ) : TimingPayload()

    // ── Decoupled Thumbnail Messages ────────────────────────────────────

    @Serializable
    data class ThumbnailUpdate(
        val eventId: String,
        val gateId: String,
        val role: TimingRole,
        val thumbnailData: String
    ) : TimingPayload()

    // ── Event Reconciliation Messages ───────────────────────────────────

    @Serializable
    data class EventSync(
        val lastSeenEventId: String? = null,
        val runId: String
    ) : TimingPayload()

    @Serializable
    data class EventSyncResponse(
        val events: List<SyncableTimingEvent>,
        val fromEventId: String? = null
    ) : TimingPayload()

    // ── Config Ordering Messages ────────────────────────────────────────

    @Serializable
    data class ConfigVersion(
        val version: Long,
        val configType: String
    ) : TimingPayload()
}

// ---------------------------------------------------------------------------
// TimingPayloadSerializer
//
// Produces JSON matching Swift's auto-synthesized Codable for enums:
//   { "caseName": { "field1": val1, ... } }
// Unit cases with no fields:
//   { "caseName": {} }
// ---------------------------------------------------------------------------

@OptIn(ExperimentalSerializationApi::class)
internal object TimingPayloadSerializer : KSerializer<TimingPayload> {

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("TimingPayload")

    /**
     * Maps each Swift case name to a pair of:
     *   - The KClass (for instanceOf checks during serialization)
     *   - The KSerializer for that data class
     */
    private data class PayloadEntry<T : TimingPayload>(
        val caseName: String,
        val klass: Class<out T>,
        val serializer: KSerializer<T>
    )

    // Build the registry once. The order does not matter.
    @Suppress("UNCHECKED_CAST")
    private val entries: List<PayloadEntry<out TimingPayload>> = listOf(
        // Handshake
        entry("sessionConfig", TimingPayload.SessionConfig::class.java, TimingPayload.SessionConfig.serializer()),
        entry("sessionConfigAck", TimingPayload.SessionConfigAck::class.java, TimingPayload.SessionConfigAck.serializer()),
        entry("roleRequest", TimingPayload.RoleRequest::class.java, TimingPayload.RoleRequest.serializer()),
        entry("roleAssigned", TimingPayload.RoleAssigned::class.java, TimingPayload.RoleAssigned.serializer()),
        entry("gateAssigned", TimingPayload.GateAssigned::class.java, TimingPayload.GateAssigned.serializer()),
        entry("roleAssignedAck", TimingPayload.RoleAssignedAck::class.java, TimingPayload.RoleAssignedAck.serializer()),
        entry("gateAssignedAck", TimingPayload.GateAssignedAck::class.java, TimingPayload.GateAssignedAck.serializer()),
        // ACK/Retry
        entry("ack", TimingPayload.Ack::class.java, TimingPayload.Ack.serializer()),
        entry("nack", TimingPayload.Nack::class.java, TimingPayload.Nack.serializer()),
        // Heartbeat
        entry("heartbeatPing", TimingPayload.HeartbeatPing::class.java, TimingPayload.HeartbeatPing.serializer()),
        entry("heartbeatPong", TimingPayload.HeartbeatPong::class.java, TimingPayload.HeartbeatPong.serializer()),
        // Clock Sync
        entry("syncPing", TimingPayload.SyncPing::class.java, TimingPayload.SyncPing.serializer()),
        entry("syncPong", TimingPayload.SyncPong::class.java, TimingPayload.SyncPong.serializer()),
        // Session Control
        entry("roleConfirmed", TimingPayload.RoleConfirmed::class.java, TimingPayload.RoleConfirmed.serializer()),
        entry("syncRequest", TimingPayload.SyncRequest::class.java, TimingPayload.SyncRequest.serializer()),
        entry("syncComplete", TimingPayload.SyncComplete::class.java, TimingPayload.SyncComplete.serializer()),
        entry("countdown", TimingPayload.Countdown::class.java, TimingPayload.Countdown.serializer()),
        entry("armed", TimingPayload.Armed::class.java, TimingPayload.Armed.serializer()),
        // Timing Events
        entry("startEvent", TimingPayload.StartEvent::class.java, TimingPayload.StartEvent.serializer()),
        entry("finishResult", TimingPayload.FinishResult::class.java, TimingPayload.FinishResult.serializer()),
        entry("abort", TimingPayload.Abort::class.java, TimingPayload.Abort.serializer()),
        entry("newRun", TimingPayload.NewRun::class.java, TimingPayload.NewRun.serializer()),
        entry("cancelRun", TimingPayload.CancelRun::class.java, TimingPayload.CancelRun.serializer()),
        entry("sessionEnded", TimingPayload.SessionEnded::class.java, TimingPayload.SessionEnded.serializer()),
        entry("calibrateRequest", TimingPayload.CalibrateRequest::class.java, TimingPayload.CalibrateRequest.serializer()),
        // Host-Controlled
        entry("startTiming", TimingPayload.StartTiming::class.java, TimingPayload.StartTiming.serializer()),
        entry("calibrateAll", TimingPayload.CalibrateAll::class.java, TimingPayload.CalibrateAll.serializer()),
        entry("calibrationStatus", TimingPayload.CalibrationStatus::class.java, TimingPayload.CalibrationStatus.serializer()),
        entry("armAll", TimingPayload.ArmAll::class.java, TimingPayload.ArmAll.serializer()),
        entry("armedAck", TimingPayload.ArmedAck::class.java, TimingPayload.ArmedAck.serializer()),
        entry("disarmAll", TimingPayload.DisarmAll::class.java, TimingPayload.DisarmAll.serializer()),
        entry("startRun", TimingPayload.StartRun::class.java, TimingPayload.StartRun.serializer()),
        entry("gateStatus", TimingPayload.GateStatus::class.java, TimingPayload.GateStatus.serializer()),
        entry("crossingEvent", TimingPayload.CrossingEvent::class.java, TimingPayload.CrossingEvent.serializer()),
        entry("timingResultBroadcast", TimingPayload.TimingResultBroadcast::class.java, TimingPayload.TimingResultBroadcast.serializer()),
        entry("multiGateResult", TimingPayload.MultiGateResult::class.java, TimingPayload.MultiGateResult.serializer()),
        entry("adjustGateLine", TimingPayload.AdjustGateLine::class.java, TimingPayload.AdjustGateLine.serializer()),
        // Supabase
        entry("supabaseSession", TimingPayload.SupabaseSession::class.java, TimingPayload.SupabaseSession.serializer()),
        entry("hybridSessionInfo", TimingPayload.HybridSessionInfo::class.java, TimingPayload.HybridSessionInfo.serializer()),
        // Mid-session config
        entry("startTypeChanged", TimingPayload.StartTypeChanged::class.java, TimingPayload.StartTypeChanged.serializer()),
        entry("distanceConfigChanged", TimingPayload.DistanceConfigChanged::class.java, TimingPayload.DistanceConfigChanged.serializer()),
        entry("pauseDetection", TimingPayload.PauseDetection::class.java, TimingPayload.PauseDetection.serializer()),
        entry("resumeDetection", TimingPayload.ResumeDetection::class.java, TimingPayload.ResumeDetection.serializer()),
        // Debug
        entry("debugPing", TimingPayload.DebugPing::class.java, TimingPayload.DebugPing.serializer()),
        entry("debugPong", TimingPayload.DebugPong::class.java, TimingPayload.DebugPong.serializer()),
        // Audio Sync
        entry("audioSyncData", TimingPayload.AudioSyncData::class.java, TimingPayload.AudioSyncData.serializer()),
        // Thumbnail
        entry("thumbnailUpdate", TimingPayload.ThumbnailUpdate::class.java, TimingPayload.ThumbnailUpdate.serializer()),
        // Event Reconciliation
        entry("eventSync", TimingPayload.EventSync::class.java, TimingPayload.EventSync.serializer()),
        entry("eventSyncResponse", TimingPayload.EventSyncResponse::class.java, TimingPayload.EventSyncResponse.serializer()),
        // Config Ordering
        entry("configVersion", TimingPayload.ConfigVersion::class.java, TimingPayload.ConfigVersion.serializer()),
    )

    private fun <T : TimingPayload> entry(
        caseName: String,
        klass: Class<T>,
        serializer: KSerializer<T>
    ) = PayloadEntry(caseName, klass, serializer)

    // Lookup by case name for deserialization
    private val byName: Map<String, PayloadEntry<out TimingPayload>> =
        entries.associateBy { it.caseName }

    // Lookup by class for serialization
    private val byClass: Map<Class<out TimingPayload>, PayloadEntry<out TimingPayload>> =
        entries.associateBy { it.klass }

    override fun serialize(encoder: Encoder, value: TimingPayload) {
        require(encoder is JsonEncoder) { "TimingPayload can only be serialized to JSON" }
        val entry = byClass[value::class.java]
            ?: throw SerializationException("Unknown TimingPayload subclass: ${value::class.java.name}")

        @Suppress("UNCHECKED_CAST")
        val typedSerializer = entry.serializer as KSerializer<TimingPayload>
        val innerJson = encoder.json.encodeToJsonElement(typedSerializer, value)
        val wrapper = buildJsonObject {
            put(entry.caseName, innerJson)
        }
        encoder.encodeJsonElement(wrapper)
    }

    override fun deserialize(decoder: Decoder): TimingPayload {
        require(decoder is JsonDecoder) { "TimingPayload can only be deserialized from JSON" }
        val jsonObject = decoder.decodeJsonElement().jsonObject

        if (jsonObject.size != 1) {
            throw SerializationException(
                "Expected exactly one key in payload object, got ${jsonObject.keys}"
            )
        }

        val (caseName, innerElement) = jsonObject.entries.first()
        val entry = byName[caseName]
            ?: throw SerializationException("Unknown payload case: '$caseName'")

        return decoder.json.decodeFromJsonElement(entry.serializer, innerElement)
    }
}

// ---------------------------------------------------------------------------
// TimingMessage – the outer envelope matching iOS TimingMessage struct
// ---------------------------------------------------------------------------

@Serializable
data class TimingMessage(
    val protocolVersion: Int = TIMING_PROTOCOL_VERSION,
    val seq: Long,
    val senderId: String,
    val sessionId: String,
    val messageId: String? = null,
    val eventId: String? = null,
    val payload: TimingPayload,
    val createdAtNanos: Long
) {
    /**
     * Whether this message requires an ACK from the receiver.
     */
    val requiresAck: Boolean
        get() {
            if (messageId == null) return false
            return when (payload) {
                is TimingPayload.SessionConfig,
                is TimingPayload.RoleRequest,
                is TimingPayload.RoleAssigned,
                is TimingPayload.GateAssigned,
                is TimingPayload.Armed,
                is TimingPayload.StartEvent,
                is TimingPayload.FinishResult,
                is TimingPayload.CalibrateAll,
                is TimingPayload.ArmAll,
                is TimingPayload.DisarmAll,
                is TimingPayload.CrossingEvent,
                is TimingPayload.TimingResultBroadcast,
                is TimingPayload.MultiGateResult,
                is TimingPayload.SupabaseSession,
                is TimingPayload.NewRun,
                is TimingPayload.CancelRun,
                is TimingPayload.ResumeDetection,
                is TimingPayload.PauseDetection -> true
                else -> false
            }
        }

    companion object {
        /**
         * Create a non-critical timing message (no messageId, no ACK needed).
         */
        fun create(
            seq: Long,
            senderId: String,
            sessionId: String,
            payload: TimingPayload,
            eventId: String? = null,
            createdAtNanos: Long = monotonicNanos()
        ) = TimingMessage(
            protocolVersion = TIMING_PROTOCOL_VERSION,
            seq = seq,
            senderId = senderId,
            sessionId = sessionId,
            messageId = null,
            eventId = eventId,
            payload = payload,
            createdAtNanos = createdAtNanos
        )

        /**
         * Create a critical timing message that requires ACK/retry.
         */
        fun createCritical(
            seq: Long,
            senderId: String,
            sessionId: String,
            payload: TimingPayload,
            eventId: String? = null,
            createdAtNanos: Long = monotonicNanos()
        ) = TimingMessage(
            protocolVersion = TIMING_PROTOCOL_VERSION,
            seq = seq,
            senderId = senderId,
            sessionId = sessionId,
            messageId = java.util.UUID.randomUUID().toString().uppercase(),
            eventId = eventId,
            payload = payload,
            createdAtNanos = createdAtNanos
        )

        /**
         * Generate a stable eventId for cross-transport deduplication.
         * Format: "runId-gateId-timestampNanos"
         */
        fun generateEventId(runId: String, gateId: String, timestampNanos: Long): String {
            val runPrefix = if (runId.length >= 8) runId.substring(0, 8) else runId
            val gatePrefix = if (gateId.length >= 8) gateId.substring(0, 8) else gateId
            return "$runPrefix-$gatePrefix-$timestampNanos"
        }

        /**
         * Current monotonic time in nanoseconds.
         * Uses elapsedRealtimeNanos on Android (survives deep sleep).
         */
        fun monotonicNanos(): Long {
            return android.os.SystemClock.elapsedRealtimeNanos()
        }
    }
}

// ---------------------------------------------------------------------------
// TimingMessageCodec – encode/decode helpers
// ---------------------------------------------------------------------------

object TimingMessageCodec {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    /**
     * Encode a TimingMessage to a JSON string.
     */
    fun encodeToString(message: TimingMessage): String {
        return json.encodeToString(TimingMessage.serializer(), message)
    }

    /**
     * Encode a TimingMessage to UTF-8 JSON bytes.
     */
    fun encodeToBytes(message: TimingMessage): ByteArray {
        return encodeToString(message).toByteArray(Charsets.UTF_8)
    }

    /**
     * Decode a TimingMessage from a JSON string.
     */
    fun decodeFromString(jsonString: String): TimingMessage {
        return json.decodeFromString(TimingMessage.serializer(), jsonString)
    }

    /**
     * Decode a TimingMessage from UTF-8 JSON bytes.
     */
    fun decodeFromBytes(bytes: ByteArray): TimingMessage {
        return decodeFromString(bytes.toString(Charsets.UTF_8))
    }
}
