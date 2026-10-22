package com.trackspeed.android.onephone

import com.trackspeed.android.camerastart.CameraStartJoint
import com.trackspeed.android.camerastart.CameraStartPoint
import com.trackspeed.android.camerastart.CameraStartPoseSample
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.trackspeed.android.camera.CameraManager
import java.util.concurrent.TimeUnit

/**
 * Maps camera frames to [SingleCameraBodyObservation]s with ML Kit pose
 * detection (the Android counterpart of iOS Vision body-pose). Observations
 * are normalized to the displayed (upright, selfie-mirrored) frame so they share
 * one coordinate space with the draggable gates.
 *
 * ML Kit's streaming detector reports at most one person per frame; the athlete
 * tracker still enforces continuity and the timing zone for that candidate.
 */
class SingleCameraPoseDetector {
    private val detector: PoseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )
    private var nv21 = ByteArray(0)

    /** Runs synchronously; call only from a background worker thread. */
    fun detect(
        frame: CameraManager.FrameData,
        rotationDegrees: Int,
        mirrorHorizontally: Boolean
    ): List<SingleCameraBodyObservation> {
        val image = InputImage.fromByteArray(
            toNv21(frame),
            frame.width,
            frame.height,
            rotationDegrees,
            InputImage.IMAGE_FORMAT_NV21
        )
        val pose = try {
            Tasks.await(detector.process(image), 2, TimeUnit.SECONDS)
        } catch (error: Exception) {
            Log.w(TAG, "[SINGLE_CAMERA_POSE_FAILED] ${error.javaClass.simpleName}")
            return emptyList()
        }
        val uprightWidth = if (rotationDegrees % 180 == 0) frame.width else frame.height
        val uprightHeight = if (rotationDegrees % 180 == 0) frame.height else frame.width
        val observation = makeObservation(
            pose,
            uprightWidth,
            uprightHeight,
            mirrorHorizontally,
            frame.timestampNanos / 1_000_000_000.0
        )
        return listOfNotNull(observation)
    }

    /** Null means inference failed, not that the athlete left the frame. Never turn failures into starts. */
    fun detectStartSample(frame: CameraManager.FrameData, rotationDegrees: Int): CameraStartPoseSample? {
        val image = InputImage.fromByteArray(toNv21(frame), frame.width, frame.height,
            rotationDegrees, InputImage.IMAGE_FORMAT_NV21)
        val pose = try {
            Tasks.await(detector.process(image), 2, TimeUnit.SECONDS)
        } catch (error: Exception) {
            Log.w(TAG, "[CAMERA_START_POSE_FAILED] ${error.javaClass.simpleName}")
            return null
        }
        // ML Kit reports upright pixels. Dividing both axes by height preserves Euclidean distances.
        val height = (if (rotationDegrees % 180 == 0) frame.height else frame.width).toDouble()
        if (height <= 0) return null
        val joints = (LANDMARKS.map { CameraStartJoint.valueOf(it.first.name) to it.second } +
            (CameraStartJoint.NOSE to PoseLandmark.NOSE)).mapNotNull { (name, type) ->
            pose.getPoseLandmark(type)?.let { point -> name to CameraStartPoint(
                point.position.x / height, point.position.y / height, point.inFrameLikelihood.toDouble()) }
        }.toMap()
        // ML Kit has no independent neck landmark. Do not synthesize it: one bad shoulder
        // would otherwise move both shoulder and neck and defeat the two-joint noise guard.
        return CameraStartPoseSample(frame.timestampNanos, joints)
    }

    fun close() {
        detector.close()
    }

    private fun toNv21(frame: CameraManager.FrameData): ByteArray {
        val width = frame.width
        val height = frame.height
        val size = width * height + 2 * ((width + 1) / 2) * ((height + 1) / 2)
        if (nv21.size != size) nv21 = ByteArray(size)
        var out = 0
        for (row in 0 until height) {
            System.arraycopy(frame.yPlane, row * frame.rowStride, nv21, out, width)
            out += width
        }
        val chromaHeight = (height + 1) / 2
        val chromaWidth = (width + 1) / 2
        for (row in 0 until chromaHeight) {
            val rowOffset = row * frame.uvRowStride
            for (col in 0 until chromaWidth) {
                val index = rowOffset + col * frame.uvPixelStride
                nv21[out++] = frame.vPlane.getOrElse(index) { NEUTRAL_CHROMA }
                nv21[out++] = frame.uPlane.getOrElse(index) { NEUTRAL_CHROMA }
            }
        }
        return nv21
    }

    private fun makeObservation(
        pose: Pose,
        width: Int,
        height: Int,
        mirror: Boolean,
        timestamp: Double
    ): SingleCameraBodyObservation? {
        if (width <= 0 || height <= 0 || pose.allPoseLandmarks.isEmpty()) return null
        val joints = HashMap<SingleCameraBodyJointName, SingleCameraBodyJoint>()
        for ((name, type) in LANDMARKS) {
            val landmark = pose.getPoseLandmark(type) ?: continue
            val confidence = landmark.inFrameLikelihood.toDouble()
            if (confidence < 0.15) continue
            joints[name] = SingleCameraBodyJoint(name, normalize(landmark, width, height, mirror), confidence)
        }
        val leftShoulder = joints[SingleCameraBodyJointName.LEFT_SHOULDER]
        val rightShoulder = joints[SingleCameraBodyJointName.RIGHT_SHOULDER]
        if (leftShoulder != null && rightShoulder != null) {
            joints[SingleCameraBodyJointName.NECK] = SingleCameraBodyJoint(
                SingleCameraBodyJointName.NECK,
                SingleCameraPoint(
                    (leftShoulder.location.x + rightShoulder.location.x) / 2,
                    (leftShoulder.location.y + rightShoulder.location.y) / 2
                ),
                minOf(leftShoulder.confidence, rightShoulder.confidence)
            )
        }
        return SingleCameraBodyObservation.fromJoints(joints, timestamp)
    }

    private fun normalize(landmark: PoseLandmark, width: Int, height: Int, mirror: Boolean): SingleCameraPoint {
        val x = (landmark.position.x / width).toDouble().coerceIn(0.0, 1.0)
        val y = (landmark.position.y / height).toDouble().coerceIn(0.0, 1.0)
        return SingleCameraPoint(if (mirror) 1 - x else x, y)
    }

    companion object {
        private const val TAG = "SingleCameraPose"
        private const val NEUTRAL_CHROMA: Byte = 128.toByte()

        private val LANDMARKS = listOf(
            SingleCameraBodyJointName.LEFT_SHOULDER to PoseLandmark.LEFT_SHOULDER,
            SingleCameraBodyJointName.RIGHT_SHOULDER to PoseLandmark.RIGHT_SHOULDER,
            SingleCameraBodyJointName.LEFT_ELBOW to PoseLandmark.LEFT_ELBOW,
            SingleCameraBodyJointName.RIGHT_ELBOW to PoseLandmark.RIGHT_ELBOW,
            SingleCameraBodyJointName.LEFT_WRIST to PoseLandmark.LEFT_WRIST,
            SingleCameraBodyJointName.RIGHT_WRIST to PoseLandmark.RIGHT_WRIST,
            SingleCameraBodyJointName.LEFT_HIP to PoseLandmark.LEFT_HIP,
            SingleCameraBodyJointName.RIGHT_HIP to PoseLandmark.RIGHT_HIP,
            SingleCameraBodyJointName.LEFT_KNEE to PoseLandmark.LEFT_KNEE,
            SingleCameraBodyJointName.RIGHT_KNEE to PoseLandmark.RIGHT_KNEE,
            SingleCameraBodyJointName.LEFT_ANKLE to PoseLandmark.LEFT_ANKLE,
            SingleCameraBodyJointName.RIGHT_ANKLE to PoseLandmark.RIGHT_ANKLE
        )

        /**
         * Rotation that makes a sensor buffer upright for the current display
         * rotation (ML Kit / Camera2 documented formula).
         */
        fun imageRotationDegrees(
            sensorOrientationDegrees: Int,
            displayRotationDegrees: Int,
            isFrontCamera: Boolean
        ): Int {
            val sensor = ((sensorOrientationDegrees % 360) + 360) % 360
            val display = ((displayRotationDegrees % 360) + 360) % 360
            return if (isFrontCamera) (sensor + display) % 360 else (sensor - display + 360) % 360
        }
    }
}
