package com.cokkles.gpos.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class SyncStateReducerTest {
    private val t0 = Instant.parse("2026-08-29T16:40:00Z")

    @Test
    fun `started enters syncing state`() {
        assertEquals(
            SyncState.Syncing(t0),
            SyncStateReducer.reduce(SyncState.Idle, SyncEvent.Started(t0)),
        )
    }

    @Test
    fun `successful refresh enters ready network state`() {
        assertEquals(
            SyncState.Ready(t0, SnapshotSource.NETWORK),
            SyncStateReducer.reduce(
                SyncState.Syncing(t0.minusSeconds(2)),
                SyncEvent.Succeeded(t0, SnapshotSource.NETWORK),
            ),
        )
    }

    @Test
    fun `failed refresh records degraded cache availability`() {
        assertEquals(
            SyncState.Degraded(
                failedAt = t0,
                reason = UnavailableReason.NETWORK_ERROR,
                cachedDataAvailable = true,
            ),
            SyncStateReducer.reduce(
                SyncState.Syncing(t0.minusSeconds(2)),
                SyncEvent.Failed(
                    at = t0,
                    reason = UnavailableReason.NETWORK_ERROR,
                    cachedDataAvailable = true,
                ),
            ),
        )
    }
}
