package com.trackspeed.android.ui.screens.race

import com.trackspeed.android.protocol.TimingMessageCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class TimingContextPolicyTest {
    private fun context(raw: String) = Json.parseToJsonElement(raw).jsonObject

    @Test fun preservesIosWatchAndFutureEvidenceAcrossDecodeEncode() {
        val wire = """{"protocolVersion":7,"seq":1,"senderId":"host","sessionId":"s","payload":{"startEvent":{"monotonicNanos":123}},"createdAtNanos":124,"timingContext":{"startReferenceNanos":123,"watchStart":{"source":{"runID":"r"},"clockUncertaintyMilliseconds":4.5},"futureField":{"nested":true}}}"""
        val decoded = TimingMessageCodec.decodeFromBytes(wire.toByteArray())
        val roundTrip = TimingMessageCodec.decodeFromBytes(TimingMessageCodec.encodeToBytes(decoded))
        assertEquals(decoded.timingContext, roundTrip.timingContext)
        assertEquals(123L, contextStartReference(roundTrip.timingContext))
        assertEquals(4.5, contextWatchUncertainty(roundTrip.timingContext), 0.0)
    }

    @Test fun missingOrInvalidDeclaredClockQualityCannotBecomePerfect() {
        assertEquals(0.0, contextClockUncertainty(null), 0.0)
        assertEquals(1_000.0, contextClockUncertainty(context("""{"clockQuality":{"isKnown":false,"uncertaintyMs":0}}""")), 0.0)
        assertEquals(1_000.0, contextWatchUncertainty(context("""{"watchStart":{}}""")), 0.0)
        assertEquals(1_000.0, contextClockUncertainty(context("""{"clockQuality":{"isKnown":true,"uncertaintyMs":-1}}""")), 0.0)
    }

    @Test fun movementRevisionMustBelongToSameWatchRun() {
        val first = context("""{"watchStart":{"source":{"runID":"ABC"}}}""")
        assertTrue(sameWatchStart(first, context("""{"watchStart":{"source":{"runID":"abc"}}}""")))
        assertFalse(sameWatchStart(first, context("""{"watchStart":{"source":{"runID":"other"}}}""")))
        assertFalse(sameWatchStart(null, first))
    }
}
