package com.cokkles.gpos.platform.security

import com.cokkles.gpos.data.remote.AegisBackendException

/** Pure decisions shared by background workers; no worker may erase a durable session. */
object BackgroundAuthenticationPolicy {
    private val authFailureCodes = setOf("AEGIS_AUTH_REQUIRED", "AEGIS_AUTH_FAILED")

    fun requiresForegroundRenewal(expiresAtEpochMs: Long?, nowEpochMs: Long): Boolean =
        expiresAtEpochMs?.let { it <= nowEpochMs + SessionContinuityPolicy.RENEW_BEFORE_MS } == true

    fun isAuthenticationFailure(error: Throwable?): Boolean =
        error is AegisBackendException && error.code in authFailureCodes
}
