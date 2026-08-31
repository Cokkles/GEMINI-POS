package com.cokkles.gpos.ui.parity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayFormattingTest {
    @Test
    fun `machine tokens become human readable labels`() {
        assertEquals("Under target", humanizeToken("UNDER_TARGET"))
        assertEquals("Previous day", humanizeToken("PREVIOUS-DAY"))
        assertEquals("Live", humanizeToken("LIVE"))
    }

    @Test
    fun `blank tokens remain absent`() {
        assertNull(humanizeToken(null))
        assertNull(humanizeToken("   "))
    }

    @Test
    fun `count labels pluralize cleanly`() {
        assertEquals("0 alerts", countLabel(0, "alert"))
        assertEquals("1 alert", countLabel(1, "alert"))
        assertEquals("2 receipts", countLabel(2, "receipt"))
        assertEquals("1 story", countLabel(1, "story", "stories"))
        assertEquals("2 stories", countLabel(2, "story", "stories"))
    }
}
