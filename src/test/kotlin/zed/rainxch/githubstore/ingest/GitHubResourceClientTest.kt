package zed.rainxch.githubstore.ingest

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import zed.rainxch.githubstore.db.ResourceCacheRepository
import zed.rainxch.githubstore.db.ResourceCacheRepository.CacheEntry
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GitHubResourceClientTest {

    private class InMemoryCache : ResourceCacheRepository() {
        val entries = mutableMapOf<String, CacheEntry>()

        override suspend fun get(key: String): CacheEntry? = entries[key]

        override suspend fun put(
            key: String,
            body: String,
            etag: String?,
            status: Int,
            contentType: String,
            ttlSeconds: Long,
        ) {
            val now = OffsetDateTime.now()
            entries[key] = CacheEntry(key, body, etag, status, contentType, now, now.plusSeconds(ttlSeconds))
        }
    }

    private val key = "releases:ente/ente?page=1&per_page=50"
    private val url = "https://api.github.com/repos/ente/ente/releases?per_page=50&page=1"

    private fun client(cache: InMemoryCache, status: HttpStatusCode, vararg headers: Pair<String, String>) =
        GitHubResourceClient(
            cacheRepository = cache,
            isQuietWindow = { true },
            engine = MockEngine {
                respond("""{"message":"API rate limit exceeded"}""", status, headersOf(*headers.map { it.first to listOf(it.second) }.toTypedArray()))
            },
        )

    private fun staleGoodEntry(): CacheEntry {
        val then = OffsetDateTime.now().minusHours(2)
        return CacheEntry("$key|anon", """[{"tag_name":"auth-v4.4.25"}]""", null, 200, "application/json", then, then.plusHours(1))
    }

    @Test
    fun `rate limit serves the last good copy and keeps it cached`() = runBlocking {
        val cache = InMemoryCache().apply { entries["$key|anon"] = staleGoodEntry() }
        val result = client(cache, HttpStatusCode.Forbidden, "x-ratelimit-remaining" to "0")
            .fetchCached(cacheKey = key, upstreamUrl = url, userToken = null, ttlSeconds = 3600)

        val stale = assertIs<GitHubResourceClient.Result.StaleFallback>(result)
        assertEquals("""[{"tag_name":"auth-v4.4.25"}]""", stale.body)
        assertEquals(200, cache.entries.getValue("$key|anon").status)
    }

    @Test
    fun `rate limit without a cached copy is an upstream error, not a cached 403`() = runBlocking {
        val cache = InMemoryCache()
        val result = client(cache, HttpStatusCode.TooManyRequests, "retry-after" to "60")
            .fetchCached(cacheKey = key, upstreamUrl = url, userToken = null, ttlSeconds = 3600)

        assertIs<GitHubResourceClient.Result.UpstreamError>(result)
        assertEquals(emptyMap(), cache.entries)
    }

    @Test
    fun `a plain 403 is still cached as a negative answer`() = runBlocking {
        val cache = InMemoryCache()
        val result = client(cache, HttpStatusCode.Forbidden)
            .fetchCached(cacheKey = key, upstreamUrl = url, userToken = null, ttlSeconds = 3600)

        assertEquals(403, assertIs<GitHubResourceClient.Result.NegativeHit>(result).status)
        assertEquals(403, cache.entries.getValue("$key|anon").status)
    }
}
