package com.trackspeed.android.sync

import com.trackspeed.android.protocol.TimingMessage
import kotlin.math.pow
import kotlin.random.Random

/**
 * Thread-safe ACK/retry state for critical TimingMessages.
 *
 * A retry always returns the original envelope, preserving messageId, seq,
 * eventId, runId and timestamp so BLE/cloud duplicates remain idempotent.
 * Broadcast messages can wait for every BLE recipient rather than treating the
 * first ACK in a multi-gate race as acknowledgement from all phones.
 */
internal class CriticalMessageRetryTracker(
    private val nowMillis: () -> Long,
    private val jitterFraction: () -> Double = { Random.nextDouble(0.0, MAX_JITTER_FRACTION) }
) {
    data class RetryTarget(
        val deviceAddress: String? = null,
        val acknowledgementKeys: Set<String> = emptySet()
    )

    sealed interface Action {
        val message: TimingMessage
        val target: RetryTarget

        data class Retry(
            override val message: TimingMessage,
            override val target: RetryTarget,
            val attempt: Int,
            val maximumAttempts: Int
        ) : Action

        data class Failed(
            override val message: TimingMessage,
            override val target: RetryTarget
        ) : Action
    }

    private data class Pending(
        val message: TimingMessage,
        val target: RetryTarget,
        val remainingAcknowledgementKeys: MutableSet<String>?,
        var retryCount: Int,
        val maximumRetries: Int,
        val baseRetryIntervalMs: Long,
        var retryAtMs: Long
    )

    private val pending = linkedMapOf<String, Pending>()

    @Synchronized
    fun track(
        message: TimingMessage,
        target: RetryTarget = RetryTarget(),
        maximumRetries: Int = DEFAULT_MAXIMUM_RETRIES,
        baseRetryIntervalMs: Long = DEFAULT_RETRY_INTERVAL_MS
    ): Boolean {
        val messageId = message.messageId ?: return false
        if (!message.requiresAck) return false
        val now = nowMillis()
        pending[messageId] = Pending(
            message = message,
            target = target,
            remainingAcknowledgementKeys = target.acknowledgementKeys
                .map(::normalizeAcknowledgementKey)
                .toMutableSet()
                .takeIf { it.isNotEmpty() },
            retryCount = 0,
            maximumRetries = maximumRetries,
            baseRetryIntervalMs = baseRetryIntervalMs,
            retryAtMs = now + retryDelayMs(baseRetryIntervalMs, retryCount = 0)
        )
        return true
    }

    /**
     * Clears a wildcard pending message on any ACK. For a multi-recipient
     * message, every supplied sender key (device ID and/or BLE address) is
     * removed and the message clears only after no expected recipients remain.
     */
    @Synchronized
    fun acknowledge(messageId: String, senderKeys: Set<String>): Boolean {
        val tracked = pending[messageId] ?: return false
        val remaining = tracked.remainingAcknowledgementKeys
        if (remaining == null) {
            pending.remove(messageId)
            return true
        }
        senderKeys.mapTo(mutableSetOf(), ::normalizeAcknowledgementKey).forEach(remaining::remove)
        if (remaining.isEmpty()) {
            pending.remove(messageId)
            return true
        }
        return false
    }

    @Synchronized
    fun reject(messageId: String?): TimingMessage? {
        if (messageId == null) return null
        return pending.remove(messageId)?.message
    }

    @Synchronized
    fun poll(): List<Action> {
        val now = nowMillis()
        val actions = mutableListOf<Action>()
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val (_, tracked) = iterator.next()
            if (now < tracked.retryAtMs) continue
            if (tracked.retryCount >= tracked.maximumRetries) {
                iterator.remove()
                actions += Action.Failed(tracked.message, tracked.target)
                continue
            }
            tracked.retryCount += 1
            tracked.retryAtMs = now + retryDelayMs(
                tracked.baseRetryIntervalMs,
                retryCount = tracked.retryCount
            )
            actions += Action.Retry(
                message = tracked.message,
                target = tracked.target,
                attempt = tracked.retryCount,
                maximumAttempts = tracked.maximumRetries
            )
        }
        return actions
    }

    @Synchronized
    fun clear() {
        pending.clear()
    }

    @Synchronized
    fun pendingCount(): Int = pending.size

    private fun retryDelayMs(baseIntervalMs: Long, retryCount: Int): Long {
        val exponential = baseIntervalMs.toDouble() * 2.0.pow(retryCount.toDouble())
        val boundedJitter = jitterFraction().coerceIn(0.0, MAX_JITTER_FRACTION)
        return (exponential * (1.0 + boundedJitter)).toLong().coerceAtLeast(1L)
    }

    private fun normalizeAcknowledgementKey(value: String): String = value.trim().lowercase()

    companion object {
        const val DEFAULT_RETRY_INTERVAL_MS = 1_000L
        const val DEFAULT_MAXIMUM_RETRIES = 12
        const val CHECK_INTERVAL_MS = 250L
        private const val MAX_JITTER_FRACTION = 0.2

        fun deviceIdKey(deviceId: String): String = "id:$deviceId"
        fun addressKey(address: String): String = "address:$address"
    }
}
