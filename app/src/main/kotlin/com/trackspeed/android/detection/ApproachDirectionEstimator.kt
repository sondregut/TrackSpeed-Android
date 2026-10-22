package com.trackspeed.android.detection

import kotlin.math.abs

/** iPhone b609e4b98: compare the upper 60% of the accepted body, excluding leg swing.
 * Abstention keeps the existing history/center fallback. Inputs are consecutive motion masks,
 * never raw images or receipt timestamps. No platform-specific image APIs are required.
 */
internal object ApproachDirectionEstimator {
    fun movingLeftToRight(previous: ByteArray?, current: ByteArray, width: Int, height: Int,
        minY: Int, maxY: Int, minimumDeltaPixels: Float): Boolean? {
        if (width <= 0 || height <= 0 || previous == null || previous.size != current.size ||
            current.size.toLong() != width.toLong()*height || !minimumDeltaPixels.isFinite() || minimumDeltaPixels < 0) return null
        val top=minY.coerceIn(0,height-1)
        val bottom=maxY.coerceIn(top,height-1)
        val upper=top+((bottom-top)*.6f).toInt()
        fun centroid(mask:ByteArray):Pair<Float,Int> {
            var count=0;var sum=0L
            for (y in top..upper) for (x in 0 until width) if(mask[y*width+x].toInt()!=0) { count++;sum+=x }
            return (if(count==0) 0f else sum.toFloat()/count) to count
        }
        val (before,previousPixels)=centroid(previous)
        val (after,currentPixels)=centroid(current)
        if(previousPixels<20 || currentPixels<20) return null
        val delta=after-before
        if(abs(delta)<minimumDeltaPixels || delta==0f) return null
        return delta>0
    }
}
