package com.trackspeed.android.camerastart

import org.junit.Assert.*
import org.junit.Test

class CameraStartOnsetTest {
    @Test fun `motion requires stillness then interpolates onset using sensor time`() {
        val engine = CameraStartArming(false)
        engine.setSessionReady(true)
        settle(engine)
        assertEquals(CameraStartArming.Phase.ARMED, engine.phase)
        assertNull(engine.ingest(pose(1_850, 0.0)))
        assertNull(engine.ingest(pose(1_900, 0.02)))
        assertNull(engine.ingest(pose(1_950, 0.04)))
        val result = engine.ingest(pose(2_000, 0.06))!!
        assertEquals(1_862_500_000L, result.onset.onsetNanos)
        assertEquals(2_000_000_000L, result.onset.confirmedAtNanos)
        assertEquals(50_000_000L, result.onset.frameIntervalNanos)
        assertEquals(CameraStartArming.Commit.MOTION, result.commit)
        assertNull(engine.ingest(pose(2_050, 0.3)))
    }

    @Test fun `single joint glitch and hands alone cannot fire`() {
        for (moving in listOf(setOf(CameraStartJoint.LEFT_WRIST), setOf(CameraStartJoint.LEFT_WRIST, CameraStartJoint.RIGHT_WRIST))) {
            val engine = CameraStartArming(false)
            engine.setSessionReady(true); settle(engine)
            for (ms in 1850L..2200L step 50) {
                val sample = pose(ms, 0.0)
                val joints = sample.joints.mapValues { (joint, p) -> if (joint in moving) p.copy(x = p.x + 0.2) else p }
                assertNull(engine.ingest(sample.copy(joints = joints)))
            }
            assertEquals(CameraStartArming.Phase.ARMED, engine.phase)
        }
    }

    @Test fun `reordered frames cannot progress confirmation`() {
        val engine = CameraStartArming(false)
        engine.setSessionReady(true); settle(engine)
        assertNull(engine.ingest(pose(1_850, 0.03)))
        repeat(4) { assertNull(engine.ingest(pose(1_850, 0.2))) }
        assertNull(engine.ingest(pose(1_800, 0.4)))
        assertNull(engine.ingest(pose(1_900, 0.04)))
        assertNotNull(engine.ingest(pose(1_950, 0.06)))
    }

    @Test fun `quiet return after twitch prevents backdating next onset`() {
        val engine = CameraStartArming(false)
        engine.setSessionReady(true); settle(engine)
        engine.ingest(pose(1_850, 0.0))
        engine.ingest(pose(1_900, 0.02))
        engine.ingest(pose(1_950, 0.0))
        engine.ingest(pose(2_000, 0.02))
        engine.ingest(pose(2_050, 0.04))
        assertEquals(1_962_500_000L, engine.ingest(pose(2_100, 0.06))!!.onset.onsetNanos)
    }

    @Test fun `session loss disarms and next run must settle again`() {
        val engine = CameraStartArming(false)
        engine.setSessionReady(true); settle(engine)
        engine.setSessionReady(false)
        assertEquals(CameraStartArming.Phase.HOLD_STILL, engine.phase)
        engine.setSessionReady(true)
        assertNull(engine.ingest(pose(1_850, 0.03)))
        assertNull(engine.ingest(pose(1_900, 0.04)))
        assertNull(engine.ingest(pose(1_950, 0.06)))
        assertNotEquals(CameraStartArming.Phase.STARTED, engine.phase)
    }

    @Test fun `athlete can settle while waiting but moving before readiness cannot fire`() {
        val engine = CameraStartArming(false)
        settle(engine)
        assertEquals(CameraStartArming.Phase.WAITING_FOR_SESSION, engine.phase)
        for (ms in 1850L..1950L step 50) assertNull(engine.ingest(pose(ms, 0.05)))
        assertEquals(CameraStartArming.Phase.HOLD_STILL, engine.phase)
        engine.setSessionReady(true)
        assertNotEquals(CameraStartArming.Phase.ARMED, engine.phase)
    }

    @Test fun `long pose gap forces reacquisition`() {
        val engine = CameraStartArming(false)
        engine.setSessionReady(true); settle(engine)
        assertNull(engine.ingest(CameraStartPoseSample(2_150_000_000, emptyMap())))
        assertEquals(CameraStartArming.Phase.STEP_INTO_FRAME, engine.phase)
        assertNull(engine.ingest(pose(2_200, 0.2)))
        assertEquals(CameraStartArming.Phase.HOLD_STILL, engine.phase)
    }

    @Test fun `invalid confidence and nonfinite coordinates cannot settle`() {
        val engine = CameraStartArming(false)
        engine.setSessionReady(true)
        for (ms in 1000L..2000L step 50) {
            val sample = pose(ms, 0.0)
            assertNull(engine.ingest(sample.copy(joints = sample.joints.mapValues { it.value.copy(x = Double.NaN) })))
        }
        assertEquals(CameraStartArming.Phase.STEP_INTO_FRAME, engine.phase)
        for (ms in 2050L..3050L step 50) {
            val sample = pose(ms, 0.0)
            engine.ingest(sample.copy(joints = sample.joints.mapValues { it.value.copy(confidence = 0.1) }))
        }
        assertEquals(CameraStartArming.Phase.STEP_INTO_FRAME, engine.phase)
    }

    @Test fun `in frame waits for trunk commitment and preserves original onset`() {
        val engine = CameraStartArming(true)
        engine.setSessionReady(true); settle(engine)
        engine.ingest(pose(1_850, 0.0))
        engine.ingest(pose(1_900, 0.02)); engine.ingest(pose(1_950, 0.04))
        assertNull(engine.ingest(pose(2_000, 0.06)))
        assertEquals(CameraStartArming.Phase.CONFIRMING, engine.phase)
        val start = engine.ingest(pose(2_050, 0.12))!!
        assertEquals(1_862_500_000L, start.onset.onsetNanos)
        assertEquals(2_050_000_000L, start.committedAtNanos)
        assertEquals(CameraStartArming.Commit.TRUNK_TRAVEL, start.commit)
    }

    @Test fun `in frame weight shift times out rather than starting`() {
        val engine = CameraStartArming(true)
        engine.setSessionReady(true); settle(engine)
        for (ms in 1850L..2650L step 50) assertNull(engine.ingest(pose(ms, 0.03)))
        assertEquals(CameraStartArming.Phase.HOLD_STILL, engine.phase)
    }

    @Test fun `leaving frame commits only after confirmed movement`() {
        val engine = CameraStartArming(true)
        engine.setSessionReady(true); settle(engine)
        // Disappearance alone never starts a run.
        assertNull(engine.ingest(CameraStartPoseSample(2_150_000_000, emptyMap())))
        assertEquals(CameraStartArming.Phase.STEP_INTO_FRAME, engine.phase)
        val ready = CameraStartArming(true)
        ready.setSessionReady(true); settle(ready)
        for (ms in 1850L..1950L step 50) assertNull(ready.ingest(pose(ms, 0.03)))
        assertEquals(CameraStartArming.Phase.CONFIRMING, ready.phase)
        assertNull(ready.ingest(CameraStartPoseSample(2_200_000_000, emptyMap())))
        val start = ready.ingest(CameraStartPoseSample(2_300_000_000, emptyMap()))!!
        assertEquals(CameraStartArming.Commit.LEFT_FRAME, start.commit)
        assertEquals(1_800_000_000L, start.onset.onsetNanos)
    }

    @Test fun `capture failures cannot be mistaken for leaving frame`() {
        val engine = CameraStartArming(true)
        engine.setSessionReady(true); settle(engine)
        for (ms in 1850L..1950L step 50) engine.ingest(pose(ms, 0.03))
        engine.cameraInterrupted()
        assertNull(engine.ingest(CameraStartPoseSample(2_500_000_000, emptyMap())))
        assertEquals(CameraStartArming.Phase.STEP_INTO_FRAME, engine.phase)
    }

    @Test fun `one moved trunk joint cannot commit a departure`() {
        val engine = CameraStartArming(true)
        engine.setSessionReady(true); settle(engine)
        for (ms in 1850L..1950L step 50) engine.ingest(pose(ms, 0.03))
        val base = pose(2_000, 0.03)
        val joints = base.joints + (CameraStartJoint.LEFT_SHOULDER to base.joints.getValue(CameraStartJoint.LEFT_SHOULDER).copy(x = 0.9))
        assertNull(engine.ingest(base.copy(joints = joints)))
        assertEquals(CameraStartArming.Phase.CONFIRMING, engine.phase)
    }

    @Test fun `fired state is one shot until run closes and rejected starts recover`() {
        val engine = CameraStartArming(false)
        engine.setSessionReady(true); settle(engine)
        for (ms in 1850L..1950L step 50) engine.ingest(pose(ms, 0.04))
        assertEquals(CameraStartArming.Phase.STARTED, engine.phase)
        engine.cameraInterrupted()
        assertEquals(CameraStartArming.Phase.STARTED, engine.phase)
        engine.setSessionReady(false); engine.setSessionReady(true)
        assertEquals(CameraStartArming.Phase.STEP_INTO_FRAME, engine.phase)
        for (ms in 3000L..3800L step 50) engine.ingest(pose(ms, 0.0))
        for (ms in 3850L..3950L step 50) engine.ingest(pose(ms, 0.04))
        assertEquals(CameraStartArming.Phase.STARTED, engine.phase)
        engine.startNotSent()
        assertEquals(CameraStartArming.Phase.STEP_INTO_FRAME, engine.phase)
    }

    private fun settle(engine: CameraStartArming) {
        for (ms in 1000L..1800L step 50) assertNull(engine.ingest(pose(ms, 0.0)))
    }
    private fun pose(ms: Long, shift: Double): CameraStartPoseSample {
        val coordinates = mapOf(
            CameraStartJoint.NOSE to (0.5 to 0.15), CameraStartJoint.NECK to (0.5 to 0.3),
            CameraStartJoint.LEFT_SHOULDER to (0.4 to 0.3), CameraStartJoint.RIGHT_SHOULDER to (0.6 to 0.3),
            CameraStartJoint.LEFT_ELBOW to (0.35 to 0.4), CameraStartJoint.RIGHT_ELBOW to (0.65 to 0.4),
            CameraStartJoint.LEFT_WRIST to (0.3 to 0.5), CameraStartJoint.RIGHT_WRIST to (0.7 to 0.5),
            CameraStartJoint.LEFT_HIP to (0.45 to 0.5), CameraStartJoint.RIGHT_HIP to (0.55 to 0.5),
            CameraStartJoint.LEFT_KNEE to (0.45 to 0.7), CameraStartJoint.RIGHT_KNEE to (0.55 to 0.7),
            CameraStartJoint.LEFT_ANKLE to (0.45 to 0.9), CameraStartJoint.RIGHT_ANKLE to (0.55 to 0.9)
        )
        return CameraStartPoseSample(ms * 1_000_000, coordinates.mapValues { CameraStartPoint(it.value.first + shift, it.value.second) })
    }
}
