package com.cokkles.gpos.platform.security

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleIdTokenExpiryTest {
    @Test fun readsJwtExpiryForRenewalScheduling() {
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"exp\":1893456000}".toByteArray())
        assertEquals(1_893_456_000_000L, GoogleIdTokenExpiry.parseEpochMs("header.$payload.signature"))
    }

    @Test fun malformedTokenDoesNotInventAnExpiry() {
        assertNull(GoogleIdTokenExpiry.parseEpochMs("not-a-jwt"))
        assertNull(GoogleIdTokenExpiry.parseEpochMs("header.invalid.signature"))
    }
}
