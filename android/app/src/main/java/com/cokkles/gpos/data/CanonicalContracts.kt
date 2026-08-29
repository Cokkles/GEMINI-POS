package com.cokkles.gpos.data

import com.cokkles.gpos.domain.CanonicalSnapshot
import java.time.Instant

/**
 * Android A0 contract for canonical GPOS reads.
 *
 * This surface intentionally exposes no remote mutation methods. Future mutations must be
 * introduced as explicit, separately reviewed commands rather than being added here implicitly.
 */
interface CanonicalReadClient {
    suspend fun fetchSnapshot(): CanonicalSnapshot
}

interface CanonicalCache {
    suspend fun read(): CachedCanonicalSnapshot?
    suspend fun replace(snapshot: CanonicalSnapshot, cachedAt: Instant)
    suspend fun clear()
}

data class CachedCanonicalSnapshot(
    val snapshot: CanonicalSnapshot,
    val cachedAt: Instant,
)

interface CanonicalRepository {
    suspend fun current(): RepositoryResult
    suspend fun refresh(): RepositoryResult
}

sealed interface RepositoryResult {
    data class Available(
        val snapshot: CanonicalSnapshot,
        val freshness: Freshness,
        val source: SnapshotSource,
    ) : RepositoryResult

    data class Unavailable(
        val reason: UnavailableReason,
        val cachedSnapshot: CanonicalSnapshot? = null,
    ) : RepositoryResult
}

enum class Freshness {
    FRESH,
    STALE,
}

enum class SnapshotSource {
    CACHE,
    NETWORK,
}

enum class UnavailableReason {
    EMPTY_CACHE,
    NETWORK_ERROR,
    INCOMPATIBLE_CONTRACT,
}
