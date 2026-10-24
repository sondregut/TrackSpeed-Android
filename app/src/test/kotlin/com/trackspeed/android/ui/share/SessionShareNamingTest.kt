package com.trackspeed.android.ui.share

import org.junit.Assert.*
import org.junit.Test

class SessionShareNamingTest {
    @Test fun noRunsOrOnlyUnnamedRunsShowNoAthlete() {
        assertEquals(SessionShareNaming.None, sessionShareNaming(emptyList()))
        assertEquals(SessionShareNaming.None, sessionShareNaming(listOf(null, "", " \n")))
    }
    @Test fun oneNameOnEveryRunUsesOneTrimmedFooter() {
        assertEquals(SessionShareNaming.Footer("Alex"), sessionShareNaming(listOf("Alex", " Alex ", "Alex")))
    }
    @Test fun mixedNamesArePerRowAndNeverAttributedToFirstAthlete() {
        assertEquals(SessionShareNaming.PerRow, sessionShareNaming(listOf("Alex", "Sam", "Alex")))
    }
    @Test fun partialNamesStayOnTheirOwnRows() {
        assertEquals(SessionShareNaming.PerRow, sessionShareNaming(listOf("Alex", null)))
        assertEquals(SessionShareNaming.PerRow, sessionShareNaming(listOf("", " Alex ", " ")))
    }
    @Test fun differingNamesAreNotSilentlyCaseFoldedIntoOneIdentity() {
        assertEquals(SessionShareNaming.PerRow, sessionShareNaming(listOf("Alex", "alex")))
    }
}
