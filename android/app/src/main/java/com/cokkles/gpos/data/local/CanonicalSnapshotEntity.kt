package com.cokkles.gpos.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "canonical_snapshot_cache")
data class CanonicalSnapshotEntity(
    @PrimaryKey val cacheKey: String = PRIMARY_CACHE_KEY,
    val contractVersion: String,
    val fetchedAtEpochMs: Long,
    val staleAfterEpochMs: Long,
    val payloadVersion: String? = null,
    val payloadHash: String? = null,
    val etag: String? = null,
    val payloadJson: String,
) {
    companion object {
        const val PRIMARY_CACHE_KEY = "canonical"
    }
}
