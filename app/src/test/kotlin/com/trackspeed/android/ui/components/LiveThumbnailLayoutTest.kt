package com.trackspeed.android.ui.components
import org.junit.Assert.*
import org.junit.Test
class LiveThumbnailLayoutTest {
    @Test fun preservesAspectAndCompactBudget() {
        assertEquals(LiveThumbnailSize(45f,80f),liveThumbnailSize(900,1600,false))
        assertEquals(LiveThumbnailSize(80f,45f),liveThumbnailSize(1600,900,false))
        assertEquals(66f,liveThumbnailSize(null,null,true).height,0f)
        assertEquals(144f,liveThumbnailSize(900,1600,false,true).height,0f)
        assertEquals(128f,liveThumbnailSize(900,1600,true,true).height,0f)
    }
}
