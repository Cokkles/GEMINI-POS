package com.cokkles.gpos.platform.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionContinuityPolicyTest {
    private val now = 1_000_000L
    @Test fun restoresAStillValidEncryptedProfileWithoutWaitingForNetworkValidation() {
        val restored = SessionContinuityPolicy.restoredUser(
            StoredCredential(
                authToken = "token",
                expiresAtEpochMs = now + 600_000,
                userEmail = " test@example.invalid ",
                userName = "Test User",
                userPictureUrl = "https://example.invalid/avatar.png",
                validatedAtEpochMs = now,
            ),
            now,
        )

        assertNotNull(restored)
        assertTrue(restored?.email == "test@example.invalid")
        assertTrue(restored?.name == "Test User")
    }

    @Test fun refusesImmediateProfileRestoreWhenExpiredOrMissingIdentity() {
        assertNull(
            SessionContinuityPolicy.restoredUser(
                StoredCredential("token", now, userEmail = "test@example.invalid"),
                now,
            ),
        )
        assertNull(
            SessionContinuityPolicy.restoredUser(
                StoredCredential("token", now + 600_000),
                now,
            ),
        )
    }
}
