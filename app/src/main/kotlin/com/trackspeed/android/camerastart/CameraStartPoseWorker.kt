package com.trackspeed.android.camerastart

import com.trackspeed.android.camera.CameraManager
import com.trackspeed.android.onephone.SingleCameraPoseDetector
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Bounded newest-frame worker. Retains sensor timestamps; inference delay never becomes start time. */
class CameraStartPoseWorker(private val onSample: (Long, String, CameraStartPoseSample?) -> Unit) {
    private data class Pending(val frame: CameraManager.FrameData, val rotation: Int, val key: String, val generation: Long)
    private val schedulingLock = Any()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "CameraStartPose") }
    private val pending = AtomicReference<Pending?>(null)
    private val draining = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val epoch = AtomicLong(0)
    private var detector: SingleCameraPoseDetector? = null
    val generation: Long get() = epoch.get()

    fun offer(frame: CameraManager.FrameData, rotationDegrees: Int, key: String) {
        if (closed.get()) return
        pending.set(Pending(frame, rotationDegrees, key, generation))
        schedule()
    }
    fun invalidate() { epoch.incrementAndGet(); pending.set(null) }
    private fun schedule(): Unit = synchronized(schedulingLock) {
        if (closed.get() || !draining.compareAndSet(false, true)) return
        executor.execute {
            try {
                while (!closed.get()) {
                    val item = pending.getAndSet(null) ?: break
                    if (item.generation != generation) continue
                    val pose = detector ?: SingleCameraPoseDetector().also { detector = it }
                    val sample = pose.detectStartSample(item.frame, item.rotation)
                    if (sample == null) {
                        // A timed-out inference may still own its input; never reuse that detector/buffer.
                        pose.close()
                        detector = null
                    }
                    if (item.generation == generation && !closed.get()) onSample(item.generation, item.key, sample)
                }
            } finally {
                draining.set(false)
                if (pending.get() != null && !closed.get()) schedule()
            }
        }
    }
    fun close(): Unit = synchronized(schedulingLock) {
        if (!closed.compareAndSet(false, true)) return
        invalidate()
        executor.execute { detector?.close(); detector = null }
        executor.shutdown()
    }
}
