package com.cokkles.gpos.platform.diagnostics

import com.cokkles.gpos.data.Freshness
import com.cokkles.gpos.data.SyncState
import com.cokkles.gpos.domain.CompatibilityState
import com.cokkles.gpos.platform.connectivity.ConnectivityState

data class DiagnosticsSnapshot(
    val appVersion: String,
    val clientType: String = "GPOS_ANDROID",
    val authState: AuthState = AuthState.NOT_CONFIGURED,
    val connectivity: ConnectivityState = ConnectivityState.Unknown,
    val compatibility: CompatibilityState = CompatibilityState.UNKNOWN,
    val syncState: SyncState = SyncState.Idle,
    val cacheFreshness: Freshness? = null,
    val supportedContractVersions: Set<String> = emptySet(),
)

enum class AuthState {
    NOT_CONFIGURED,
    SIGNED_OUT,
    AUTHENTICATED,
    EXPIRED,
}
