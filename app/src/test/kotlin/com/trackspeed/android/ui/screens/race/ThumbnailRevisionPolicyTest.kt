package com.trackspeed.android.ui.screens.race

import com.trackspeed.android.protocol.TimingRole
import org.junit.Assert.*
import org.junit.Test

class ThumbnailRevisionPolicyTest {
    @Test fun lateStartDecodeCannotReplaceNewestStartImage() {
        val policy = ThumbnailRevisionPolicy()
        assertTrue(policy.claim("run:start", TimingRole.START_LINE, 100))
        assertTrue(policy.claim("run:start", TimingRole.START_LINE, 120))
        assertFalse(policy.isCurrent("run:start", 100))
        assertFalse(policy.claim("run:start", TimingRole.START_LINE, 110))
        assertTrue(policy.isCurrent("run:start", 120))
    }
    @Test fun firstFinishAndSeparateRunsKeepTheirOwnImages() {
        val policy = ThumbnailRevisionPolicy()
        assertTrue(policy.claim("run:finish", TimingRole.FINISH_LINE, 150))
        assertFalse(policy.claim("run:finish", TimingRole.FINISH_LINE, 160))
        assertTrue(policy.claim("next:finish", TimingRole.FINISH_LINE, 200))
        assertTrue(policy.isCurrent("run:finish", 150))
        assertTrue(policy.isCurrent("next:finish", 200))
    }
}
