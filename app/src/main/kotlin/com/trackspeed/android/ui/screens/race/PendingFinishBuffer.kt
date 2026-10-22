package com.trackspeed.android.ui.screens.race

/** Finish evidence waiting for a delayed start, ordered by sensor time, never arrival time.
 * Keep a small bounded window: an unrelated early crossing must not hide a later valid finish.
 */
internal class PendingFinishBuffer<T>(private val capacity: Int = 8) {
    data class Candidate<T>(val timestampNanos: Long, val evidence: T)
    private val candidates = sortedMapOf<Long, T>()
    init { require(capacity > 0) }

    val firstTimestamp: Long? get() = candidates.firstKeyOrNull()

    fun add(timestampNanos: Long, evidence: T) {
        if (timestampNanos <= 0 || candidates.containsKey(timestampNanos)) return
        candidates[timestampNanos] = evidence
        while (candidates.size > capacity) candidates.remove(candidates.firstKey())
    }

    fun takeFirstAfter(startNanos: Long): Candidate<T>? {
        if (startNanos <= 0) return null
        candidates.keys.filter { it <= startNanos }.forEach(candidates::remove)
        val timestamp = candidates.firstKeyOrNull() ?: return null
        val result = Candidate(timestamp, candidates.getValue(timestamp))
        clear() // The first eligible finish closes the run; repeats cannot replace it.
        return result
    }

    fun updateLatestEvidence(update: (T) -> T) {
        if (candidates.isNotEmpty()) {
            val timestamp = candidates.lastKey()
            candidates[timestamp] = update(candidates.getValue(timestamp))
        }
    }

    fun clear() = candidates.clear()
    private fun <K, V> java.util.SortedMap<K, V>.firstKeyOrNull(): K? = if (isEmpty()) null else firstKey()
}

/** A remote result needs an actual local start. Receipt time is not measurement evidence. */
internal fun finishTimestampForResult(localStartNanos: Long?, splitNanos: Long): Long? {
    val start = localStartNanos?.takeIf { it > 0 } ?: return null
    if (splitNanos <= 0 || splitNanos > Long.MAX_VALUE - start) return null
    return start + splitNanos
}
