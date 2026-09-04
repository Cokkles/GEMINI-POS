package com.cokkles.gpos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.cokkles.gpos.data.CanonicalCachePolicy
import com.cokkles.gpos.data.local.CanonicalSnapshotEntity
import com.cokkles.gpos.data.local.GposDatabase
import com.cokkles.gpos.data.remote.AegisBackendClient
import com.cokkles.gpos.data.remote.AegisBackendException
import com.cokkles.gpos.data.remote.AuthState
import com.cokkles.gpos.data.remote.BackendRuntimeState
import com.cokkles.gpos.data.remote.BriefingRuntimeState
import com.cokkles.gpos.data.remote.DashboardPayloadMapper
import com.cokkles.gpos.data.remote.DashboardRuntimeState
import com.cokkles.gpos.data.remote.FinancePayloadMapper
import com.cokkles.gpos.data.remote.FinanceRuntimeState
import com.cokkles.gpos.data.remote.NotificationsPayloadMapper
import com.cokkles.gpos.data.remote.NotificationsRuntimeState
import com.cokkles.gpos.data.remote.RuntimeDataSource
import com.cokkles.gpos.data.remote.RuntimeUiState
import com.cokkles.gpos.platform.security.AndroidKeystoreCredentialStore
import com.cokkles.gpos.platform.security.CredentialStore
import com.cokkles.gpos.platform.security.StoredCredential
import com.cokkles.gpos.platform.sync.CanonicalSyncScheduler
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import org.json.JSONObject

class GposRuntimeViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val backend = AegisBackendClient()
    private val credentialStore: CredentialStore = AndroidKeystoreCredentialStore(application)
    private val syncScheduler = CanonicalSyncScheduler(application)
    private val cacheDao = Room.databaseBuilder(
        application,
        GposDatabase::class.java,
        CanonicalCachePolicy.DATABASE_NAME,
    ).build().canonicalSnapshotDao()

    private val _uiState = MutableStateFlow(RuntimeUiState())
    val uiState: StateFlow<RuntimeUiState> = _uiState.asStateFlow()

    private val activeReads = mutableSetOf<String>()
    private var sessionJob: Job? = null
    private val readJobs = mutableMapOf<String, Job>()

    private fun launchRead(key: String, block: suspend () -> Unit) {
        if (!activeReads.add(key)) return
        _uiState.update { it.copy(refreshing = true) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try { block() } finally {
                readJobs.remove(key)
                activeReads.remove(key)
                _uiState.update { it.copy(refreshing = activeReads.isNotEmpty()) }
            }
        }
        readJobs[key] = job
        job.start()
    }

    private fun cancelReads() {
        readJobs.values.toList().forEach { it.cancel() }
    }

    init {
        refreshBackendAndRestoreSession()
    }

    fun refreshBackendAndRestoreSession() {
        if (sessionJob?.isActive == true) return
        sessionJob = viewModelScope.launch {
            val storedCredential = credentialStore.read()
            if (storedCredential != null) {
                loadCachedDashboard()
                loadCachedBriefing()
                loadCachedFinance()
                loadCachedNotifications()
            } else {
                cacheDao.clear()
                clearPrivateUiState()
            }

            _uiState.update {
                it.copy(
                    backend = it.backend.copy(checking = true, error = null),
                    auth = AuthState.Restoring,
                )
            }

            val configResult = runCatching { backend.getAuthConfig() }
            configResult.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            val config = configResult.getOrNull()
            val configError = configResult.exceptionOrNull()?.safeMessage()
            _uiState.update { state ->
                state.copy(
                    backend = BackendRuntimeState(
                        checking = false,
                        reachable = config != null,
                        authConfig = config,
                        error = configError,
                        lastProtectedRead = state.backend.lastProtectedRead,
                    ),
                )
            }

            if (config == null) {
                if (!restoreOfflineSession(configError ?: "Backend discovery is unavailable.")) {
                    _uiState.update { it.copy(auth = AuthState.SignedOut) }
                }
                return@launch
            }

            if (!config.configured) {
                clearPrivateSession()
                _uiState.update {
                    it.copy(auth = AuthState.Error("AEGIS authentication is not configured on the backend."))
                }
                return@launch
            }

            if (
                config.clientId != null &&
                config.clientId != BuildConfig.GPOS_GOOGLE_SERVER_CLIENT_ID &&
                !config.additionalAudiencesConfigured
            ) {
                clearPrivateSession()
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
        sessionJob?.cancel()
        cancelReads()
        sessionJob = viewModelScope.launch {
            val previousAuth = _uiState.value.auth
            val previousCredential = credentialStore.read()
            _uiState.update { it.copy(auth = AuthState.Authenticating) }
            runCatching { backend.authenticate(idToken) }
                .onSuccess { session ->
                    credentialStore.replace(
                        StoredCredential(
                            idToken = idToken,
                            expiresAtEpochMs = session.expiresAtEpochMs,
                        ),
                    )
                    syncScheduler.schedule()
                    _uiState.update {
                        it.copy(
                            auth = AuthState.Authenticated(
                                user = session.user,
                                expiresAtEpochMs = session.expiresAtEpochMs,
                            ),
                        )
                    }
                    refreshCanonicalReads()
                }
                .onFailure { error ->
                        if (error is CancellationException) throw error
                        val stillValid = previousCredential?.expiresAtEpochMs?.let { it > System.currentTimeMillis() } == true
                        if (!isAuthenticationFailure(error) && stillValid) {
                            _uiState.update { it.copy(auth = previousAuth, backend = it.backend.copy(error = error.safeMessage())) }
                        } else {
                            clearPrivateSession()
                            _uiState.update { it.copy(auth = AuthState.Error(error.safeMessage())) }
                        }
                }
        }
    }

    fun reportAuthFailure(message: String) {
        _uiState.update { it.copy(auth = AuthState.Error(message)) }
    }

    fun signOut() {
        sessionJob?.cancel()
        cancelReads()
        clearPrivateUiState()
        _uiState.update { it.copy(auth = AuthState.SignedOut) }
        sessionJob = viewModelScope.launch {
            val credential = credentialStore.read()
            clearPrivateSession()
            _uiState.update {
                it.copy(
                    auth = AuthState.SignedOut,
                    backend = it.backend.copy(lastProtectedRead = null),
                )
            }
            credential?.idToken?.let { backend.logout(it) }
        }
    }

    fun refreshCanonicalReads() {
        refreshProtectedReads()
        refreshDashboard()
        refreshLatestHorizon()
        refreshFinance()
        refreshNotifications()
    }

    fun refreshProtectedReads() {
        launchRead("refreshProtectedReads") {
            val credential = credentialStore.read()
            if (credential == null) {
                _uiState.update { it.copy(auth = AuthState.SignedOut) }
                return@launchRead
            }

            runCatching {
                val health = backend.readHealth(credential.idToken)
                val capabilities = backend.readCapabilities(credential.idToken)
                val healthStatus = health.optString("status", "success")
                val capabilityStatus = capabilities.optString("status", "success")
                "Health: $healthStatus • Capabilities: $capabilityStatus"
            }.onSuccess { summary ->
                _uiState.update {
                    it.copy(backend = it.backend.copy(lastProtectedRead = summary, error = null))
                }
            }.onFailure { error ->
                handleProtectedFailure(error)
            }
        }
    }

    fun refreshDashboard() {
        launchRead("refreshDashboard") {
            val credential = credentialStore.read() ?: return@launchRead
            runCatching {
                val json = backend.readDashboard(credential.idToken)
                val snapshot = DashboardPayloadMapper.map(json)
                val fetchedAt = System.currentTimeMillis()
                cache(
                    key = CanonicalCachePolicy.DASHBOARD_KEY,
                    contract = CanonicalCachePolicy.DASHBOARD_CONTRACT,
                    freshnessMs = CanonicalCachePolicy.DASHBOARD_FRESHNESS_MS,
                    json = json,
                    fetchedAt = fetchedAt,
                )
                DashboardRuntimeState(snapshot, RuntimeDataSource.LIVE, fetchedAt)
            }.onSuccess { dashboard ->
                _uiState.update { it.copy(dashboard = dashboard, backend = it.backend.copy(error = null)) }
            }.onFailure { error ->
                onDomainReadFailure(error) { loadCachedDashboard(error.safeMessage()) }
            }
        }
    }

    fun refreshLatestHorizon() {
        launchRead("refreshLatestHorizon") {
            val credential = credentialStore.read() ?: return@launchRead
            runCatching {
                val json = backend.readLatestHorizon(credential.idToken)
                val plainText = json.optString("plain_text").trim()
                if (plainText.isBlank()) {
                    throw IllegalStateException("Canonical HORIZON response contained no plain_text briefing.")
                }
                val fetchedAt = parseBackendTime(json) ?: System.currentTimeMillis()
                cache(
                    key = CanonicalCachePolicy.HORIZON_KEY,
                    contract = CanonicalCachePolicy.HORIZON_CONTRACT,
                    freshnessMs = CanonicalCachePolicy.HORIZON_FRESHNESS_MS,
                    json = json,
                    fetchedAt = fetchedAt,
                )
                BriefingRuntimeState(plainText, RuntimeDataSource.LIVE, fetchedAt)
            }.onSuccess { briefing ->
                _uiState.update { it.copy(briefing = briefing) }
            }.onFailure { error ->
                onDomainReadFailure(error) { loadCachedBriefing(error.safeMessage()) }
            }
        }
    }

    fun refreshFinance() {
        launchRead("refreshFinance") {
            val credential = credentialStore.read() ?: return@launchRead
            runCatching {
                val json = backend.readRecentFinance(credential.idToken, FINANCE_HOURS)
                val snapshot = FinancePayloadMapper.map(json, FINANCE_HOURS)
                val fetchedAt = System.currentTimeMillis()
                cache(
                    key = CanonicalCachePolicy.FINANCE_KEY,
                    contract = CanonicalCachePolicy.FINANCE_CONTRACT,
                    freshnessMs = CanonicalCachePolicy.FINANCE_FRESHNESS_MS,
                    json = json,
                    fetchedAt = fetchedAt,
                )
                FinanceRuntimeState(snapshot, RuntimeDataSource.LIVE, fetchedAt)
            }.onSuccess { finance ->
                _uiState.update { it.copy(finance = finance) }
            }.onFailure { error ->
                onDomainReadFailure(error) { loadCachedFinance(error.safeMessage()) }
            }
        }
    }

    fun refreshNotifications() {
        launchRead("refreshNotifications") {
            val credential = credentialStore.read() ?: return@launchRead
            runCatching {
                val json = backend.readNotifications(credential.idToken)
                val snapshot = NotificationsPayloadMapper.map(json)
                val fetchedAt = System.currentTimeMillis()
                cache(
                    key = CanonicalCachePolicy.NOTIFICATIONS_KEY,
                    contract = CanonicalCachePolicy.NOTIFICATIONS_CONTRACT,
                    freshnessMs = CanonicalCachePolicy.NOTIFICATIONS_FRESHNESS_MS,
                    json = json,
                    fetchedAt = fetchedAt,
                )
                NotificationsRuntimeState(snapshot, RuntimeDataSource.LIVE, fetchedAt)
            }.onSuccess { notifications ->
                _uiState.update { it.copy(notifications = notifications) }
            }.onFailure { error ->
                onDomainReadFailure(error) { loadCachedNotifications(error.safeMessage()) }
            }
        }
    }

    private suspend fun restoreSession() {
        val credential = credentialStore.read()
        if (credential == null) {
            cacheDao.clear()
            clearPrivateUiState()
            _uiState.update { it.copy(auth = AuthState.SignedOut) }
            return
        }

        val expiry = credential.expiresAtEpochMs
        if (expiry != null && expiry <= System.currentTimeMillis()) {
            clearPrivateSession()
            _uiState.update { it.copy(auth = AuthState.SignedOut) }
            return
        }

        val result = runCatching { backend.validateSession(credential.idToken) }
        val session = result.getOrNull()
        if (session != null) {
            credentialStore.replace(StoredCredential(credential.idToken, session.expiresAtEpochMs))
            syncScheduler.schedule()
            _uiState.update {
                it.copy(
                    auth = AuthState.Authenticated(
                        user = session.user,
                        expiresAtEpochMs = session.expiresAtEpochMs,
                    ),
                )
            }
            refreshCanonicalReads()
            return
        }

        val error = result.exceptionOrNull() ?: return
        if (error is CancellationException) throw error
        if (isAuthenticationFailure(error)) {
            clearPrivateSession()
            _uiState.update { it.copy(auth = AuthState.Error(error.safeMessage())) }
        } else {
            syncScheduler.schedule()
            _uiState.update {
                it.copy(
                    auth = AuthState.OfflineRestored(
                        expiresAtEpochMs = credential.expiresAtEpochMs,
                        reason = error.safeMessage(),
                    ),
                    backend = it.backend.copy(error = error.safeMessage()),
                )
            }
        }
    }

    private suspend fun restoreOfflineSession(reason: String): Boolean {
        val credential = credentialStore.read() ?: return false
        val expiry = credential.expiresAtEpochMs
        if (expiry != null && expiry <= System.currentTimeMillis()) {
            clearPrivateSession()
            return false
        }
        syncScheduler.schedule()
        _uiState.update {
            it.copy(
                auth = AuthState.OfflineRestored(
                    expiresAtEpochMs = expiry,
                    reason = reason,
                ),
            )
        }
        return true
    }

    private suspend fun clearPrivateSession() {
        syncScheduler.cancel()
        credentialStore.clear()
        cacheDao.clear()
        clearPrivateUiState()
    }

    private fun clearPrivateUiState() {
        _uiState.update {
            it.copy(briefing = null, dashboard = null, finance = null, notifications = null)
        }
    }

    private suspend fun loadCachedDashboard(error: String? = null): Boolean {
        if (credentialStore.read() == null) return false
        val cached = cacheDao.read(CanonicalCachePolicy.DASHBOARD_KEY) ?: return false
        val json = runCatching { JSONObject(cached.payloadJson) }.getOrNull() ?: return false
        val snapshot = runCatching { DashboardPayloadMapper.map(json) }.getOrNull() ?: return false
        _uiState.update {
            it.copy(
                dashboard = DashboardRuntimeState(
                    snapshot,
                    cachedSource(cached.staleAfterEpochMs),
                    cached.fetchedAtEpochMs,
                    error,
                ),
            )
        }
        return true
    }

    private suspend fun loadCachedBriefing(error: String? = null): Boolean {
        if (credentialStore.read() == null) return false
        val cached = cacheDao.read(CanonicalCachePolicy.HORIZON_KEY) ?: return false
        val json = runCatching { JSONObject(cached.payloadJson) }.getOrNull() ?: return false
        val plainText = json.optString("plain_text").trim()
        if (plainText.isBlank()) return false
        _uiState.update {
            it.copy(
                briefing = BriefingRuntimeState(
                    plainText,
                    cachedSource(cached.staleAfterEpochMs),
                    cached.fetchedAtEpochMs,
                    error,
                ),
            )
        }
        return true
    }

    private suspend fun loadCachedFinance(error: String? = null): Boolean {
        if (credentialStore.read() == null) return false
        val cached = cacheDao.read(CanonicalCachePolicy.FINANCE_KEY) ?: return false
        val json = runCatching { JSONObject(cached.payloadJson) }.getOrNull() ?: return false
        val snapshot = runCatching { FinancePayloadMapper.map(json, FINANCE_HOURS) }.getOrNull() ?: return false
        _uiState.update {
            it.copy(
                finance = FinanceRuntimeState(
                    snapshot,
                    cachedSource(cached.staleAfterEpochMs),
                    cached.fetchedAtEpochMs,
                    error,
                ),
            )
        }
        return true
    }

    private suspend fun loadCachedNotifications(error: String? = null): Boolean {
        if (credentialStore.read() == null) return false
        val cached = cacheDao.read(CanonicalCachePolicy.NOTIFICATIONS_KEY) ?: return false
        val json = runCatching { JSONObject(cached.payloadJson) }.getOrNull() ?: return false
        val snapshot = runCatching { NotificationsPayloadMapper.map(json) }.getOrNull() ?: return false
        _uiState.update {
            it.copy(
                notifications = NotificationsRuntimeState(
                    snapshot,
                    cachedSource(cached.staleAfterEpochMs),
                    cached.fetchedAtEpochMs,
                    error,
                ),
            )
        }
        return true
    }

    private suspend fun cache(
        key: String,
        contract: String,
        freshnessMs: Long,
        json: JSONObject,
        fetchedAt: Long,
    ) {
        cacheDao.replace(
            CanonicalSnapshotEntity(
                cacheKey = key,
                contractVersion = contract,
                fetchedAtEpochMs = fetchedAt,
                staleAfterEpochMs = fetchedAt + freshnessMs,
                payloadVersion = json.optString("version").takeIf { it.isNotBlank() },
                payloadJson = json.toString(),
            ),
        )
    }

    private suspend fun onDomainReadFailure(
        error: Throwable,
        cachedFallback: suspend () -> Boolean,
    ) {
        if (error is CancellationException) throw error
        if (isAuthenticationFailure(error)) {
            handleProtectedFailure(error)
        } else if (!cachedFallback()) {
            _uiState.update { it.copy(backend = it.backend.copy(error = error.safeMessage())) }
        }
    }

    private fun handleProtectedFailure(error: Throwable) {
        if (error is CancellationException) throw error
        val message = error.safeMessage()
        viewModelScope.launch {
            if (isAuthenticationFailure(error)) {
                clearPrivateSession()
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

    private fun cachedSource(staleAfterEpochMs: Long): RuntimeDataSource =
        if (staleAfterEpochMs < System.currentTimeMillis()) RuntimeDataSource.STALE else RuntimeDataSource.CACHED

    private fun isAuthenticationFailure(error: Throwable): Boolean {
        val message = error.safeMessage().lowercase()
        return (error is AegisBackendException && error.code in AUTH_FAILURE_CODES) ||
            message.contains("authentication") ||
            message.contains("identity token") ||
            message.contains("not authorized")
    }

    private companion object {
        const val FINANCE_HOURS = 72
        val AUTH_FAILURE_CODES = setOf("AEGIS_AUTH_REQUIRED", "AEGIS_AUTH_FAILED")
    }
}

private fun Throwable.safeMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: "The request could not be completed."
