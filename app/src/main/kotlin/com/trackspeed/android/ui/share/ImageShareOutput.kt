package com.trackspeed.android.ui.share

import com.trackspeed.android.ui.components.ShareCardData
import com.trackspeed.android.ui.components.ShareCardTheme
import com.trackspeed.android.ui.components.ShareSessionCardData

internal fun ShareCardData.outputKey(theme: ShareCardTheme, renderEnvironment: String,
    options: RunImageOptions = RunImageOptions()): String = imageShareFingerprint(listOf(
    "run-canvas-v3", theme.name, renderEnvironment, timeSeconds.toString(), distance.toString(), startType,
    dateFormatted, athleteName, isPersonalBest.toString(), isSeasonBest.toString(), speedValue?.toString(),
    speedUnit, segments.toString(), timeDigits.toString(), startPhoto?.generationId?.toString(), finishPhoto?.generationId?.toString(),
    startGatePosition?.toString(), finishGatePosition?.toString()
) + options.fingerprintFields())

internal fun ShareSessionCardData.outputKey(theme: ShareCardTheme, renderEnvironment: String,
    options: SessionImageOptions = SessionImageOptions()): String = imageShareFingerprint(buildList {
    addAll(listOf("session-canvas-v3", theme.name, renderEnvironment, distance.toString(), dateFormatted))
    addAll(options.fingerprintFields())
    runs.forEach { run ->
        addAll(listOf(run.runNumber.toString(), run.timeSeconds.toString(), run.athleteName,
            run.segments.toString(), run.timeDigits.toString(), run.thumbnail?.generationId?.toString()))
    }
})
