package com.trackspeed.android.diagnostics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Deliberately no arbitrary strings, maps, radio identifiers, names or exceptions. */
enum class ConnectionDiagnosticFlow { CONNECTION, HOST, JOIN, CLOCK_SYNC, RECONNECT }
enum class ConnectionDiagnosticRole { HOST, CLIENT, REFERENCE, REQUESTER, START, FINISH, SPLIT, UNKNOWN }
enum class ConnectionDiagnosticTransport { BLE, WIFI_AWARE, UNKNOWN }
enum class ConnectionDiagnosticPhase { ATTEMPT, CAPABILITY, PERMISSION, DISCOVERY, LINK, GATT, HANDSHAKE, DATA_PATH, SOCKET, CLOCK_SYNC, STATE, INTERNET }
enum class ConnectionDiagnosticOutcome { STARTED, WAITING, READY, CONNECTED, DISCONNECTED, RECONNECTING, RECOVERED, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT, UNAVAILABLE, FALLBACK }
enum class ConnectionDiagnosticErrorDomain { CAPABILITY, PERMISSION, BLUETOOTH, GATT, WIFI_AWARE, SOCKET, PROTOCOL, CLOCK_SYNC, LIFECYCLE, UNKNOWN }
enum class ConnectionDiagnosticErrorCode { UNSUPPORTED, DISABLED, PERMISSION_DENIED, CIPHER_UNAVAILABLE, ATTACH_FAILED, CONFIG_FAILED, SESSION_TERMINATED, DISCOVERY_TIMEOUT, NETWORK_UNAVAILABLE, NETWORK_INFO_PENDING, NETWORK_LOST, SOCKET_FAILED, EXCHANGE_TIMEOUT, GATT_STATUS, HANDSHAKE_REJECTED, QUALITY_REJECTED, CANCELLED, UNKNOWN }
enum class ConnectionDiagnosticNetwork { NONE, WIFI, CELLULAR, ETHERNET, OTHER, UNKNOWN }
enum class ConnectionDiagnosticEstimator { NTP, ONE_WAY_BOUND, WIFI_AWARE, UNKNOWN }

/** Upload-only gate: overlapping/cancelled samplers release only their own token. */
class ConnectionDiagnosticSamplingGate(
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onIdle: () -> Unit = {}
) {
    private val counter = AtomicLong()
    private val active = mutableMapOf<Long, Long>()
    private fun expireHolds() {
        val now = monotonicMillis()
        active.entries.removeAll { it.value != Long.MAX_VALUE && it.value <= now }
    }
    val isActive: Boolean get() = synchronized(active) { expireHolds(); active.isNotEmpty() }
    fun isActive(token: Long): Boolean = synchronized(active) { expireHolds(); active.containsKey(token) }
    fun begin(maxHoldMs: Long? = null): Long = synchronized(active) {
        expireHolds()
        counter.incrementAndGet().also { token ->
            active[token] = maxHoldMs?.let { monotonicMillis() + it.coerceIn(1, 3_600_000) } ?: Long.MAX_VALUE
        }
    }
    fun end(token: Long) {
        val becameIdle = synchronized(active) { active.remove(token) != null && active.isEmpty() }
        if (becameIdle) try { onIdle() } catch (_: Exception) { }
    }
}

/** Small observational context shared by outer connection/sync boundaries, never timestamp capture. */
class ConnectionDiagnosticAttempt(
    val flow: ConnectionDiagnosticFlow,
    private val monotonicNanos: () -> Long,
    private val sink: (ConnectionDiagnosticEvent) -> Unit,
    val id: UUID = UUID.randomUUID()
) {
    private val startedAt = monotonicNanos()
    private val sequence = AtomicInteger()

    fun record(
        phase: ConnectionDiagnosticPhase,
        outcome: ConnectionDiagnosticOutcome,
        transport: ConnectionDiagnosticTransport = ConnectionDiagnosticTransport.UNKNOWN,
        sessionId: UUID? = null,
        enrich: (ConnectionDiagnosticEvent) -> ConnectionDiagnosticEvent = { it }
    ) {
        // A telemetry sink cannot fail, cancel, or otherwise affect the observed protocol operation.
        try {
            val event = ConnectionDiagnosticEvent(
                attemptId = id, flow = flow, transport = transport, phase = phase, outcome = outcome,
                sessionId = sessionId, sequence = sequence.getAndIncrement(),
                elapsedMs = ((monotonicNanos() - startedAt).coerceAtLeast(0) / 1_000_000)
            )
            sink(enrich(event))
        } catch (_: Exception) { }
    }
}

data class ConnectionDiagnosticEvent(
    val attemptId: UUID,
    val flow: ConnectionDiagnosticFlow,
    val transport: ConnectionDiagnosticTransport,
    val phase: ConnectionDiagnosticPhase,
    val outcome: ConnectionDiagnosticOutcome,
    val id: UUID = UUID.randomUUID(),
    val sessionId: UUID? = null,
    val role: ConnectionDiagnosticRole? = null,
    val occurredAt: Instant = Instant.now(),
    /** Supplied by the caller's monotonic clock; never computed from occurredAt. */
    val elapsedMs: Long? = null,
    val sequence: Int = 0,
    val retry: Int = 0,
    val timeoutMs: Int? = null,
    val errorDomain: ConnectionDiagnosticErrorDomain? = null,
    val errorCode: ConnectionDiagnosticErrorCode? = null,
    val nativeStatus: Int? = null,
    val capabilitySupported: Boolean? = null,
    val radioAvailable: Boolean? = null,
    val permissionGranted: Boolean? = null,
    val peerCapability: Boolean? = null,
    val network: ConnectionDiagnosticNetwork = ConnectionDiagnosticNetwork.UNKNOWN,
    val internetValidated: Boolean? = null,
    val metered: Boolean? = null,
    val uncertaintyMs: Double? = null,
    val minRttMs: Double? = null,
    val jitterMs: Double? = null,
    val acceptedSamples: Int? = null,
    val rejectedSamples: Int? = null,
    val estimator: ConnectionDiagnosticEstimator? = null
) {
    fun bind(ownerId: UUID, environment: ConnectionDiagnosticEnvironment): ConnectionDiagnosticRecord =
        ConnectionDiagnosticRecord(
            id = id.toString(), userId = ownerId.toString(), attemptId = attemptId.toString(),
            // Schema requires device_id; use the random local attempt token, never stable device identity.
            sessionId = sessionId?.toString(), deviceId = attemptId.toString(),
            flow = flow.name.lowercase(), localRole = role?.name?.lowercase(),
            transport = transport.name.lowercase(), phase = phase.name.lowercase(),
            outcome = outcome.name.lowercase(), reason = errorCode?.name?.lowercase(),
            severity = when (outcome) {
                ConnectionDiagnosticOutcome.FAILED -> "error"
                ConnectionDiagnosticOutcome.TIMED_OUT, ConnectionDiagnosticOutcome.UNAVAILABLE,
                ConnectionDiagnosticOutcome.FALLBACK -> "warning"
                else -> "info"
            },
            durationMs = elapsedMs?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt(),
            retryCount = retry.coerceAtLeast(0), hasInternet = internetValidated,
            appVersion = safeLabel(environment.appVersion), appBuild = environment.appBuild.coerceAtLeast(0).toString(),
            deviceModel = safeLabel(environment.deviceModel), localCreatedAt = occurredAt.toString(),
            metadata = ConnectionDiagnosticMetadata(
                osVersion = safeLabel(environment.osVersion), androidSdk = environment.androidSdk.coerceAtLeast(0),
                buildMarker = safeLabel(environment.buildMarker), sourceFingerprint = safeLabel(environment.sourceFingerprint),
                sequence = sequence.coerceAtLeast(0), timeoutMs = timeoutMs?.coerceAtLeast(0),
                errorDomain = errorDomain?.name?.lowercase(), errorCode = errorCode?.name?.lowercase(),
                nativeStatus = nativeStatus, capabilitySupported = capabilitySupported,
                radioAvailable = radioAvailable, permissionGranted = permissionGranted,
                peerCapability = peerCapability, network = network.name.lowercase(), metered = metered,
                uncertaintyMs = uncertaintyMs.safeMetric(), minRttMs = minRttMs.safeMetric(), jitterMs = jitterMs.safeMetric(),
                acceptedSamples = acceptedSamples?.coerceAtLeast(0), rejectedSamples = rejectedSamples?.coerceAtLeast(0),
                estimator = estimator?.name?.lowercase()
            )
        )

    companion object {
        private fun Double?.safeMetric(): Double? = this?.takeIf { it.isFinite() && it >= 0 && it <= 3_600_000 }
        internal fun safeLabel(value: String): String = value.takeIf {
            it.length in 1..64 && it.matches(Regex("[A-Za-z0-9 ._()-]+")) &&
                !Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b").containsMatchIn(it) &&
                !Regex("\\b(?:bearer|token|password|secret)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) &&
                !Regex("[A-Za-z0-9_-]{40,}").containsMatchIn(it)
        } ?: "unknown"
    }
}

/** These labels come from Build/BuildConfig only, never Settings device names. */
data class ConnectionDiagnosticEnvironment(
    val appVersion: String, val appBuild: Int, val deviceModel: String,
    val osVersion: String, val androidSdk: Int, val buildMarker: String, val sourceFingerprint: String
)

/** Subset of the existing iOS table contract. Intentionally omits ios_version and all peer/name columns. */
@Serializable
@ConsistentCopyVisibility
data class ConnectionDiagnosticRecord internal constructor(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("attempt_id") val attemptId: String,
    @SerialName("session_id") val sessionId: String?,
    @SerialName("device_id") val deviceId: String,
    val flow: String,
    @SerialName("local_role") val localRole: String?,
    val transport: String,
    val phase: String,
    val outcome: String,
    val reason: String?,
    val severity: String,
    @SerialName("duration_ms") val durationMs: Int?,
    @SerialName("retry_count") val retryCount: Int,
    @SerialName("has_internet") val hasInternet: Boolean?,
    @SerialName("app_version") val appVersion: String,
    @SerialName("app_build") val appBuild: String,
    @SerialName("device_model") val deviceModel: String,
    @SerialName("local_created_at") val localCreatedAt: String,
    val metadata: ConnectionDiagnosticMetadata
) {
    /** Revalidate persisted data; a corrupt outbox must not turn into a free-form telemetry path. */
    internal fun isSanitized(): Boolean = runCatching {
        fun uuid(value: String) = UUID.fromString(value).toString() == value
        fun label(value: String) = ConnectionDiagnosticEvent.safeLabel(value) == value
        fun metric(value: Double?) = value == null || (value.isFinite() && value in 0.0..3_600_000.0)
        require(uuid(id) && uuid(userId) && uuid(attemptId) && (sessionId == null || uuid(sessionId)))
        require(uuid(deviceId) && deviceId == attemptId)
        require(ConnectionDiagnosticFlow.entries.any { it.name.lowercase() == flow })
        require(localRole == null || ConnectionDiagnosticRole.entries.any { it.name.lowercase() == localRole })
        require(ConnectionDiagnosticTransport.entries.any { it.name.lowercase() == transport })
        require(ConnectionDiagnosticPhase.entries.any { it.name.lowercase() == phase })
        require(ConnectionDiagnosticOutcome.entries.any { it.name.lowercase() == outcome })
        require(reason == null || ConnectionDiagnosticErrorCode.entries.any { it.name.lowercase() == reason })
        require(severity in setOf("info", "warning", "error") && (durationMs == null || durationMs >= 0) && retryCount >= 0)
        require(label(appVersion) && label(deviceModel) && appBuild.toIntOrNull()?.let { it >= 0 } == true)
        Instant.parse(localCreatedAt)
        with(metadata) {
            require(schemaVersion == 1 && platform == "android" && androidSdk >= 0)
            require(label(osVersion) && label(buildMarker) && label(sourceFingerprint))
            require(sequence >= 0 && (timeoutMs == null || timeoutMs >= 0))
            require(errorDomain == null || ConnectionDiagnosticErrorDomain.entries.any { it.name.lowercase() == errorDomain })
            require(errorCode == null || ConnectionDiagnosticErrorCode.entries.any { it.name.lowercase() == errorCode })
            require(ConnectionDiagnosticNetwork.entries.any { it.name.lowercase() == network })
            require(estimator == null || ConnectionDiagnosticEstimator.entries.any { it.name.lowercase() == estimator })
            require(metric(uncertaintyMs) && metric(minRttMs) && metric(jitterMs))
            require((acceptedSamples == null || acceptedSamples >= 0) && (rejectedSamples == null || rejectedSamples >= 0))
        }
        true
    }.getOrDefault(false)
}

@Serializable
@ConsistentCopyVisibility
data class ConnectionDiagnosticMetadata internal constructor(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val platform: String = "android",
    @SerialName("os_version") val osVersion: String,
    @SerialName("android_sdk") val androidSdk: Int,
    @SerialName("build_marker") val buildMarker: String,
    @SerialName("source_fingerprint") val sourceFingerprint: String,
    val sequence: Int,
    @SerialName("timeout_ms") val timeoutMs: Int?,
    @SerialName("error_domain") val errorDomain: String?,
    @SerialName("error_code") val errorCode: String?,
    @SerialName("native_status") val nativeStatus: Int?,
    @SerialName("capability_supported") val capabilitySupported: Boolean?,
    @SerialName("radio_available") val radioAvailable: Boolean?,
    @SerialName("permission_granted") val permissionGranted: Boolean?,
    @SerialName("peer_capability") val peerCapability: Boolean?,
    val network: String,
    val metered: Boolean?,
    @SerialName("uncertainty_ms") val uncertaintyMs: Double?,
    @SerialName("min_rtt_ms") val minRttMs: Double?,
    @SerialName("jitter_ms") val jitterMs: Double?,
    @SerialName("accepted_samples") val acceptedSamples: Int?,
    @SerialName("rejected_samples") val rejectedSamples: Int?,
    val estimator: String?
)
