package com.cokkles.gpos.platform.security

import android.app.Activity
import android.util.Base64
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCustomException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.NoCredentialException
import com.cokkles.gpos.BuildConfig
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import java.security.SecureRandom

class GoogleSignInCoordinator(
    private val activity: Activity,
) {
    private val credentialManager = CredentialManager.create(activity)

    suspend fun requestIdToken(serverClientId: String): String {
        require(serverClientId.isNotBlank()) { "Google server client ID is not configured." }

        try {
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
                throw GoogleSignInDiagnosticException(
                    code = "UNSUPPORTED_CREDENTIAL",
                    message = "Google returned an unsupported credential type. No token was sent to AUTH-1.",
                )
            }
            return try {
                GoogleIdTokenCredential.createFrom(credential.data).idToken
            } catch (error: GoogleIdTokenParsingException) {
                throw GoogleSignInDiagnosticException(
                    code = "TOKEN_PARSE_FAILED",
                    message = "Google returned a credential that the installed googleid library could not parse.",
                    cause = error,
                )
            }
        } catch (error: GoogleSignInDiagnosticException) {
            throw error
        } catch (error: GetCredentialCancellationException) {
            throw GoogleSignInDiagnosticException(
                code = "USER_CANCELLED",
                message = "Google sign-in was cancelled before an identity token was issued.",
                cause = error,
            )
        } catch (error: NoCredentialException) {
            throw GoogleSignInDiagnosticException(
                code = "NO_CREDENTIAL",
                message = "No usable Google credential is currently available on this device. Check that a Google account is signed in and can authenticate.",
                cause = error,
            )
        } catch (error: GetCredentialProviderConfigurationException) {
            throw GoogleSignInDiagnosticException(
                code = "PROVIDER_CONFIGURATION",
                message = "Android Credential Manager is not correctly configured or available for this sign-in provider.",
                cause = error,
            )
        } catch (error: GetCredentialUnsupportedException) {
            throw GoogleSignInDiagnosticException(
                code = "CREDENTIAL_MANAGER_UNSUPPORTED",
                message = "Credential Manager is disabled or unsupported on this device.",
                cause = error,
            )
        } catch (error: GetCredentialInterruptedException) {
            throw GoogleSignInDiagnosticException(
                code = "CREDENTIAL_INTERRUPTED",
                message = "Google sign-in was interrupted. Retrying the sign-in flow is safe.",
                cause = error,
            )
        } catch (error: GetCredentialCustomException) {
            throw classifyOpaqueCredentialFailure(error)
        } catch (error: GetCredentialUnknownException) {
            throw classifyOpaqueCredentialFailure(error)
        }
    }

    suspend fun clearProviderState() {
        runCatching {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        }
    }

    private fun classifyOpaqueCredentialFailure(error: Exception): GoogleSignInDiagnosticException {
        val raw = error.message.orEmpty()
        val normalized = raw.lowercase()
        val looksLikeDeveloperConfiguration =
            normalized.contains("developer_error") ||
                normalized.contains("developer error") ||
                normalized.contains("configuration") ||
                Regex("(^|\\D)10(:|\\D)").containsMatchIn(normalized)

        return if (looksLikeDeveloperConfiguration) {
            GoogleSignInDiagnosticException(
                code = "ANDROID_OAUTH_CONFIGURATION",
                message = androidOauthRegistrationHint(),
                cause = error,
            )
        } else {
            GoogleSignInDiagnosticException(
                code = "CREDENTIAL_UNKNOWN",
                message = "Google Credential Manager could not complete sign-in before AUTH-1 was reached. ${raw.take(180)}".trim(),
                cause = error,
            )
        }
    }

    private fun androidOauthRegistrationHint(): String =
        "Google rejected the native Android identity before AUTH-1. Register Android OAuth client package ${BuildConfig.GPOS_ANDROID_PACKAGE} with SHA-1 ${BuildConfig.GPOS_CHECKPOINT_CERT_SHA1} in the same Google Cloud project as the server/web client ID."

    private fun generateNonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }
}

class GoogleSignInDiagnosticException(
    val code: String,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
