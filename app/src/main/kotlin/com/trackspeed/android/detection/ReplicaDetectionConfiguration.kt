package com.trackspeed.android.detection

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Validated, data-only detector profile shared with iOS `replica_v1`.
 * Supabase can tune reviewed parameters, but cannot download executable code.
 */
data class ReplicaDetectionConfiguration(
    val revision: String,
    val source: String,
    val parameters: Parameters
) {
    data class Parameters(
        var diffThreshold: Int = 15,
        var heightFraction: Float = 0.35f,
        var widthFraction: Float = 0.08f,
        var localSupportFraction: Float = 0.25f,
        var minFillRatio: Float = 0.20f,
        var maxAspectRatio: Float = 1.2f,
        var minFillRatioLenient: Float = 0.12f,
        var maxAspectRatioLenient: Float = 1.7f,
        var spikeRatioThreshold: Float = 1.5f,
        var warmupFrames: Int = 10,
        var torsoFraction: Float = 0.30f,
        var useLeadingEdgeTrigger: Boolean = true,
        var torsoRunAbsMin: Int = 30,
        var torsoRunAbsMax: Int = 55,
        var torsoRunHeightFraction: Float = 0.25f,
        var gateRunMergeMaxGap: Int = 2,
        var flashGuardCoverage: Float = 0.55f,
        var flashGuardFill: Float = 0.70f,
        var flashGuardWidthFraction: Float = 0.80f,
        var flashGuardHeightFraction: Float = 0.80f,
        var fullWidthBandWidthFraction: Float = 0.95f,
        var gateBandHalfWidth: Int = 2,
        var thickGateHalfWidth: Int = 4,
        var limbWaitReleaseAfter: Int = 3,
        var cooldownSeconds: Double = 0.3,
        var sparseStartupSceneMotionFrameLimit: Int = 90,
        var sparseStartupSceneMotionMaxGateWidth: Int = 8,
        var thinSceneMotionMaxBuildup: Int = 1,
        var thinSceneMotionMaxStripWidth: Float = 1f,
        var thinSceneMotionMaxHorizontalRun: Int = 2,
        var thinSceneMotionMaxGateWidth: Int = 1,
        var thinSceneMotionMinBlobHeightFraction: Float = 0.55f,
        var thinSceneMotionMinTorsoDistance: Int = 10,
        var noTorsoTinyGateRowGuardEnabled: Boolean = true,
        var noTorsoTinyGateRowMaxGateWidth: Int = 2,
        var noTorsoTinyGateRowMaxStripWidth: Float = 2f,
        var noTorsoTinyGateRowMaxHorizontalRun: Int = 3,
        var noTorsoTinyGateRowMinBlobHeightFraction: Float = 0.55f,
        var incoherentSceneMotionGuardEnabled: Boolean = true,
        var sceneMotionMinWidthFraction: Float = 0.80f,
        var sceneMotionMinTorsoFragments: Int = 24,
        var sceneMotionWeakMaxGateWidth: Int = 8,
        var sceneMotionWeakMaxStripWidth: Float = 8f,
        var sceneMotionWeakMaxHorizontalRun: Int = 8,
        var sceneMotionBroadShadowMinWidthFraction: Float = 0.90f,
        var sceneMotionBroadShadowMaxGateWidth: Int = 14,
        var sceneMotionBroadShadowMaxStripWidth: Float = 14f,
        var sceneMotionBroadShadowMaxHorizontalRun: Int = 14,
        var xAnchorSelectorV3Enabled: Boolean = true,
        var motionDirectionMinDeltaPixels: Float = 4f,
        var motionDirectionHistoryMaxAgeFrames: Int = 8,
        var motionDirectionHistoryLength: Int = 8,
        var lowContrastBodyFallbackEnabled: Boolean = true,
        var lowContrastBodyFallbackMinMergedRun: Int = 18,
        var lowContrastBodyFallbackMinLocalRun: Int = 18,
        var lowContrastBodyFallbackMinGateBandColumns: Int = 3,
        var lowContrastBodyFallbackMinGateBandPixels: Int = 45,
        var lowContrastBodyFallbackMinSequenceFrames: Int = 2,
        var lowContrastBodyFallbackMinBlobHeightFraction: Float = 0.40f,
        var lowContrastBodyFallbackMinBlobWidthFraction: Float = 0.10f,
        var lowContrastBodyFallbackMaxBlobWidthFraction: Float = 0.88f,
        var lowContrastBodyFallbackUpperZoneFraction: Float = 0.55f
    )

    class ValidationException(message: String) : IllegalArgumentException(message)

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
        const val SUPPORTED_PIPELINE = "replica_v1"
        const val REMOTE_CONFIG_KEY = "replica_detection_profile_v1"

        val bundled: ReplicaDetectionConfiguration
            get() = ReplicaDetectionConfiguration(
                revision = "bundled-2026-07-21",
                source = "bundled",
                parameters = Parameters()
            )

        fun resolveRemoteJson(
            rawJson: String,
            appVersion: String,
            rolloutBucket: Int,
            nowEpochMillis: Long = System.currentTimeMillis()
        ): ReplicaDetectionConfiguration? {
            val payload = try {
                Json.parseToJsonElement(rawJson).jsonObject
            } catch (error: Exception) {
                throw ValidationException("Invalid JSON: ${error.message}")
            }

            val schema = payload.requiredInt("schemaVersion")
            if (schema != SUPPORTED_SCHEMA_VERSION) {
                throw ValidationException("Unsupported schemaVersion $schema")
            }
            val pipeline = payload.requiredString("pipeline")
            if (pipeline != SUPPORTED_PIPELINE) {
                throw ValidationException("Unsupported pipeline $pipeline")
            }
            val revision = payload.requiredString("revision").trim()
            if (revision.length !in 1..80 || !revision.matches(Regex("[A-Za-z0-9._-]+"))) {
                throw ValidationException("Invalid revision")
            }

            payload.optionalBoolean("enabled")?.let { if (!it) return null }
            payload.optionalString("minimumAppVersion")?.let {
                if (compareVersions(appVersion, it) < 0) return null
            }
            payload.optionalString("maximumAppVersion")?.let {
                if (compareVersions(appVersion, it) > 0) return null
            }
            payload.optionalString("expiresAt")?.let { value ->
                val expiration = runCatching { Instant.parse(value).toEpochMilli() }
                    .getOrElse { throw ValidationException("Invalid expiresAt") }
                if (expiration <= nowEpochMillis) return null
            }

            val rollout = payload.optionalDouble("rolloutPercentage") ?: 100.0
            if (!rollout.isFinite() || rollout !in 0.0..100.0) {
                throw ValidationException("Invalid value for rolloutPercentage")
            }
            if (rolloutBucket !in 0 until 10_000) {
                throw ValidationException("Invalid rollout bucket")
            }
            if (rolloutBucket.toDouble() >= rollout * 100.0) return null

            val parametersObject = payload["parameters"]?.let {
                runCatching { it.jsonObject }
                    .getOrElse { throw ValidationException("Invalid value for parameters") }
            } ?: throw ValidationException("Invalid value for parameters")
            val parameters = Parameters()
            parametersObject.forEach { (key, value) ->
                val primitive = runCatching { value.jsonPrimitive }
                    .getOrElse { throw ValidationException("Invalid value for $key") }
                if (primitive.isString) throw ValidationException("Invalid value for $key")
                parameters.applyRemoteValue(key, primitive.booleanOrNull, primitive.doubleOrNull)
            }
            parameters.validateRelationships()
            return ReplicaDetectionConfiguration(revision, "remote", parameters)
        }

        /** FNV-1a, matching iOS byte-for-byte and bounded to 0..<10,000. */
        fun stableRolloutBucket(identifier: String): Int {
            var hash = -3750763034362895579L // UInt64 14695981039346656037
            identifier.toByteArray(Charsets.UTF_8).forEach { byte ->
                hash = hash xor (byte.toLong() and 0xffL)
                hash *= 1_099_511_628_211L
            }
            return java.lang.Long.remainderUnsigned(hash, 10_000L).toInt()
        }

        internal fun compareVersions(left: String, right: String): Int {
            val leftParts = left.split('.').mapNotNull(String::toIntOrNull)
            val rightParts = right.split('.').mapNotNull(String::toIntOrNull)
            if (leftParts.isEmpty() || rightParts.isEmpty()) return left.compareTo(right)
            repeat(maxOf(leftParts.size, rightParts.size)) { index ->
                val l = leftParts.getOrElse(index) { 0 }
                val r = rightParts.getOrElse(index) { 0 }
                if (l != r) return l.compareTo(r)
            }
            return 0
        }
    }
}

object ReplicaDetectionConfigurationStore {
    private val current = AtomicReference(ReplicaDetectionConfiguration.bundled)

    fun snapshot(): ReplicaDetectionConfiguration {
        val configuration = current.get()
        return configuration.copy(parameters = configuration.parameters.copy())
    }

    fun replace(configuration: ReplicaDetectionConfiguration) {
        current.set(configuration.copy(parameters = configuration.parameters.copy()))
    }
}

private fun JsonObject.requiredString(key: String): String =
    optionalString(key) ?: throw ReplicaDetectionConfiguration.ValidationException("Invalid value for $key")

private fun JsonObject.requiredInt(key: String): Int =
    this[key]?.jsonPrimitive?.takeUnless { it.isString }?.intOrNull
        ?: throw ReplicaDetectionConfiguration.ValidationException("Invalid value for $key")

private fun JsonObject.optionalString(key: String): String? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    return runCatching { value.jsonPrimitive }
        .getOrNull()
        ?.takeIf { it.isString }
        ?.content
        ?: throw ReplicaDetectionConfiguration.ValidationException("Invalid value for $key")
}

private fun JsonObject.optionalBoolean(key: String): Boolean? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    return runCatching { value.jsonPrimitive.takeUnless { it.isString }?.booleanOrNull }
        .getOrNull()
        ?: throw ReplicaDetectionConfiguration.ValidationException("Invalid value for $key")
}

private fun JsonObject.optionalDouble(key: String): Double? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    return runCatching { value.jsonPrimitive.takeUnless { it.isString }?.doubleOrNull }
        .getOrNull()
        ?: throw ReplicaDetectionConfiguration.ValidationException("Invalid value for $key")
}

private fun ReplicaDetectionConfiguration.Parameters.applyRemoteValue(
    key: String,
    boolean: Boolean?,
    number: Double?
) {
    fun bool(): Boolean = boolean
        ?: throw ReplicaDetectionConfiguration.ValidationException("Invalid value for $key")
    fun numeric(range: ClosedFloatingPointRange<Double>): Double {
        val result = number
            ?: throw ReplicaDetectionConfiguration.ValidationException("Invalid value for $key")
        if (!result.isFinite() || result !in range) {
            throw ReplicaDetectionConfiguration.ValidationException("Invalid value for $key")
        }
        return result
    }
    fun integer(range: IntRange): Int {
        val result = numeric(range.first.toDouble()..range.last.toDouble())
        if (result % 1.0 != 0.0) {
            throw ReplicaDetectionConfiguration.ValidationException("Invalid value for $key")
        }
        return result.toInt()
    }
    fun float(range: ClosedFloatingPointRange<Double>): Float = numeric(range).toFloat()

    when (key) {
        "diffThreshold" -> diffThreshold = integer(1..80)
        "heightFraction" -> heightFraction = float(0.20..0.95)
        "widthFraction" -> widthFraction = float(0.02..0.80)
        "localSupportFraction" -> localSupportFraction = float(0.05..0.90)
        "minFillRatio" -> minFillRatio = float(0.02..0.90)
        "maxAspectRatio" -> maxAspectRatio = float(0.20..5.00)
        "minFillRatioLenient" -> minFillRatioLenient = float(0.02..0.90)
        "maxAspectRatioLenient" -> maxAspectRatioLenient = float(0.20..5.00)
        "spikeRatioThreshold" -> spikeRatioThreshold = float(0.50..5.00)
        "warmupFrames" -> warmupFrames = integer(0..180)
        "torsoFraction" -> torsoFraction = float(0.10..0.70)
        "useLeadingEdgeTrigger" -> useLeadingEdgeTrigger = bool()
        "torsoRunAbsMin" -> torsoRunAbsMin = integer(5..160)
        "torsoRunAbsMax" -> torsoRunAbsMax = integer(5..200)
        "torsoRunHeightFraction" -> torsoRunHeightFraction = float(0.05..0.80)
        "gateRunMergeMaxGap" -> gateRunMergeMaxGap = integer(0..8)
        "flashGuardCoverage" -> flashGuardCoverage = float(0.10..1.00)
        "flashGuardFill" -> flashGuardFill = float(0.10..1.00)
        "flashGuardWidthFraction" -> flashGuardWidthFraction = float(0.10..1.00)
        "flashGuardHeightFraction" -> flashGuardHeightFraction = float(0.10..1.00)
        "fullWidthBandWidthFraction" -> fullWidthBandWidthFraction = float(0.50..1.00)
        "gateBandHalfWidth" -> gateBandHalfWidth = integer(1..8)
        "thickGateHalfWidth" -> thickGateHalfWidth = integer(0..12)
        "limbWaitReleaseAfter" -> limbWaitReleaseAfter = integer(0..10)
        "cooldownSeconds" -> cooldownSeconds = numeric(0.0..5.0)
        "sparseStartupSceneMotionFrameLimit" -> sparseStartupSceneMotionFrameLimit = integer(0..600)
        "sparseStartupSceneMotionMaxGateWidth" -> sparseStartupSceneMotionMaxGateWidth = integer(0..60)
        "thinSceneMotionMaxBuildup" -> thinSceneMotionMaxBuildup = integer(0..30)
        "thinSceneMotionMaxStripWidth" -> thinSceneMotionMaxStripWidth = float(0.0..60.0)
        "thinSceneMotionMaxHorizontalRun" -> thinSceneMotionMaxHorizontalRun = integer(0..60)
        "thinSceneMotionMaxGateWidth" -> thinSceneMotionMaxGateWidth = integer(0..60)
        "thinSceneMotionMinBlobHeightFraction" -> thinSceneMotionMinBlobHeightFraction = float(0.20..1.00)
        "thinSceneMotionMinTorsoDistance" -> thinSceneMotionMinTorsoDistance = integer(0..90)
        "noTorsoTinyGateRowGuardEnabled" -> noTorsoTinyGateRowGuardEnabled = bool()
        "noTorsoTinyGateRowMaxGateWidth" -> noTorsoTinyGateRowMaxGateWidth = integer(0..60)
        "noTorsoTinyGateRowMaxStripWidth" -> noTorsoTinyGateRowMaxStripWidth = float(0.0..60.0)
        "noTorsoTinyGateRowMaxHorizontalRun" -> noTorsoTinyGateRowMaxHorizontalRun = integer(0..60)
        "noTorsoTinyGateRowMinBlobHeightFraction" -> noTorsoTinyGateRowMinBlobHeightFraction = float(0.20..1.00)
        "incoherentSceneMotionGuardEnabled" -> incoherentSceneMotionGuardEnabled = bool()
        "sceneMotionMinWidthFraction" -> sceneMotionMinWidthFraction = float(0.40..1.00)
        "sceneMotionMinTorsoFragments" -> sceneMotionMinTorsoFragments = integer(1..160)
        "sceneMotionWeakMaxGateWidth" -> sceneMotionWeakMaxGateWidth = integer(0..90)
        "sceneMotionWeakMaxStripWidth" -> sceneMotionWeakMaxStripWidth = float(0.0..90.0)
        "sceneMotionWeakMaxHorizontalRun" -> sceneMotionWeakMaxHorizontalRun = integer(0..90)
        "sceneMotionBroadShadowMinWidthFraction" -> sceneMotionBroadShadowMinWidthFraction = float(0.40..1.00)
        "sceneMotionBroadShadowMaxGateWidth" -> sceneMotionBroadShadowMaxGateWidth = integer(0..90)
        "sceneMotionBroadShadowMaxStripWidth" -> sceneMotionBroadShadowMaxStripWidth = float(0.0..90.0)
        "sceneMotionBroadShadowMaxHorizontalRun" -> sceneMotionBroadShadowMaxHorizontalRun = integer(0..90)
        "xAnchorSelectorV3Enabled" -> xAnchorSelectorV3Enabled = bool()
        "motionDirectionMinDeltaPixels" -> motionDirectionMinDeltaPixels = float(0.0..40.0)
        "motionDirectionHistoryMaxAgeFrames" -> motionDirectionHistoryMaxAgeFrames = integer(1..60)
        "motionDirectionHistoryLength" -> motionDirectionHistoryLength = integer(2..60)
        "lowContrastBodyFallbackEnabled" -> lowContrastBodyFallbackEnabled = bool()
        "lowContrastBodyFallbackMinMergedRun" -> lowContrastBodyFallbackMinMergedRun = integer(1..160)
        "lowContrastBodyFallbackMinLocalRun" -> lowContrastBodyFallbackMinLocalRun = integer(1..160)
        "lowContrastBodyFallbackMinGateBandColumns" -> lowContrastBodyFallbackMinGateBandColumns = integer(1..30)
        "lowContrastBodyFallbackMinGateBandPixels" -> lowContrastBodyFallbackMinGateBandPixels = integer(1..2_000)
        "lowContrastBodyFallbackMinSequenceFrames" -> lowContrastBodyFallbackMinSequenceFrames = integer(1..60)
        "lowContrastBodyFallbackMinBlobHeightFraction" -> lowContrastBodyFallbackMinBlobHeightFraction = float(0.10..1.00)
        "lowContrastBodyFallbackMinBlobWidthFraction" -> lowContrastBodyFallbackMinBlobWidthFraction = float(0.01..1.00)
        "lowContrastBodyFallbackMaxBlobWidthFraction" -> lowContrastBodyFallbackMaxBlobWidthFraction = float(0.01..1.00)
        "lowContrastBodyFallbackUpperZoneFraction" -> lowContrastBodyFallbackUpperZoneFraction = float(0.10..1.00)
        else -> throw ReplicaDetectionConfiguration.ValidationException("Unknown detection parameter $key")
    }
}

private fun ReplicaDetectionConfiguration.Parameters.validateRelationships() {
    if (minFillRatioLenient > minFillRatio) {
        throw ReplicaDetectionConfiguration.ValidationException("minFillRatioLenient must be <= minFillRatio")
    }
    if (maxAspectRatioLenient < maxAspectRatio) {
        throw ReplicaDetectionConfiguration.ValidationException("maxAspectRatioLenient must be >= maxAspectRatio")
    }
    if (torsoRunAbsMin > torsoRunAbsMax) {
        throw ReplicaDetectionConfiguration.ValidationException("torsoRunAbsMin must be <= torsoRunAbsMax")
    }
    if (lowContrastBodyFallbackMinBlobWidthFraction > lowContrastBodyFallbackMaxBlobWidthFraction) {
        throw ReplicaDetectionConfiguration.ValidationException("low-contrast min width must be <= max width")
    }
}
