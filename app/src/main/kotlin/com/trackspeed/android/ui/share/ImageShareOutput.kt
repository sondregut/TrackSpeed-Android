package com.trackspeed.android.ui.share

import com.trackspeed.android.ui.components.ShareCardData
import com.trackspeed.android.ui.components.ShareCardTheme
import com.trackspeed.android.ui.components.ShareSessionCardData

internal fun ShareCardData.outputKey(theme: ShareCardTheme, renderEnvironment: String): String = imageShareFingerprint(listOf(
    "run-story-v1", theme.name, renderEnvironment, timeSeconds.toString(), distance.toString(), startType,
    dateFormatted, athleteName, isPersonalBest.toString(), isSeasonBest.toString(), speedValue?.toString(),
    speedUnit, segments.toString()
))

internal fun ShareSessionCardData.outputKey(theme: ShareCardTheme, renderEnvironment: String): String = imageShareFingerprint(buildList {
    addAll(listOf("session-story-v1", theme.name, renderEnvironment, distance.toString(), dateFormatted))
    runs.forEach { run ->
        addAll(listOf(run.runNumber.toString(), run.timeSeconds.toString(), run.athleteName,
            run.segments.toString(), run.thumbnail?.generationId?.toString()))
    }
})
