package com.cokkles.gpos.platform.security

import android.app.Activity
import android.util.Base64
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.SecureRandom

class GoogleSignInCoordinator(
    private val activity: Activity,
) {
    private val credentialManager = CredentialManager.create(activity)

    suspend fun requestIdToken(serverClientId: String): String {
        require(serverClientId.isNotBlank()) { "Google server client ID is not configured." }

        val googleOption = GetSignInWithGoogleOption.Builder(serverClientId)
            .setNonce(generateNonce())
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleOption)
            .build()
        val result = credentialManager.getCredential(
            context = activity,
            request = request,
        )
        val credential = result.credential
        if (
            credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            throw IllegalStateException("Google Sign-In returned an unsupported credential type.")
        }
        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }

    suspend fun clearProviderState() {
        runCatching {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        }
    }

    private fun generateNonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }
}
