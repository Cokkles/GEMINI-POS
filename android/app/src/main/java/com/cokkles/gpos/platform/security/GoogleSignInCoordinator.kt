package com.cokkles.gpos.platform.security

import android.app.Activity
import android.content.MutableContextWrapper
import android.os.SystemClock
import android.util.Base64
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCustomException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.NoCredentialException
import com.cokkles.gpos.BuildConfig
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import java.security.SecureRandom
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException

class GoogleSignInCoordinator(
    private val activity: Activity,
) {
    private val credentialManager = CredentialManager.create(activity)

    suspend fun requestIdToken(serverClientId: String): String {
        require(serverClientId.isNotBlank()) { "Google server client ID is not configured." }

        val explicitStartedAt = SystemClock.elapsedRealtime()
        return try {
            requestExplicitButtonToken(serverClientId)
        } catch (error: GetCredentialCancellationException) {
            val elapsedMs = SystemClock.elapsedRealtime() - explicitStartedAt
            if (elapsedMs >= IMMEDIATE_ABORT_THRESHOLD_MS) {
                throw diagnostic(
                    code = "USER_CANCELLED",
                    detail = "Google sign-in was cancelled before an identity token was issued.",
                    cause = error,
                )
            }

            // A cancellation returned before Google UI could reasonably be dismissed is not
            // treated as proof of user cancellation. Retry once using Credential Manager's
            // account-selector flow, which is the companion flow recommended by Google.
            delay(PROVIDER_RETRY_DELAY_MS)
            val selectorStartedAt = SystemClock.elapsedRealtime()
            try {
                requestAccountSelectorToken(serverClientId)
            } catch (selectorCancellation: GetCredentialCancellationException) {
                val selectorElapsedMs = SystemClock.elapsedRealtime() - selectorStartedAt
                if (selectorElapsedMs < IMMEDIATE_ABORT_THRESHOLD_MS) {
                    throw diagnostic(
                        code = "IMMEDIATE_PROVIDER_ABORT",
                        detail = "Google identity exited immediately in both native sign-in flows before AUTH-1 was reached. ${androidOauthRegistrationHint()}",
                        cause = selectorCancellation,
                    )
                }
                throw diagnostic(
                    code = "USER_CANCELLED",
                    detail = "Google account selection was cancelled before an identity token was issued.",
                    cause = selectorCancellation,
                )
            } catch (selectorError: Exception) {
                throw classifyCredentialFailure(selectorError)
            }
        } catch (error: GoogleSignInDiagnosticException) {
            throw error
        } catch (error: Exception) {
            throw classifyCredentialFailure(error)
        }
    }

    /**
     * Best-effort continuity path for an account that previously authenticated successfully.
     * This deliberately filters to previously authorized Google accounts and enables provider
     * auto-selection. The caller must not surface failures as a fresh-login error because an
     * unavailable silent credential simply means the normal Sign in control should remain.
     */
    suspend fun requestAuthorizedIdToken(serverClientId: String): String {
        require(serverClientId.isNotBlank()) { "Google server client ID is not configured." }
        return try {
            val option = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(true)
                .setAutoSelectEnabled(true)
                .setServerClientId(serverClientId)
                .setNonce(generateNonce())
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build()
            parseIdToken(requestCredential(request))
        } catch (error: GoogleSignInDiagnosticException) {
            throw error
        } catch (error: Exception) {
            throw classifyCredentialFailure(error)
        }
    }

    suspend fun clearProviderState() {
        runCatching {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        }
    }

    private suspend fun requestExplicitButtonToken(serverClientId: String): String {
        val option = GetSignInWithGoogleOption.Builder(serverClientId)
            .setNonce(generateNonce())
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()
        return parseIdToken(requestCredential(request))
    }

    private suspend fun requestAccountSelectorToken(serverClientId: String): String {
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .setServerClientId(serverClientId)
            .setNonce(generateNonce())
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()
        return parseIdToken(requestCredential(request))
    }

    private suspend fun requestCredential(request: GetCredentialRequest): GetCredentialResponse {
        // Google currently recommends an activity-based MutableContextWrapper for Credential
        // Manager UI so configuration changes do not leave stale activity references.
        val uiContext = MutableContextWrapper(activity)
        return credentialManager.getCredential(
            context = uiContext,
            request = request,
        )
    }

    private fun parseIdToken(result: GetCredentialResponse): String {
        val credential = result.credential
        if (
            credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            throw diagnostic(
                code = "UNSUPPORTED_CREDENTIAL",
                detail = "Google returned an unsupported credential type. No token was sent to AUTH-1.",
            )
        }
        return try {
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } catch (error: GoogleIdTokenParsingException) {
            throw diagnostic(
                code = "TOKEN_PARSE_FAILED",
                detail = "Google returned a credential that the installed googleid library could not parse.",
                cause = error,
            )
        }
    }

    private fun classifyCredentialFailure(error: Exception): GoogleSignInDiagnosticException = when (error) {
        is CancellationException -> throw error
        is GoogleSignInDiagnosticException -> error
        is GetCredentialCancellationException -> diagnostic(
            code = "USER_CANCELLED",
            detail = "Google sign-in was cancelled before an identity token was issued.",
            cause = error,
        )
        is NoCredentialException -> diagnostic(
            code = "NO_CREDENTIAL",
            detail = "No usable Google credential is currently available on this device. Check that a Google account is signed in and can authenticate.",
            cause = error,
        )
        is GetCredentialProviderConfigurationException -> diagnostic(
            code = "PROVIDER_CONFIGURATION",
            detail = "Android Credential Manager is not correctly configured or available for this sign-in provider. ${androidOauthRegistrationHint()}",
            cause = error,
        )
        is GetCredentialUnsupportedException -> diagnostic(
            code = "CREDENTIAL_MANAGER_UNSUPPORTED",
            detail = "Credential Manager is disabled or unsupported on this device.",
            cause = error,
        )
        is GetCredentialInterruptedException -> diagnostic(
            code = "CREDENTIAL_INTERRUPTED",
            detail = "Google sign-in was interrupted. Retrying the sign-in flow is safe.",
            cause = error,
        )
        is GetCredentialCustomException,
        is GetCredentialUnknownException -> classifyOpaqueCredentialFailure(error)
        else -> diagnostic(
            code = "CREDENTIAL_UNEXPECTED",
            detail = "Google identity failed before AUTH-1. ${error::class.java.simpleName}: ${error.message.orEmpty().take(160)}".trim(),
            cause = error,
        )
    }

    private fun classifyOpaqueCredentialFailure(error: Exception): GoogleSignInDiagnosticException {
        val raw = error.message.orEmpty()
        val normalized = raw.lowercase()
        val looksLikeDeveloperConfiguration =
            normalized.contains("developer_error") ||
                normalized.contains("developer error") ||
                normalized.contains("configuration") ||
                normalized.contains("oauth") ||
                Regex("(^|\\D)10(:|\\D)").containsMatchIn(normalized)

        return if (looksLikeDeveloperConfiguration) {
            diagnostic(
                code = "ANDROID_OAUTH_CONFIGURATION",
                detail = androidOauthRegistrationHint(),
                cause = error,
            )
        } else {
            diagnostic(
                code = "CREDENTIAL_UNKNOWN",
                detail = "Google Credential Manager could not complete sign-in before AUTH-1 was reached. ${raw.take(180)}".trim(),
                cause = error,
            )
        }
    }

    private fun diagnostic(
        code: String,
        detail: String,
        cause: Throwable? = null,
    ): GoogleSignInDiagnosticException = GoogleSignInDiagnosticException(
        code = code,
        message = "$code • $detail",
        cause = cause,
    )

    private fun androidOauthRegistrationHint(): String =
        "Verify an Android OAuth client exists in Google Cloud project 441009275873 for package ${BuildConfig.GPOS_ANDROID_PACKAGE} with SHA-1 ${BuildConfig.GPOS_CHECKPOINT_CERT_SHA1}; keep the existing web/server client ID as the ID-token audience."

    private fun generateNonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }

    private companion object {
        const val IMMEDIATE_ABORT_THRESHOLD_MS = 1_500L
        const val PROVIDER_RETRY_DELAY_MS = 250L
    }
}

class GoogleSignInDiagnosticException(
    val code: String,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
