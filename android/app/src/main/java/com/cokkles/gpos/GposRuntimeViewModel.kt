package com.cokkles.gpos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.cokkles.gpos.data.local.CanonicalSnapshotEntity
import com.cokkles.gpos.data.local.GposDatabase
import com.cokkles.gpos.data.remote.AegisBackendClient
import com.cokkles.gpos.data.remote.AuthState
import com.cokkles.gpos.data.remote.BackendRuntimeState
import com.cokkles.gpos.data.remote.BriefingRuntimeState
import com.cokkles.gpos.data.remote.RuntimeDataSource
import com.cokkles.gpos.data.remote.RuntimeUiState
import com.cokkles.gpos.platform.security.AndroidKeystoreCredentialStore
import com.cokkles.gpos.platform.security.CredentialStore
import com.cokkles.gpos.platform.security.StoredCredential
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

class GposRuntimeViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val backend = AegisBackendClient()
    private val credentialStore: CredentialStore = AndroidKeystoreCredentialStore(application)
    private val cacheDao = Room.databaseBuilder(
        application,
        GposDatabase::class.java,
        DATABASE_NAME,
    ).build().canonicalSnapshotDao()

    private val _uiState = MutableStateFlow(RuntimeUiState())
    val uiState: StateFlow<RuntimeUiState> = _uiState.asStateFlow()

    init {
        refreshBackendAndRestoreSession()
    }

    fun refreshBackendAndRestoreSession() {
        viewModelScope.launch {
            loadCachedBriefing()
            _uiState.update {
                it.copy(
                    backend = it.backend.copy(checking = true, error = null),
                    auth = AuthState.Restoring,
                )
            }

            val configResult = runCatching { backend.getAuthConfig() }
            val config = configResult.getOrNull()
            _uiState.update { state ->
                state.copy(
                    backend = BackendRuntimeState(
                        checking = false,
                        reachable = config != null,
                        authConfig = config,
                        error = configResult.exceptionOrNull()?.safeMessage(),
                        lastProtectedRead = state.backend.lastProtectedRead,
                    ),
                )
            }

            if (config == null || !config.configured) {
                _uiState.update { it.copy(auth = AuthState.SignedOut) }
                return@launch
            }

            if (
                config.clientId != null &&
                config.clientId != BuildConfig.GPOS_GOOGLE_SERVER_CLIENT_ID
            ) {
                _uiState.update {
                    it.copy(
                        auth = AuthState.Error(
                            "Backend OAuth audience does not match this Android build. Sign-in is blocked safely.",
                        ),
                    )
                }
                return@launch
            }

            restoreSession()
        }
    }

    fun authenticateWithIdToken(idToken: String) {
        if (idToken.isBlank()) {
            reportAuthFailure("Google did not return a usable identity token.")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(auth = AuthState.Authenticating) }
            runCatching { backend.authenticate(idToken) }
                .onSuccess { session ->
                    credentialStore.replace(
                        StoredCredential(
                            idToken = idToken,
                            expiresAtEpochMs = session.expiresAtEpochMs,
                        ),
                    )
                    _uiState.update {
                        it.copy(
                            auth = AuthState.Authenticated(
                                user = session.user,
                                expiresAtEpochMs = session.expiresAtEpochMs,
                            ),
                        )
                    }
                    refreshProtectedReads()
                    refreshLatestHorizon()
                }
                .onFailure { error ->
                    credentialStore.clear()
                    _uiState.update { it.copy(auth = AuthState.Error(error.safeMessage())) }
                }
        }
    }

    fun reportAuthFailure(message: String) {
        _uiState.update { it.copy(auth = AuthState.Error(message)) }
    }

    fun signOut() {
        viewModelScope.launch {
            val credential = credentialStore.read()
            credential?.idToken?.let { backend.logout(it) }
            credentialStore.clear()
            _uiState.update {
                it.copy(
                    auth = AuthState.SignedOut,
                    backend = it.backend.copy(lastProtectedRead = null),
                )
            }
        }
    }

    fun refreshProtectedReads() {
        viewModelScope.launch {
            val credential = credentialStore.read()
            if (credential == null) {
                _uiState.update { it.copy(auth = AuthState.SignedOut) }
                return@launch
            }

            runCatching {
                val health = backend.readHealth(credential.idToken)
                val dashboard = backend.readDashboard(credential.idToken)
                val healthStatus = health.optString("status", "success")
                val dashboardStatus = dashboard.optString("status", "success")
                "Health: $healthStatus • Dashboard: $dashboardStatus"
            }.onSuccess { summary ->
                _uiState.update {
                    it.copy(backend = it.backend.copy(lastProtectedRead = summary, error = null))
                }
            }.onFailure { error ->
                handleProtectedFailure(error)
            }
        }
    }

    fun refreshLatestHorizon() {
        viewModelScope.launch {
            val credential = credentialStore.read()
            if (credential == null) {
                loadCachedBriefing()
                return@launch
            }

            runCatching {
                val json = backend.readLatestHorizon(credential.idToken)
                val plainText = json.optString("plain_text").trim()
                if (plainText.isBlank()) {
                    throw IllegalStateException("Canonical HORIZON response contained no plain_text briefing.")
                }
                val fetchedAt = parseBackendTime(json) ?: System.currentTimeMillis()
                cacheDao.replace(
                    CanonicalSnapshotEntity(
                        cacheKey = HORIZON_CACHE_KEY,
                        contractVersion = HORIZON_CONTRACT,
                        fetchedAtEpochMs = fetchedAt,
                        staleAfterEpochMs = fetchedAt + HORIZON_FRESHNESS_MS,
                        payloadVersion = json.optString("version").takeIf { it.isNotBlank() },
                        payloadJson = json.toString(),
                    ),
                )
                BriefingRuntimeState(
                    plainText = plainText,
                    source = RuntimeDataSource.LIVE,
                    fetchedAtEpochMs = fetchedAt,
                )
            }.onSuccess { briefing ->
                _uiState.update { it.copy(briefing = briefing) }
            }.onFailure { error ->
                val cached = loadCachedBriefing(error.safeMessage())
                if (!cached) handleProtectedFailure(error)
            }
        }
    }

    private suspend fun restoreSession() {
        val credential = credentialStore.read()
        if (credential == null) {
            _uiState.update { it.copy(auth = AuthState.SignedOut) }
            return
        }

        val expiry = credential.expiresAtEpochMs
        if (expiry != null && expiry <= System.currentTimeMillis()) {
            credentialStore.clear()
            _uiState.update { it.copy(auth = AuthState.SignedOut) }
            return
        }

        runCatching { backend.validateSession(credential.idToken) }
            .onSuccess { session ->
                credentialStore.replace(
                    StoredCredential(
                        idToken = credential.idToken,
                        expiresAtEpochMs = session.expiresAtEpochMs,
                    ),
                )
                _uiState.update {
                    it.copy(
                        auth = AuthState.Authenticated(
                            user = session.user,
                            expiresAtEpochMs = session.expiresAtEpochMs,
                        ),
                    )
                }
                refreshProtectedReads()
                refreshLatestHorizon()
            }
            .onFailure {
                credentialStore.clear()
                _uiState.update { state -> state.copy(auth = AuthState.SignedOut) }
            }
    }

    private suspend fun loadCachedBriefing(error: String? = null): Boolean {
        val cached = cacheDao.read(HORIZON_CACHE_KEY) ?: return false
        val json = runCatching { JSONObject(cached.payloadJson) }.getOrNull() ?: return false
        val plainText = json.optString("plain_text").trim()
        if (plainText.isBlank()) return false
        val source = if (cached.staleAfterEpochMs < System.currentTimeMillis()) {
            RuntimeDataSource.STALE
        } else {
            RuntimeDataSource.CACHED
        }
        _uiState.update {
            it.copy(
                briefing = BriefingRuntimeState(
                    plainText = plainText,
                    source = source,
                    fetchedAtEpochMs = cached.fetchedAtEpochMs,
                    error = error,
                ),
            )
        }
        return true
    }

    private fun handleProtectedFailure(error: Throwable) {
        val message = error.safeMessage()
        viewModelScope.launch {
            if (message.contains("authentication", ignoreCase = true)) {
                credentialStore.clear()
                _uiState.update { it.copy(auth = AuthState.Error(message)) }
            }
            _uiState.update {
                it.copy(backend = it.backend.copy(lastProtectedRead = "Protected read failed", error = message))
            }
        }
    }

    private fun parseBackendTime(json: JSONObject): Long? {
        val values = listOf(
            json.optString("fetched_at"),
            json.optString("generated_at"),
            json.optString("last_updated"),
        )
        return values.firstNotNullOfOrNull { value ->
            value.takeIf { it.isNotBlank() }
                ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        }
    }

    private companion object {
        const val DATABASE_NAME = "gpos-cache.db"
        const val HORIZON_CACHE_KEY = "latest_horizon"
        const val HORIZON_CONTRACT = "AUTH-1/get_latest_horizon/plain_text"
        const val HORIZON_FRESHNESS_MS = 24L * 60L * 60L * 1000L
    }
}

private fun Throwable.safeMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: "The request could not be completed."
