package com.trackspeed.android.camera

internal data class CameraStreamCandidate(
    val width: Int,
    val height: Int,
    val minimumFrameDurationNanos: Long
) {
    val pixelCount: Long get() = width.toLong() * height
}

internal data class CameraFpsCandidate(val lower: Int, val upper: Int)

/** Pure selection logic kept outside Camera2 so the device matrix is unit-testable. */
internal object CameraConfigurationSelector {
    private const val MINIMUM_WIDTH = 640
    private const val PREFERRED_MINIMUM_WIDTH = 1280
    private const val PREFERRED_MAXIMUM_WIDTH = 1920
    private const val TARGET_ASPECT_RATIO = 16.0 / 9.0

    fun selectStream(
        candidates: List<CameraStreamCandidate>,
        targetFps: Int
    ): CameraStreamCandidate? {
        require(targetFps > 0)
        val frameBudgetNanos = 1_000_000_000L / targetFps
        val usable = candidates.filter { it.width >= MINIMUM_WIDTH && it.height > 0 }
        if (usable.isEmpty()) return null

        val capable = usable.filter {
            it.minimumFrameDurationNanos <= 0L ||
                it.minimumFrameDurationNanos <= frameBudgetNanos
        }
        val pool = capable.ifEmpty { usable }
        val preferred = pool.filter { it.width in PREFERRED_MINIMUM_WIDTH..PREFERRED_MAXIMUM_WIDTH }
            .ifEmpty { pool }

        return preferred.minWithOrNull(
            compareBy<CameraStreamCandidate> { candidate ->
                kotlin.math.abs(candidate.width.toDouble() / candidate.height - TARGET_ASPECT_RATIO)
            }.thenByDescending { it.pixelCount }
        )
    }

    fun selectFpsRange(
        candidates: List<CameraFpsCandidate>,
        targetFps: Int
    ): CameraFpsCandidate? {
        if (candidates.isEmpty()) return null
        candidates.firstOrNull { it.lower == targetFps && it.upper == targetFps }?.let { return it }

        val containingTarget = candidates.filter { it.lower <= targetFps && it.upper >= targetFps }
        if (containingTarget.isNotEmpty()) {
            return containingTarget.minWithOrNull(
                compareBy<CameraFpsCandidate> { it.upper - targetFps }
                    .thenBy { targetFps - it.lower }
            )
        }

        return candidates.minWithOrNull(
            compareBy<CameraFpsCandidate> { kotlin.math.abs(it.upper - targetFps) }
                .thenBy { kotlin.math.abs(it.lower - targetFps) }
        )
    }
}
