package com.cokkles.gpos.data

import java.time.Instant

sealed interface SyncState {
    data object Idle : SyncState

    data class Syncing(
        val startedAt: Instant,
    ) : SyncState

    data class Ready(
        val lastSuccessfulSyncAt: Instant,
        val source: SnapshotSource,
    ) : SyncState

    data class Degraded(
        val failedAt: Instant,
        val reason: UnavailableReason,
        val cachedDataAvailable: Boolean,
    ) : SyncState
}

sealed interface SyncEvent {
    data class Started(val at: Instant) : SyncEvent
    data class Succeeded(val at: Instant, val source: SnapshotSource) : SyncEvent
    data class Failed(
        val at: Instant,
        val reason: UnavailableReason,
        val cachedDataAvailable: Boolean,
    ) : SyncEvent
}

object SyncStateReducer {
    fun reduce(current: SyncState, event: SyncEvent): SyncState = when (event) {
        is SyncEvent.Started -> SyncState.Syncing(event.at)
        is SyncEvent.Succeeded -> SyncState.Ready(event.at, event.source)
        is SyncEvent.Failed -> SyncState.Degraded(
            failedAt = event.at,
            reason = event.reason,
            cachedDataAvailable = event.cachedDataAvailable,
        )
    }
}
