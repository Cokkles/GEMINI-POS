package com.cokkles.gpos.platform.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationContractsTest {
    @Test
    fun `known routes resolve to typed targets`() {
        assertEquals(GposDeepLinkTarget.BRIEFING, GposDeepLinkTarget.fromRoute("briefing"))
        assertEquals(GposDeepLinkTarget.NOTIFICATIONS, GposDeepLinkTarget.fromRoute("notifications"))
        assertEquals(GposDeepLinkTarget.FINANCES, GposDeepLinkTarget.fromRoute("finances"))
    }

    @Test
    fun `unknown routes fail closed`() {
        assertNull(GposDeepLinkTarget.fromRoute("../../unexpected"))
        assertNull(GposDeepLinkTarget.fromRoute("https://example.invalid"))
        assertNull(GposDeepLinkTarget.fromRoute("insights?payload=secret"))
    }
}
