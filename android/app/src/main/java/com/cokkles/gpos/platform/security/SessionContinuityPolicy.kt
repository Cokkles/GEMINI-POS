package com.cokkles.gpos.platform.security

import com.cokkles.gpos.data.remote.AuthenticatedUser

/** Restore backend-issued sessions without ever launching Google UI automatically. */
object SessionContinuityPolicy {
    const val RENEW_BEFORE_MS = 10L * 60L * 1000L

    fun restoredUser(credential: StoredCredential, now: Long): AuthenticatedUser? {
        if (credential.authToken.isBlank()) return null
        if (credential.expiresAtEpochMs?.let { it <= now } == true) return null
        val email = credential.userEmail?.trim().orEmpty()
        if (email.isBlank()) return null
        return AuthenticatedUser(
            email = email,
            name = credential.userName?.trim()?.takeIf(String::isNotBlank),
            pictureUrl = credential.userPictureUrl?.trim()?.takeIf(String::isNotBlank),
        )
    }
}
