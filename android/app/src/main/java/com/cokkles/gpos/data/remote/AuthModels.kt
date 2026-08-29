package com.cokkles.gpos.data.remote

data class AuthConfig(
    val provider: String,
    val configured: Boolean,
    val allowlistConfigured: Boolean,
    val enforcementRequired: Boolean,
    val authVersion: String,
    val backendVersion: String,
    val clientId: String?,
)

data class AuthenticatedUser(
    val email: String,
    val name: String?,
    val pictureUrl: String?,
)

data class AuthenticatedSession(
    val user: AuthenticatedUser,
    val expiresAtEpochMs: Long?,
)

sealed interface AuthState {
    data object Restoring : AuthState
    data object SignedOut : AuthState
    data object Authenticating : AuthState
    data class Authenticated(
        val user: AuthenticatedUser,
        val expiresAtEpochMs: Long?,
    ) : AuthState
    data class Error(val message: String) : AuthState
}

enum class RuntimeDataSource {
    LIVE,
    CACHED,
    STALE,
}

data class BriefingRuntimeState(
    val plainText: String,
    val source: RuntimeDataSource,
    val fetchedAtEpochMs: Long,
    val error: String? = null,
)

data class DashboardRuntimeState(
    val snapshot: DashboardSnapshot,
    val source: RuntimeDataSource,
    val fetchedAtEpochMs: Long,
    val error: String? = null,
)

data class BackendRuntimeState(
    val checking: Boolean = false,
    val reachable: Boolean = false,
    val authConfig: AuthConfig? = null,
    val error: String? = null,
    val lastProtectedRead: String? = null,
)

data class RuntimeUiState(
    val backend: BackendRuntimeState = BackendRuntimeState(),
    val auth: AuthState = AuthState.Restoring,
    val briefing: BriefingRuntimeState? = null,
    val dashboard: DashboardRuntimeState? = null,
)
