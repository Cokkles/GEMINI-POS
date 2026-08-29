package com.cokkles.gpos.data

import com.cokkles.gpos.domain.CanonicalSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class DefaultCanonicalRepositoryTest {
    private val now = Instant.parse("2026-08-29T16:30:00Z")

    @Test
    fun `current reports empty cache without touching network`() {
        val client = FakeClient(snapshot())
        val cache = FakeCache()
        val repository = DefaultCanonicalRepository(client, cache, now = { now })

        val result = runSuspend { repository.current() }

        assertEquals(
            RepositoryResult.Unavailable(UnavailableReason.EMPTY_CACHE),
            result,
        )
        assertEquals(0, client.fetchCount)
    }

    @Test
    fun `refresh stores successful canonical network snapshot`() {
        val expected = snapshot()
        val client = FakeClient(expected)
        val cache = FakeCache()
        val repository = DefaultCanonicalRepository(client, cache, now = { now })

        val result = runSuspend { repository.refresh() }

        assertEquals(
            RepositoryResult.Available(expected, Freshness.FRESH, SnapshotSource.NETWORK),
            result,
        )
        assertEquals(expected, cache.value?.snapshot)
        assertEquals(now, cache.value?.cachedAt)
    }

    @Test
    fun `refresh failure preserves cached snapshot as fallback`() {
        val cached = snapshot(Instant.parse("2026-08-29T16:00:00Z"))
        val client = FakeClient(snapshot(), failure = IllegalStateException("offline"))
        val cache = FakeCache(CachedCanonicalSnapshot(cached, now.minusSeconds(3600)))
        val repository = DefaultCanonicalRepository(client, cache, now = { now })

        val result = runSuspend { repository.refresh() }

        assertEquals(
            RepositoryResult.Unavailable(UnavailableReason.NETWORK_ERROR, cached),
            result,
        )
        assertEquals(cached, cache.value?.snapshot)
    }

    @Test
    fun `canonical read client exposes no mutation commands`() {
        val methodNames = CanonicalReadClient::class.java.declaredMethods.map { it.name }.toSet()

        assertEquals(setOf("fetchSnapshot"), methodNames)
        assertNull(methodNames.firstOrNull { it.contains("update", ignoreCase = true) })
        assertNull(methodNames.firstOrNull { it.contains("delete", ignoreCase = true) })
        assertNull(methodNames.firstOrNull { it.contains("create", ignoreCase = true) })
    }

    private fun snapshot(generatedAt: Instant = now) = CanonicalSnapshot(generatedAt = generatedAt)
}

private class FakeClient(
    private val value: CanonicalSnapshot,
    private val failure: Exception? = null,
) : CanonicalReadClient {
    var fetchCount: Int = 0

    override suspend fun fetchSnapshot(): CanonicalSnapshot {
        fetchCount += 1
        failure?.let { throw it }
        return value
    }
}

private class FakeCache(
    initial: CachedCanonicalSnapshot? = null,
) : CanonicalCache {
    var value: CachedCanonicalSnapshot? = initial

    override suspend fun read(): CachedCanonicalSnapshot? = value

    override suspend fun replace(snapshot: CanonicalSnapshot, cachedAt: Instant) {
        value = CachedCanonicalSnapshot(snapshot, cachedAt)
    }

    override suspend fun clear() {
        value = null
    }
}

private fun <T> runSuspend(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) {
            outcome = result
        }
    })
    return requireNotNull(outcome).getOrThrow()
}
