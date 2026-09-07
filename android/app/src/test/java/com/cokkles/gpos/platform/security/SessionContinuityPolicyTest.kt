package com.cokkles.gpos.platform.security

import com.cokkles.gpos.data.remote.AuthState
import com.cokkles.gpos.data.remote.AuthenticatedUser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionContinuityPolicyTest {
    private val now = 1_000_000L
    private fun connected(expiry: Long?) = AuthState.Authenticated(AuthenticatedUser("test@example.invalid", null, null), expiry)

    @Test fun renewsExpiredOrSoonExpiringPreviouslyAuthorizedSession() {
        assertTrue(SessionContinuityPolicy.shouldRenew(connected(now - 1), true, now))
        assertTrue(SessionContinuityPolicy.shouldRenew(connected(now + 120_000), true, now))
        assertTrue(SessionContinuityPolicy.shouldRenew(AuthState.SignedOut, true, now))
        assertTrue(SessionContinuityPolicy.shouldRenew(AuthState.ReconnectRequired(now - 1, "Expired"), true, now))
    }

    @Test fun neverAutoSignsInAfterExplicitLogoutOrOnFirstUse() {
        assertFalse(SessionContinuityPolicy.shouldRenew(AuthState.SignedOut, false, now))
        assertFalse(SessionContinuityPolicy.shouldRenew(connected(now - 1), false, now))
    }

    @Test fun doesNotRetryConfigurationErrorsOrDuplicateInFlightAuthentication() {
        for (state in listOf(AuthState.Restoring, AuthState.Authenticating, AuthState.Error("Access denied"))) {
            assertFalse(SessionContinuityPolicy.shouldRenew(state, true, now))
        }
    }

    @Test fun preservesHealthyOrUnknownLifetimeSession() {
        assertFalse(SessionContinuityPolicy.shouldRenew(connected(now + 120_001), true, now))
        assertFalse(SessionContinuityPolicy.shouldRenew(connected(null), true, now))
        assertFalse(SessionContinuityPolicy.shouldRenew(AuthState.OfflineRestored(now + 600_000, "Offline"), true, now))
    }
}
