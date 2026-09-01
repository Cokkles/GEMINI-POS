package com.cokkles.gpos.platform.security

import android.content.Context

/**
 * Non-sensitive continuity hint. This stores no token, account identifier, or private data.
 * It only records whether the user previously completed an authenticated AEGIS session so the
 * launcher may attempt Credential Manager authorized-account renewal after token expiry.
 */
class AuthContinuityPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun wasAuthenticated(): Boolean = prefs.getBoolean(KEY_PREVIOUSLY_AUTHENTICATED, false)

    fun markAuthenticated() {
        prefs.edit().putBoolean(KEY_PREVIOUSLY_AUTHENTICATED, true).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_PREVIOUSLY_AUTHENTICATED).apply()
    }

    private companion object {
        const val PREFS_NAME = "aegis-auth-continuity"
        const val KEY_PREVIOUSLY_AUTHENTICATED = "previously_authenticated"
    }
}
