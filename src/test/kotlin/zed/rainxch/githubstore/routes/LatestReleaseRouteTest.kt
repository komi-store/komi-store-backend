package zed.rainxch.githubstore.routes

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import zed.rainxch.githubstore.db.ResourceCacheRepository
import zed.rainxch.githubstore.db.ResourceCacheRepository.CacheEntry
import zed.rainxch.githubstore.ingest.GitHubResourceClient
import zed.rainxch.githubstore.match.ForgejoResourceClient
import zed.rainxch.githubstore.respondNotFound
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Route-level contract for GET /v1/releases/{owner}/{name}/latest:
//   - one GitHub release object (the same wire shape as one element of the
//     /v1/releases list) for the Detail first paint;
//   - 5-minute server cache + 60s/300s edge cache;
//   - a GitHub rate limit serves the last good copy with X-Cache-State;
//   - a GitHub 404 ("no stable release") is forwarded as our 404 and cached
//     negatively for the same short window.
class LatestReleaseRouteTest {

    // ResourceCacheRepository is open and backs the production
    // resource_cache table; an in-memory map keeps the route test free of
    // Postgres/Docker while exercising the exact GitHubResourceClient logic.
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

    private val releaseJson = """
        {
          "id": 991,
          "tag_name": "v4.4.25",
          "name": "v4.4.25",
          "draft": false,
          "prerelease": false,
          "published_at": "2026-10-20T09:30:00Z",
          "html_url": "https://github.com/ente/ente/releases/tag/v4.4.25",
          "assets": [
            {
              "name": "ente-4.4.25.apk",
              "size": 48123456,
              "download_count": 1200,
              "browser_download_url": "https://github.com/ente/ente/releases/download/v4.4.25/ente-4.4.25.apk"
            }
          ]
        }
    """.trimIndent()

    private val cacheKey = "releases:latest:ente/ente|anon"

    private fun ApplicationTestBuilder.setupApp(resourceClient: GitHubResourceClient) {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
            // Same 404 path as production: Ktor StatusPages overrides any
            // route-level 404 body with the shared not_found envelope (see
            // StatusPagesOverrideTest), so the 404 test asserts the real wire
            // shape rather than the route-local one.
            install(StatusPages) {
                status(HttpStatusCode.NotFound) { call, _ -> respondNotFound(call) }
            }
            routing {
                route("/v1") {
                    releasesRoutes(resourceClient, ForgejoResourceClient(cache = InMemoryCache()))
                }
            }
        }
    }

    @Test
    fun `returns the latest stable release with assets from GitHub`() = testApplication {
        val cache = InMemoryCache()
        val upstreamCalls = AtomicInteger()
        val upstreamRequests = mutableListOf<HttpRequestData>()
        val resourceClient = GitHubResourceClient(
            cacheRepository = cache,
            // Proves the route shares the /releases token policy: an
            // anonymous caller goes out on the rotation pool.
            fallbackTokenProvider = { "pool-token" },
            engine = MockEngine { request ->
                upstreamCalls.incrementAndGet()
                upstreamRequests += request
                respond(releaseJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json")))
            },
        )
        setupApp(resourceClient)

        val response = client.get("/v1/releases/ente/ente/latest")

        assertEquals(HttpStatusCode.OK, response.status)
        // Byte-for-byte passthrough: the body is the single release DTO the
        // client already parses from the /releases list, assets included.
        val body = response.bodyAsText()
        assertEquals(releaseJson, body)
        assertTrue(body.contains("\"assets\""))
        assertTrue(body.contains("\"draft\": false"))
        assertTrue(body.contains("\"prerelease\": false"))

        val cacheControl = response.headers[HttpHeaders.CacheControl]
        assertNotNull(cacheControl)
        assertTrue(cacheControl.contains("public"), "latest must be shared-cacheable: $cacheControl")
        assertTrue(cacheControl.contains("max-age=60"), "browser TTL missing: $cacheControl")
        assertTrue(cacheControl.contains("s-maxage=300"), "edge TTL missing: $cacheControl")

        assertEquals(1, upstreamCalls.get())
        val request = upstreamRequests.single()
        assertEquals("/repos/ente/ente/releases/latest", request.url.encodedPath)
        assertEquals("token pool-token", request.headers[HttpHeaders.Authorization])
        assertEquals("application/vnd.github+json", request.headers[HttpHeaders.Accept])
        assertTrue(cache.entries.containsKey(cacheKey), "response must be stored under the latest-release key")
    }

    @Test
    fun `second request is served from the server cache for the full five minutes`() = testApplication {
        val cache = InMemoryCache()
        val upstreamCalls = AtomicInteger()
        val resourceClient = GitHubResourceClient(
            cacheRepository = cache,
            engine = MockEngine {
                upstreamCalls.incrementAndGet()
                respond(releaseJson, HttpStatusCode.OK)
            },
        )
        setupApp(resourceClient)

        val first = client.get("/v1/releases/ente/ente/latest")
        val second = client.get("/v1/releases/ente/ente/latest")

        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(releaseJson, first.bodyAsText())
        assertEquals(releaseJson, second.bodyAsText())
        assertEquals(1, upstreamCalls.get(), "the cached latest release must not fan out to GitHub")

        val entry = cache.entries.getValue(cacheKey)
        assertEquals(200, entry.status)
        assertEquals(300, Duration.between(entry.fetchedAt, entry.expiresAt).seconds)
    }

    @Test
    fun `a rate-limited GitHub serves the last good latest release`() = testApplication {
        val cache = InMemoryCache()
        val staleThen = OffsetDateTime.now().minusHours(2)
        cache.entries[cacheKey] = CacheEntry(
            key = cacheKey,
            body = releaseJson,
            etag = null,
            status = 200,
            contentType = "application/json",
            fetchedAt = staleThen,
            expiresAt = staleThen.plusMinutes(5),
        )
        val resourceClient = GitHubResourceClient(
            cacheRepository = cache,
            engine = MockEngine {
                respond(
                    """{"message":"API rate limit exceeded"}""",
                    HttpStatusCode.Forbidden,
                    headersOf("x-ratelimit-remaining" to listOf("0")),
                )
            },
        )
        setupApp(resourceClient)

        val response = client.get("/v1/releases/ente/ente/latest")

        // Same contract as the resource proxy: a rate limit must never
        // replace the cached release with a 403, and intermediaries must
        // not re-cache the stale answer.
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(releaseJson, response.bodyAsText())
        assertEquals("stale-fallback", response.headers["X-Cache-State"])
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals(200, cache.entries.getValue(cacheKey).status)
    }

    @Test
    fun `no stable release is a 404 and the negative answer is cached briefly`() = testApplication {
        val cache = InMemoryCache()
        val upstreamCalls = AtomicInteger()
        val resourceClient = GitHubResourceClient(
            cacheRepository = cache,
            engine = MockEngine {
                upstreamCalls.incrementAndGet()
                respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound)
            },
        )
        setupApp(resourceClient)

        val first = client.get("/v1/releases/ente/ente/latest")

        assertEquals(HttpStatusCode.NotFound, first.status)
        assertTrue(
            first.bodyAsText().contains("not_found"),
            "expected the shared 404 envelope, got: ${first.bodyAsText()}",
        )
        // respondNotFound's short negative edge cache — same 5-minute
        // freshness the server-side negative entry gets.
        assertEquals("public, max-age=300, s-maxage=300", first.headers[HttpHeaders.CacheControl])

        val second = client.get("/v1/releases/ente/ente/latest")
        assertEquals(HttpStatusCode.NotFound, second.status)
        assertEquals(1, upstreamCalls.get(), "a no-stable-release 404 must be cached server-side")
        val cached = cache.entries.getValue(cacheKey)
        assertEquals(404, cached.status)
        assertEquals(300, Duration.between(cached.fetchedAt, cached.expiresAt).seconds)
    }

    @Test
    fun `forge host is rejected instead of silently answering with GitHub data`() = testApplication {
        val cache = InMemoryCache()
        val upstreamCalls = AtomicInteger()
        val resourceClient = GitHubResourceClient(
            cacheRepository = cache,
            engine = MockEngine {
                upstreamCalls.incrementAndGet()
                respond(releaseJson, HttpStatusCode.OK)
            },
        )
        setupApp(resourceClient)

        val response = client.get("/v1/releases/ente/ente/latest?host=codeberg.org")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("unsupported_host"))
        assertEquals(0, upstreamCalls.get(), "a forge latest must not fall through to the GitHub upstream")
    }
}
