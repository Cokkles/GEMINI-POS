package com.cokkles.gpos.platform.security

import com.cokkles.gpos.data.remote.AegisBackendException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundAuthenticationPolicyTest {
    @Test fun expiredTokenRequiresForegroundRenewalWithoutDestroyingContinuity() {
        assertTrue(BackgroundAuthenticationPolicy.requiresForegroundRenewal(999L, 1_000L))
        assertTrue(BackgroundAuthenticationPolicy.requiresForegroundRenewal(1_000L, 1_000L))
        assertFalse(BackgroundAuthenticationPolicy.requiresForegroundRenewal(1_001L, 1_000L))
        assertFalse(BackgroundAuthenticationPolicy.requiresForegroundRenewal(null, 1_000L))
    }

    @Test fun onlyAuth1RejectionsPauseWorkForRenewal() {
        assertTrue(BackgroundAuthenticationPolicy.isAuthenticationFailure(AegisBackendException("AEGIS_AUTH_REQUIRED", "renew")))
        assertTrue(BackgroundAuthenticationPolicy.isAuthenticationFailure(AegisBackendException("AEGIS_AUTH_FAILED", "renew")))
        assertFalse(BackgroundAuthenticationPolicy.isAuthenticationFailure(AegisBackendException("HTTP_503", "busy")))
        assertFalse(BackgroundAuthenticationPolicy.isAuthenticationFailure(IllegalStateException("other")))
    }
}
