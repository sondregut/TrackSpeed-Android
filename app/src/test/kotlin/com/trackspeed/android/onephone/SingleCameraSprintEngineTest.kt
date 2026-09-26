package com.trackspeed.android.onephone

import com.trackspeed.android.onephone.SingleCameraSprintDirection.LEFT_TO_RIGHT
import com.trackspeed.android.onephone.SingleCameraSprintDirection.RIGHT_TO_LEFT
import com.trackspeed.android.onephone.SingleCameraSprintStartStyle.FLYING
import com.trackspeed.android.onephone.SingleCameraSprintStartStyle.STANDING
import com.trackspeed.android.onephone.SingleCameraSprintTimingEvent.Armed
import com.trackspeed.android.onephone.SingleCameraSprintTimingEvent.AthleteAcquired
import com.trackspeed.android.onephone.SingleCameraSprintTimingEvent.Completed
import com.trackspeed.android.onephone.SingleCameraSprintTimingEvent.GateCrossed
import com.trackspeed.android.onephone.SingleCameraSprintTimingEvent.Invalidated
import com.trackspeed.android.onephone.SingleCameraSprintTimingEvent.Started
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports of the iOS `SingleCameraSprintTests` model/engine/tracker coverage. */
class SingleCameraSprintEngineTest {

    private fun pose(time: Double, x: Double, confidence: Double = 0.90) =
        SingleCameraPoseSample(timestamp = time, rootX = x, rootY = 0.5, confidence = confidence)

    private fun body(time: Double, x: Double, height: Double): SingleCameraBodyObservation {
        val root = SingleCameraPoint(x, 0.46)
        return SingleCameraBodyObservation(
            timestamp = time,
            root = root,
            confidence = 0.94,
            boundingBox = SingleCameraRect(x - 0.05, root.y - height * 0.48, 0.10, height),
            joints = mapOf(
                SingleCameraBodyJointName.ROOT to SingleCameraBodyJoint(SingleCameraBodyJointName.ROOT, root, 0.94)
            )
        )
    }

    private fun config(
        distance: Double,
        style: SingleCameraSprintStartStyle = STANDING,
        drill: SingleCameraSprintDrill = SingleCameraSprintDrill.SPRINT
    ) = SingleCameraSprintConfiguration.create(distance, style, drill)

    private fun completeStandingRun(engine: SingleCameraSprintTimingEngine, offset: Double): SingleCameraSprintResult {
        assertTrue(engine.arm(config(5.0).defaultGates(), LEFT_TO_RIGHT))
        listOf(
            0.00 to 0.140, 0.20 to 0.141, 0.40 to 0.139, 0.60 to 0.140, 0.62 to 0.141,
            0.64 to 0.150, 0.66 to 0.170, 0.80 to 0.32, 0.90 to 0.48, 1.00 to 0.65, 1.10 to 0.82
        ).forEach { (t, x) -> engine.ingest(pose(offset + t, x)) }
        val events = engine.ingest(pose(offset + 1.18, 0.90))
        return (events.last() as Completed).result
    }

    @Test
    fun supportedDistanceBuildsMetricStyleGateSet() {
        assertEquals(listOf(0.0, 5.0, 10.0, 20.0), config(20.0).gateDistances)
        assertEquals(10.0, config(11.0).distanceMeters, 0.0)
        assertEquals(listOf(0.0, 5.0, 10.0, 15.0), config(15.0).gateDistances)
        assertEquals(listOf(0.0, 5.0, 10.0, 15.0), config(15.0, FLYING).gateDistances)
        assertEquals(listOf(0.0, 5.0, 10.0, 15.0, 20.0), config(20.0, FLYING).gateDistances)
    }

    @Test
    fun configurationChangesKeepAlignedGates() {
        val standing = config(10.0)
        val aligned = SingleCameraSprintGateLayout.moving(5.0, 0.47, standing.defaultGates(), LEFT_TO_RIGHT)
        val flying = standing.changingStartStyle(FLYING)
        val flyingGates = SingleCameraSprintGateLayout.reconfigured(aligned, flying, LEFT_TO_RIGHT)
        assertEquals(FLYING, flying.startStyle)
        assertEquals(aligned, flyingGates)

        val twenty = flying.changingDistance(20.0)
        val twentyGates = SingleCameraSprintGateLayout.reconfigured(flyingGates, twenty, LEFT_TO_RIGHT)
        assertEquals(listOf(0.0, 5.0, 10.0, 15.0, 20.0), twentyGates.map { it.distanceMeters })
        assertNotNull(SingleCameraCourseCalibration.create(twentyGates, LEFT_TO_RIGHT))
    }

    @Test
    fun phoneMotionWarningPersistsUntilReset() {
        val warning = SingleCameraPhoneMotionWarningState()
        assertFalse(warning.ingest(true))
        assertFalse(warning.ingest(true))
        assertFalse(warning.ingest(false))
        assertFalse(warning.didDetectMovement)
        assertFalse(warning.ingest(true))
        assertFalse(warning.ingest(true))
        assertTrue(warning.ingest(true))
        assertTrue(warning.didDetectMovement)
        warning.endSampleSequence()
        assertTrue(warning.didDetectMovement)
        assertEquals(0, warning.consecutiveMotionSamples)
        warning.reset()
        assertFalse(warning.didDetectMovement)
    }

    @Test
    fun flyingDefaultsReserveRunInAndExitSpaceInBothDirections() {
        for (distance in SingleCameraSprintConfiguration.supportedDistances(FLYING)) {
            for (direction in SingleCameraSprintDirection.entries) {
                val assessment = SingleCameraSprintFraming.assessment(
                    config(distance, FLYING).defaultGates(direction),
                    direction
                )!!
                assertTrue(assessment.isReady)
                assertEquals(3.0, assessment.acquisitionMeters, 0.000_1)
                assertEquals(2.0, assessment.exitMeters, 0.000_1)
            }
        }
    }

    @Test
    fun projectiveCalibrationMapsEveryMeasuredGate() {
        // Generated from d = (40x - 4) / (0.5x + 1).
        val gates = listOf(
            SingleCameraSprintGate(0.0, 0.10),
            SingleCameraSprintGate(5.0, 9.0 / 37.5),
            SingleCameraSprintGate(10.0, 14.0 / 35.0),
            SingleCameraSprintGate(20.0, 0.80)
        )
        val calibration = SingleCameraCourseCalibration.create(gates, LEFT_TO_RIGHT)!!
        assertTrue(calibration.usesProjectiveMapping)
        assertTrue(calibration.maxResidualMeters < 0.000_1)
        for (gate in gates) {
            assertEquals(gate.distanceMeters, calibration.meters(gate.normalizedX), 0.000_1)
            assertEquals(gate.normalizedX, calibration.normalizedX(gate.distanceMeters), 0.000_1)
        }
        val upstreamX = calibration.normalizedX(-1.0)
        assertEquals(-1.0, calibration.meters(upstreamX), 0.000_1)
    }

    @Test
    fun calibrationRejectsGateOrderThatReversesOnScreen() {
        val gates = listOf(
            SingleCameraSprintGate(0.0, 0.2),
            SingleCameraSprintGate(5.0, 0.6),
            SingleCameraSprintGate(10.0, 0.5)
        )
        assertNull(SingleCameraCourseCalibration.create(gates, LEFT_TO_RIGHT))
    }

    @Test
    fun everyGateMovesIndependentlyAndClampsBeforeCrossing() {
        val configuration = config(20.0)
        for (direction in SingleCameraSprintDirection.entries) {
            val original = configuration.defaultGates(direction)
            for (gate in original) {
                val delta = if (direction == LEFT_TO_RIGHT) 0.04 else -0.04
                val moved = SingleCameraSprintGateLayout.moving(
                    gate.distanceMeters,
                    (gate.normalizedX + delta).coerceIn(0.06, 0.94),
                    original,
                    direction
                )
                assertNotEquals(gate.normalizedX, moved.first { it.distanceMeters == gate.distanceMeters }.normalizedX)
                original.filter { it.distanceMeters != gate.distanceMeters }.forEach { neighbor ->
                    assertEquals(neighbor.normalizedX, moved.first { it.distanceMeters == neighbor.distanceMeters }.normalizedX, 0.0)
                }
                assertNotNull(SingleCameraCourseCalibration.create(moved, direction))
            }
        }

        val clamped = SingleCameraSprintGateLayout.moving(5.0, 0.99, config(10.0).defaultGates(), LEFT_TO_RIGHT)
        val middle = clamped.first { it.distanceMeters == 5.0 }
        val finish = clamped.first { it.distanceMeters == 10.0 }
        assertTrue(finish.normalizedX - middle.normalizedX >= SingleCameraSprintGateLayout.MINIMUM_DIRECTED_SPACING - 0.000_1)
    }

    @Test
    fun trackerLocksFirstPersonAndRejectsAbruptIdentitySwap() {
        val tracker = SingleCameraAthleteTracker()
        val athlete = body(0.0, 0.20, 0.50)
        val bystander = body(0.0, 0.78, 0.24)
        assertEquals(0.20, tracker.select(listOf(bystander, athlete), 0.14, 0.0)!!.root.x, 0.000_1)

        val continued = tracker.select(listOf(body(0.05, 0.23, 0.20), body(0.05, 0.24, 0.49)), 0.14, 0.05)
        assertEquals(0.24, continued!!.root.x, 0.000_1)

        val replacement = body(0.10, 0.26, 0.15)
        assertNull(tracker.select(listOf(replacement), 0.14, 0.10))
        assertEquals(0.26, tracker.select(listOf(replacement), 0.14, 0.80)!!.root.x, 0.000_1)
    }

    @Test
    fun standingSelectionShowsSkeletonWithoutGrantingTimingLock() {
        val gates = config(10.0).defaultGates()
        val startX = gates.first().normalizedX
        val range = SingleCameraSprintAcquisition.initialDirectedRange(gates, LEFT_TO_RIGHT, STANDING, 1.0)!!
        val coordinator = SingleCameraAthleteSelectionCoordinator()
        val preview = coordinator.select(listOf(body(0.0, 0.50, 0.50)), startX, 0.0, LEFT_TO_RIGHT, range)
        assertEquals(0.50, preview.visibleObservation!!.root.x, 0.000_1)
        assertNull(preview.timingObservation)
        assertTrue(preview.isVisibleOutsideTimingZone)

        val locked = coordinator.select(
            listOf(body(0.05, 0.50, 0.50), body(0.05, startX, 0.50)),
            startX,
            0.05,
            LEFT_TO_RIGHT,
            range
        )
        assertEquals(startX, locked.timingObservation!!.root.x, 0.000_1)
        assertFalse(locked.isVisibleOutsideTimingZone)
    }

    @Test
    fun liveEngineSettlesStartsAndInterpolatesEveryGate() {
        val engine = SingleCameraSprintTimingEngine()
        assertTrue(engine.arm(config(10.0).defaultGates(), LEFT_TO_RIGHT))
        assertEquals(listOf(AthleteAcquired), engine.ingest(pose(0.00, 0.140)))
        engine.ingest(pose(0.20, 0.141))
        engine.ingest(pose(0.40, 0.139))
        assertEquals(listOf(Armed), engine.ingest(pose(0.60, 0.140)))
        assertTrue(engine.ingest(pose(0.62, 0.141)).isEmpty())
        assertTrue(engine.ingest(pose(0.64, 0.150)).isEmpty())
        val start = (engine.ingest(pose(0.66, 0.170)).first() as Started).timestamp
        assertEquals(SingleCameraSprintPhase.Running, engine.phase)
        assertTrue(start >= 0.62 && start < 0.64)

        engine.ingest(pose(0.80, 0.25))
        engine.ingest(pose(1.00, 0.40))
        val five = (engine.ingest(pose(1.20, 0.60)).first() as GateCrossed).split
        assertEquals(5.0, five.distanceMeters, 0.0)
        assertEquals(1.10, five.crossingTimestamp, 0.000_1)

        engine.ingest(pose(1.35, 0.70))
        engine.ingest(pose(1.50, 0.80))
        val result = (engine.ingest(pose(1.60, 0.90)).last() as Completed).result
        assertEquals(SingleCameraSprintPhase.Completed, engine.phase)
        assertEquals(listOf(5.0, 10.0), result.splits.map { it.distanceMeters })
        assertEquals(1.56 - start, result.totalTimeSeconds, 0.000_1)
        assertTrue(result.peakVelocityMetersPerSecond > 0)
        assertEquals("single-camera-v4-mlkit-2d-standing-sprint", result.algorithmVersion)
    }

    @Test
    fun standingStartUsesTheSamePhysicalThresholdAcrossFramingAndDirection() {
        for (distance in listOf(5.0, 20.0)) {
            for (direction in SingleCameraSprintDirection.entries) {
                val gates = config(distance).defaultGates(direction)
                val calibration = SingleCameraCourseCalibration.create(gates, direction)!!
                val engine = SingleCameraSprintTimingEngine()
                assertTrue(engine.arm(gates, direction))
                fun x(meters: Double) = calibration.normalizedX(meters)
                assertEquals(listOf(AthleteAcquired), engine.ingest(pose(0.00, x(0.0))))
                engine.ingest(pose(0.20, x(0.0)))
                engine.ingest(pose(0.40, x(0.0)))
                assertEquals(listOf(Armed), engine.ingest(pose(0.60, x(0.0))))
                assertTrue(engine.ingest(pose(0.62, x(0.01))).isEmpty())
                assertTrue(engine.ingest(pose(0.64, x(0.07))).isEmpty())
                val started = engine.ingest(pose(0.66, x(0.11))).first() as Started
                assertEquals(0.626_667, started.timestamp, 0.000_1)
            }
        }
    }

    @Test
    fun engineCompletesResetAndCompletesASecondRun() {
        val engine = SingleCameraSprintTimingEngine()
        assertTrue(completeStandingRun(engine, 0.0).totalTimeSeconds > 0)
        engine.reset()
        assertEquals(SingleCameraSprintPhase.Calibrating, engine.phase)
        assertTrue(engine.splits.isEmpty())
        val second = completeStandingRun(engine, 3.0)
        assertEquals(second.totalTimeSeconds, engine.liveElapsedSeconds, 0.000_1)
    }

    @Test
    fun flyingStartLocksThreeFramesAndTimesZeroMeterCrossing() {
        val engine = SingleCameraSprintTimingEngine()
        val gates = config(10.0, FLYING).defaultGates()
        assertTrue(engine.arm(gates, LEFT_TO_RIGHT, FLYING))
        assertEquals(listOf(AthleteAcquired), engine.ingest(pose(0.000, 0.14)))
        assertEquals(SingleCameraSprintPhase.Locking(1.0 / 3.0), engine.phase)
        assertTrue(engine.ingest(pose(0.016, 0.16)).isEmpty())
        assertEquals(listOf(Armed), engine.ingest(pose(0.032, 0.18)))

        val start = (engine.ingest(pose(0.048, 0.27)).first() as Started).timestamp
        val startGateX = gates.first().normalizedX
        assertEquals(0.032 + (startGateX - 0.18) / (0.27 - 0.18) * 0.016, start, 0.000_1)

        engine.ingest(pose(0.080, 0.45))
        assertTrue(engine.ingest(pose(0.120, 0.60)).any { it is GateCrossed && it.split.distanceMeters == 5.0 })
        engine.ingest(pose(0.160, 0.75))
        val result = (engine.ingest(pose(0.180, 0.83)).last() as Completed).result
        val finishX = gates.last().normalizedX
        val expectedFinish = 0.160 + (finishX - 0.75) / (0.83 - 0.75) * 0.020
        assertEquals(expectedFinish - start, result.totalTimeSeconds, 0.000_1)
        assertEquals(FLYING, result.startStyle)
    }

    @Test
    fun flyingStartFailsClosedWhenAthleteReachesZeroBeforeLock() {
        val engine = SingleCameraSprintTimingEngine()
        assertTrue(engine.arm(config(10.0, FLYING).defaultGates(), LEFT_TO_RIGHT, FLYING))
        engine.ingest(pose(0.000, 0.20))
        assertEquals(
            listOf(Invalidated(SingleCameraInvalidReason.REACHED_START_BEFORE_READY)),
            engine.ingest(pose(0.016, 0.26))
        )
    }

    @Test
    fun flyingPreStartPoseGapReturnsToEntrySearch() {
        val engine = SingleCameraSprintTimingEngine()
        assertTrue(engine.arm(config(10.0, FLYING).defaultGates(), LEFT_TO_RIGHT, FLYING))
        engine.ingest(pose(0.000, 0.14))
        engine.ingest(pose(0.016, 0.16))
        assertEquals(listOf(Armed), engine.ingest(pose(0.032, 0.18)))
        assertTrue(engine.ingestMissingPose(0.40).isEmpty())
        assertEquals(SingleCameraSprintPhase.Acquiring, engine.phase)
        assertEquals(listOf(AthleteAcquired), engine.ingest(pose(0.42, 0.14)))
    }

    @Test
    fun flyingStartRequiresThreeMeterRunInAndTwoMeterExit() {
        val gates = listOf(
            SingleCameraSprintGate(0.0, 0.10),
            SingleCameraSprintGate(5.0, 0.45),
            SingleCameraSprintGate(10.0, 0.80)
        )
        val engine = SingleCameraSprintTimingEngine()
        assertFalse(engine.arm(gates, LEFT_TO_RIGHT, FLYING))
        assertEquals(SingleCameraSprintPhase.Invalid(SingleCameraInvalidReason.FLYING_FRAMING), engine.phase)
    }

    @Test
    fun rightToLeftRunUsesTheSameForwardSemantics() {
        val engine = SingleCameraSprintTimingEngine()
        assertTrue(engine.arm(config(5.0).defaultGates(RIGHT_TO_LEFT), RIGHT_TO_LEFT))
        assertEquals(listOf(AthleteAcquired), engine.ingest(pose(0.0, 0.86)))
        engine.ingest(pose(0.2, 0.86))
        engine.ingest(pose(0.4, 0.86))
        assertEquals(listOf(Armed), engine.ingest(pose(0.6, 0.86)))
        engine.ingest(pose(0.62, 0.859))
        engine.ingest(pose(0.64, 0.85))
        assertTrue(engine.ingest(pose(0.66, 0.83)).any { it is Started })
        listOf(0.74 to 0.70, 0.8 to 0.60, 0.9 to 0.45, 1.0 to 0.30, 1.1 to 0.20).forEach { (t, x) ->
            engine.ingest(pose(t, x))
        }
        assertTrue(engine.ingest(pose(1.2, 0.10)).any { it is Completed })
    }

    @Test
    fun runningRunFailsClosedAfterPoseGap() {
        val engine = SingleCameraSprintTimingEngine(
            SingleCameraSprintTimingParameters(settleDurationSeconds = 0.2, movementConfirmationSamples = 1)
        )
        assertTrue(engine.arm(config(5.0).defaultGates(), LEFT_TO_RIGHT))
        engine.ingest(pose(0.0, 0.14))
        engine.ingest(pose(0.1, 0.14))
        engine.ingest(pose(0.2, 0.14))
        engine.ingest(pose(0.22, 0.15))
        assertEquals(SingleCameraSprintPhase.Running, engine.phase)
        assertEquals(
            listOf(Invalidated(SingleCameraInvalidReason.TRACKING_LOST)),
            engine.ingestMissingPose(0.60)
        )
    }

    @Test
    fun standingHoldCannotBridgeMissingFrames() {
        val engine = SingleCameraSprintTimingEngine()
        assertTrue(engine.arm(config(5.0).defaultGates(), LEFT_TO_RIGHT))
        for (index in 0..3) engine.ingest(pose(index * 0.2, 0.14))
        assertEquals(SingleCameraSprintPhase.Armed, engine.phase)
        engine.ingestMissingPose(1.1)
        assertEquals(SingleCameraSprintPhase.Acquiring, engine.phase)
        engine.ingest(pose(1.2, 0.14))
        assertTrue(engine.phase is SingleCameraSprintPhase.Settling)
        assertNull(engine.result)
    }

    @Test
    fun proAgilityUsesYardsCentreStartAndTwoReturnsInEitherDirection() {
        for (direction in SingleCameraSprintDirection.entries) {
            val configuration = config(20.0, drill = SingleCameraSprintDrill.PRO_AGILITY)
            assertEquals(18.288, configuration.distanceMeters, 0.000_001)
            assertEquals(listOf(0.0, 4.572, 9.144), configuration.gateDistances)
            val gates = configuration.defaultGates(direction)
            val calibration = SingleCameraCourseCalibration.create(gates, direction)!!
            assertEquals(
                0.5,
                SingleCameraSprintAcquisition.targetX(gates, direction, STANDING, SingleCameraSprintDrill.PRO_AGILITY),
                0.001
            )
            val engine = SingleCameraSprintTimingEngine()
            assertTrue(engine.arm(gates, direction, drill = SingleCameraSprintDrill.PRO_AGILITY))
            var time = 0.0
            fun feed(distance: Double) {
                engine.ingest(pose(time, calibration.normalizedX(distance)))
                time += 0.1
            }
            repeat(8) { feed(4.572) }
            assertEquals(SingleCameraSprintPhase.Armed, engine.phase)
            listOf(4.60, 4.70, 4.9, 5.5, 6.5, 7.5, 8.5, 8.8, 8.0, 7.0, 6.0, 5.0, 4.4).forEach(::feed)
            assertEquals(SingleCameraSprintPhase.Running, engine.phase)
            assertEquals(1, engine.splits.size)
            listOf(3.4, 2.4, 1.4, 0.4, 0.3, 1.3, 2.3, 3.3, 4.3, 4.8).forEach(::feed)
            val result = engine.result!!
            assertEquals(listOf(9.144, 18.288), result.splits.map { it.distanceMeters })
            assertEquals(SingleCameraSprintQuality.REVIEW, result.quality)
        }
    }

    @Test
    fun returnCourseCannotFinishFromStartLineJitter() {
        val configuration = config(10.0, drill = SingleCameraSprintDrill.SHUTTLE)
        val gates = configuration.defaultGates()
        val calibration = SingleCameraCourseCalibration.create(gates, LEFT_TO_RIGHT)!!
        val engine = SingleCameraSprintTimingEngine()
        assertTrue(engine.arm(gates, LEFT_TO_RIGHT, drill = SingleCameraSprintDrill.SHUTTLE))
        for (index in 0..7) engine.ingest(pose(index * 0.1, 0.14))
        listOf(0.03, 0.10, 0.3, 0.1, -0.1).forEachIndexed { index, d ->
            engine.ingest(pose(0.8 + index * 0.1, calibration.normalizedX(d)))
        }
        assertNull(engine.result)
        assertTrue(engine.splits.isEmpty())
    }

    @Test
    fun phoneMovementMarksResultForReviewWithoutChangingItsTime() {
        val result = completeStandingRun(SingleCameraSprintTimingEngine(), 0.0)
        val marked = result.markedForPhoneMotionReview()
        assertEquals(SingleCameraSprintQuality.REVIEW, marked.quality)
        assertEquals(result.totalTimeSeconds, marked.totalTimeSeconds, 0.0)
    }

    @Test
    fun persistenceProducesCumulativeSegmentsAndRoundTripsDiagnostics() {
        val result = completeStandingRun(SingleCameraSprintTimingEngine(), 0.0)
        val record = SingleCameraSprintRunRecord(
            id = "run-1",
            runNumber = 1,
            createdAtMillis = 0,
            result = result,
            gates = config(5.0).defaultGates(),
            direction = LEFT_TO_RIGHT,
            phoneMotionWarning = false
        )
        val segments = SingleCameraSprintPersistence.segmentSplits(result.splits)
        assertEquals(result.splits.size, segments.size)
        assertEquals((result.totalTimeSeconds * 1e9).toLong(), segments.last().cumulativeSplitNanos)
        val decoded = SingleCameraSprintPersistence.decodeDiagnostics(SingleCameraSprintPersistence.diagnosticsJson(record))!!
        assertEquals("sprint", decoded.drillRaw)
        assertEquals(record.gates.size, decoded.gates.size)
        assertEquals("inFrame", SingleCameraSprintPersistence.persistedStartType(STANDING))
        assertNull(SingleCameraSprintPersistence.decodeDiagnostics("{\"watchStart\":{}}"))
    }

    @Test
    fun imageRotationMatchesCamera2Formula() {
        assertEquals(0, SingleCameraPoseDetector.imageRotationDegrees(90, 90, isFrontCamera = false))
        assertEquals(180, SingleCameraPoseDetector.imageRotationDegrees(90, 270, isFrontCamera = false))
        assertEquals(0, SingleCameraPoseDetector.imageRotationDegrees(270, 90, isFrontCamera = true))
        assertEquals(90, SingleCameraPoseDetector.imageRotationDegrees(90, 0, isFrontCamera = false))
    }
}
