package com.cokkles.gpos.platform.security

import java.util.Base64
import org.json.JSONObject

/** Reads the signed JWT expiry claim without treating the unverified payload as authorization. */
object GoogleIdTokenExpiry {
    fun parseEpochMs(idToken: String): Long? {
        val payload = idToken.split('.').getOrNull(1)?.takeIf(String::isNotBlank) ?: return null
        return runCatching {
            val decoded = Base64.getUrlDecoder().decode(payload.padEnd((payload.length + 3) / 4 * 4, '='))
            val seconds = JSONObject(decoded.toString(Charsets.UTF_8)).optLong("exp")
            seconds.takeIf { it > 0L && it <= Long.MAX_VALUE / 1000L }?.times(1000L)
        }.getOrNull()
    }
}
