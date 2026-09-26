package com.trackspeed.android.ui.screens.race

import com.trackspeed.android.protocol.TimingRole

/** A delayed JPEG decode/download must still belong to the winning crossing. */
internal class ThumbnailRevisionPolicy {
    private val winners = linkedMapOf<String, Long>()

    fun claim(key: String, role: TimingRole, timestamp: Long): Boolean {
        if (timestamp <= 0) return false
        val previous = winners[key]
        if (previous != null && timestamp != previous && !crossingCandidateWins(role, previous, timestamp)) return false
        winners[key] = timestamp
        while (winners.size > 512) winners.remove(winners.keys.first())
        return true
    }

    fun isCurrent(key: String, timestamp: Long): Boolean = winners[key] == timestamp
}
