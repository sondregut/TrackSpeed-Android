package com.trackspeed.android.ui.screens.race

import com.trackspeed.android.protocol.TimingMessage
import com.trackspeed.android.protocol.TimingPayload
import com.trackspeed.android.protocol.TimingRole

/**
 * Stable wire identity for one camera-confirmed gate crossing.
 *
 * The envelope ID is shared by BLE retries and reconnect replay, while every
 * retry still receives its own message-level ACK. Keeping this construction in
 * one place prevents the detector, result, and evidence ledgers from naming the
 * same physical crossing differently.
 */
internal data class CanonicalCrossingEvent(
    val eventId: String,
    val payload: TimingPayload.CrossingEvent
)

internal fun canonicalCrossingEvent(
    runId: String,
    gateId: String,
    role: TimingRole,
    gateIndex: Int,
    timestampNanos: Long,
    confidence: Double = 1.0
): CanonicalCrossingEvent = CanonicalCrossingEvent(
    eventId = TimingMessage.generateEventId(runId, gateId, timestampNanos),
    payload = TimingPayload.CrossingEvent(
        gateId = gateId,
        role = role,
        gateIndex = gateIndex,
        timestampNanos = timestampNanos,
        confidence = confidence,
        thumbnailData = null
    )
)

/** A timing result is distinct from the finish crossing that produced it. */
internal fun canonicalTimingResultEventId(finishCrossingEventId: String): String =
    "$finishCrossingEventId-result"
