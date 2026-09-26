package com.trackspeed.android.sync

/**
 * Maps setup clock samples onto one monotonic progress window.
 *
 * The first measurement does most of the work. A possible second measurement
 * uses the final part of the bar, and 100% remains reserved for a result that
 * passed the setup accuracy gate.
 */
internal object ClockSyncSetupProgress {
    private const val FIRST_MEASUREMENT_COMPLETE = 0.80f
    private const val WAITING_FOR_VALIDATION_MAX = 0.95f

    fun fromCompletedSamples(
        completedSamples: Int,
        samplesPerMeasurement: Int = ClockSyncConfig.FULL_SYNC_SAMPLES,
        maximumMeasurements: Int = ClockSyncConfig.MAX_SYNC_RETRIES
    ): Float {
        if (completedSamples <= 0) return 0f

        val samplesPerRun = samplesPerMeasurement.coerceAtLeast(1)
        val runCount = maximumMeasurements.coerceAtLeast(1)
        val firstRunSamples = completedSamples.coerceAtMost(samplesPerRun)
        val firstRunProgress =
            firstRunSamples.toFloat() / samplesPerRun * FIRST_MEASUREMENT_COMPLETE

        if (runCount == 1 || completedSamples <= samplesPerRun) {
            return firstRunProgress.coerceAtMost(WAITING_FOR_VALIDATION_MAX)
        }

        val remainingCapacity = WAITING_FOR_VALIDATION_MAX - FIRST_MEASUREMENT_COMPLETE
        val remainingSamples = (completedSamples - samplesPerRun)
            .coerceAtMost(samplesPerRun * (runCount - 1))
        val remainingTotal = samplesPerRun * (runCount - 1)
        val retryProgress = remainingSamples.toFloat() / remainingTotal * remainingCapacity
        return (FIRST_MEASUREMENT_COMPLETE + retryProgress)
            .coerceAtMost(WAITING_FOR_VALIDATION_MAX)
    }
}
