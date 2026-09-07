package com.cokkles.gpos.platform.security

import com.cokkles.gpos.data.remote.AuthState

/** Renew through Google; never extend the lifetime of an existing identity token. */
object SessionContinuityPolicy {
    const val RENEW_BEFORE_MS = 120_000L

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
}
