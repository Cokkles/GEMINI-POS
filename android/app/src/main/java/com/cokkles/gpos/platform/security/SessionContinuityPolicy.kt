package com.cokkles.gpos.platform.security

import com.cokkles.gpos.data.remote.AuthState
import com.cokkles.gpos.data.remote.AuthenticatedUser

/** Renew through Google; never extend the lifetime of an existing identity token. */
object SessionContinuityPolicy {
    const val RENEW_BEFORE_MS = 10L * 60L * 1000L
    const val MIN_RECHECK_MS = 30_000L
    const val MAX_RECHECK_MS = 5L * 60L * 1000L
    const val UNKNOWN_EXPIRY_RECHECK_MS = 10L * 60L * 1000L

    fun renewalRetryDelayMs(consecutiveFailures: Int): Long = when (consecutiveFailures) {
        0 -> MIN_RECHECK_MS
        1 -> MIN_RECHECK_MS
        2 -> 60_000L
        3 -> 2L * 60L * 1000L
        4 -> 5L * 60L * 1000L
        else -> 10L * 60L * 1000L
    }

    fun shouldRenew(auth: AuthState, previouslyAuthenticated: Boolean, now: Long): Boolean {
        if (!previouslyAuthenticated) return false
        return when (auth) {
            AuthState.SignedOut -> true
            is AuthState.Authenticated -> auth.expiresAtEpochMs?.let { it <= now + RENEW_BEFORE_MS } == true
            is AuthState.OfflineRestored -> auth.expiresAtEpochMs?.let { it <= now + RENEW_BEFORE_MS } == true
            is AuthState.ReconnectRequired -> true
            else -> false // Do not retry backend authorization/configuration errors.
        }
    }

    fun restoredUser(credential: StoredCredential, now: Long): AuthenticatedUser? {
        if (credential.idToken.isBlank()) return null
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
