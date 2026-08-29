package com.cokkles.gpos.data

import java.time.Duration
import java.time.Instant

class DefaultCanonicalRepository(
    private val client: CanonicalReadClient,
    private val cache: CanonicalCache,
    private val now: () -> Instant = { Instant.now() },
    private val maxFreshAge: Duration = Duration.ofMinutes(15),
) : CanonicalRepository {

    override suspend fun current(): RepositoryResult {
        val cached = cache.read()
            ?: return RepositoryResult.Unavailable(UnavailableReason.EMPTY_CACHE)

        val freshness = if (isFresh(cached)) Freshness.FRESH else Freshness.STALE
        return RepositoryResult.Available(
            snapshot = cached.snapshot,
            freshness = freshness,
            source = SnapshotSource.CACHE,
        )
    }

    override suspend fun refresh(): RepositoryResult {
        return try {
            val snapshot = client.fetchSnapshot()
            cache.replace(snapshot, now())
            RepositoryResult.Available(
                snapshot = snapshot,
                freshness = Freshness.FRESH,
                source = SnapshotSource.NETWORK,
            )
        } catch (_: IncompatibleCanonicalContractException) {
            fallback(UnavailableReason.INCOMPATIBLE_CONTRACT)
        } catch (_: Exception) {
            fallback(UnavailableReason.NETWORK_ERROR)
        }
    }

    private suspend fun fallback(reason: UnavailableReason): RepositoryResult {
        val cached = cache.read()
        return if (cached == null) {
            RepositoryResult.Unavailable(reason)
        } else {
            RepositoryResult.Unavailable(
                reason = reason,
                cachedSnapshot = cached.snapshot,
            )
        }
    }

    private fun isFresh(cached: CachedCanonicalSnapshot): Boolean {
        val age = Duration.between(cached.cachedAt, now())
        return !age.isNegative && age <= maxFreshAge
    }
}

class IncompatibleCanonicalContractException(message: String) : Exception(message)
