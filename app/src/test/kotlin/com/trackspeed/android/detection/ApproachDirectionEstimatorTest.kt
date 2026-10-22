package com.trackspeed.android.detection
import org.junit.Assert.*
import org.junit.Test
class ApproachDirectionEstimatorTest {
    private fun mask(x:Int,legX:Int=x)=ByteArray(100*100).apply {
        for(y in 10..65) for(dx in 0..9) this[y*100+x+dx]=1
        for(y in 80..99) for(dx in 0..19) this[y*100+legX+dx]=1
    }
    private fun direction(a:ByteArray?,b:ByteArray)=ApproachDirectionEstimator.movingLeftToRight(a,b,100,100,10,99,4f)
    @Test fun usesUpperBodyDespiteOpposingLegMotion() {
        assertEquals(true,direction(mask(25,70),mask(30,10)))
        assertEquals(false,direction(mask(30,10),mask(25,70)))
    }
    @Test fun abstainsOnSmallMotionOrMissingAndSparseMasks() {
        assertNull(direction(mask(25),mask(28)))
        assertNull(direction(null,mask(30)))
        assertNull(direction(ByteArray(10000),mask(30)))
        assertNull(direction(ByteArray(1),mask(30)))
    }
    @Test fun configMatchesCurrentIphoneAndCanBeDisabledLocally() {
        assertTrue(ReplicaDetectionConfiguration.Parameters().motionDirectionApproachRuntimeEnabled)
        val config=ReplicaDetectionConfiguration.Parameters(motionDirectionApproachRuntimeEnabled=false)
        assertFalse(config.motionDirectionApproachRuntimeEnabled)
    }
}
