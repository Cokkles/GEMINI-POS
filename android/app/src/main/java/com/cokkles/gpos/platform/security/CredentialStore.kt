package com.cokkles.gpos.platform.security

/**
 * Storage boundary for authentication material.
 *
 * Implementations must use Android Keystore-backed protection and must never expose credentials
 * through Compose state, logs, intents, deep links, analytics, or plaintext persistence.
 */
interface CredentialStore {
    suspend fun read(): StoredCredential?
    suspend fun replace(credential: StoredCredential)
    suspend fun clear()
}

data class StoredCredential(
    val idToken: String,
    val expiresAtEpochMs: Long? = null,
    val userEmail: String? = null,
    val userName: String? = null,
    val userPictureUrl: String? = null,
    val validatedAtEpochMs: Long? = null,
)
