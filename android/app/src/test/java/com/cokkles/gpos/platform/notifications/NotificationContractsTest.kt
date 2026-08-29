package com.cokkles.gpos.platform.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationContractsTest {
    @Test
    fun `known routes resolve to typed targets`() {
        assertEquals(
            GposDeepLinkTarget.BRIEFING,
            GposDeepLinkTarget.fromRoute("briefing"),
        )
    }

    @Test
    fun `unknown routes fail closed`() {
        assertNull(GposDeepLinkTarget.fromRoute("../../unexpected"))
        assertNull(GposDeepLinkTarget.fromRoute("https://example.invalid"))
    }
}
