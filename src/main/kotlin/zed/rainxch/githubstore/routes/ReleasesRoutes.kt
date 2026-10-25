package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import zed.rainxch.githubstore.ingest.GitHubResourceClient
import zed.rainxch.githubstore.match.ForgejoResourceClient
import zed.rainxch.githubstore.util.GitHubIdentifiers

fun Route.releasesRoutes(
    resourceClient: GitHubResourceClient,
    forgejoResourceClient: ForgejoResourceClient,
) {
    get("/releases/{owner}/{name}") {
        val owner = GitHubIdentifiers.validOwner(call.parameters["owner"])
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_owner"))
        val name = GitHubIdentifiers.validName(call.parameters["name"])
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_name"))

        // Allow client to ask for a specific page — cache key includes it so
        // page 1 and page 2 don't collide in the cache.
        val page = (call.request.queryParameters["page"]?.toIntOrNull() ?: 1).coerceIn(1, 50)
        val perPage = (call.request.queryParameters["per_page"]?.toIntOrNull() ?: 100).coerceIn(1, 100)

        // Forge-keyed releases (3.4). Same allowlist + host-keyed cache as
        // /v1/repo. Returns CRLF-normalised upstream JSON; field-shape
        // translation stays on the client until the unified release DTO ships.
        val forgeHost = call.request.queryParameters["host"]?.trim()?.lowercase()
        if (!forgeHost.isNullOrBlank()) {
            if (!forgejoResourceClient.isTrusted(forgeHost)) {
                return@get call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "invalid_host", "message" to "host not in trusted forge allowlist"),
                )
            }
            val raw = forgejoResourceClient.fetchReleasesRaw(forgeHost, owner, name, page, perPage)
            if (raw == null) {
                return@get call.respond(HttpStatusCode.BadGateway, mapOf("error" to "forge_unreachable"))
            }
            call.response.header(HttpHeaders.CacheControl, "public, max-age=30, s-maxage=60")
            return@get call.respondText(raw, ContentType.Application.Json, HttpStatusCode.OK)
        }

        val userToken = call.request.headers["X-GitHub-Token"]?.takeIf { it.isNotBlank() }

        val cacheKey = "releases:$owner/$name?page=$page&per_page=$perPage"
        val upstreamUrl =
            "https://api.github.com/repos/$owner/$name/releases?per_page=$perPage&page=$page"

        val ttlSeconds = 3600L // 1 hour — releases publish frequently on active repos

        val result = resourceClient.fetchCached(
            cacheKey = cacheKey,
            upstreamUrl = upstreamUrl,
            userToken = userToken,
            ttlSeconds = ttlSeconds,
        )

        when (result) {
            is GitHubResourceClient.Result.Hit -> {
                // Edge-cache 60s; client already TTLs 6h locally so repeat
                // asks within an hour just hit Gcore.
                call.response.header(HttpHeaders.CacheControl, "public, max-age=30, s-maxage=60")
                call.respondText(result.body, ContentType.parse(result.contentType), HttpStatusCode.OK)
            }
            is GitHubResourceClient.Result.StaleFallback -> {
                // Serving stale because upstream is unreachable right now.
                // Tell intermediaries not to re-cache the stale answer.
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.response.header("X-Cache-State", "stale-fallback")
                call.respondText(result.body, ContentType.parse(result.contentType), HttpStatusCode.OK)
            }
            is GitHubResourceClient.Result.NegativeHit -> {
                call.response.header(HttpHeaders.CacheControl, "public, max-age=60, s-maxage=300")
                call.respond(HttpStatusCode.fromValue(result.status), mapOf("error" to "upstream_${result.status}"))
            }
            is GitHubResourceClient.Result.UpstreamError -> {
                call.respond(HttpStatusCode.BadGateway, mapOf("error" to "github_unreachable"))
            }
        }
    }

    // Single latest release for the app's Detail first paint. GitHub's
    // /releases/latest already answers with the newest non-draft,
    // non-prerelease release and includes the full `assets` array, so the
    // body is proxied verbatim — one element of the same release DTO that
    // /releases serves as a list. A 404 from GitHub means the repo has no
    // stable release (or does not exist); the route forwards that status so
    // the client can fall back to its list-based selection.
    //
    // GitHub-only: the list route supports ?host=<forge>, but a forge
    // latest read would need a list fetch + local filter. Reject the
    // parameter instead of silently answering with GitHub data.
    get("/releases/{owner}/{name}/latest") {
        val owner = GitHubIdentifiers.validOwner(call.parameters["owner"])
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_owner"))
        val name = GitHubIdentifiers.validName(call.parameters["name"])
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_name"))

        val forgeHost = call.request.queryParameters["host"]?.trim()?.lowercase()
        if (!forgeHost.isNullOrBlank()) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                mapOf(
                    "error" to "unsupported_host",
                    "message" to "latest release is only proxied for github.com",
                ),
            )
        }

        val userToken = call.request.headers["X-GitHub-Token"]?.takeIf { it.isNotBlank() }

        val cacheKey = "releases:latest:$owner/$name"
        val upstreamUrl = "https://api.github.com/repos/$owner/$name/releases/latest"

        val result = resourceClient.fetchCached(
            cacheKey = cacheKey,
            upstreamUrl = upstreamUrl,
            userToken = userToken,
            ttlSeconds = LATEST_RELEASE_TTL_SECONDS,
            // Same short window for the no-stable-release 404: a repo that
            // publishes its first stable release should show it within
            // minutes, not after the proxy's 15-minute negative default.
            negativeTtlSeconds = LATEST_RELEASE_TTL_SECONDS,
        )

        when (result) {
            is GitHubResourceClient.Result.Hit -> {
                // Short edge TTL — the first paint wants a newly published
                // release within minutes, unlike the release list (1h server
                // / 60s edge) whose body carries every release.
                call.response.header(HttpHeaders.CacheControl, "public, max-age=60, s-maxage=300")
                call.respondText(result.body, ContentType.parse(result.contentType), HttpStatusCode.OK)
            }
            is GitHubResourceClient.Result.StaleFallback -> {
                // A GitHub rate limit serves the last good copy; tell
                // intermediaries not to re-cache the stale answer.
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.response.header("X-Cache-State", "stale-fallback")
                call.respondText(result.body, ContentType.parse(result.contentType), HttpStatusCode.OK)
            }
            is GitHubResourceClient.Result.NegativeHit -> {
                // 404 flows through the global StatusPages handler, which
                // emits the not_found envelope and its own 5-minute edge
                // cache; setting our header here too would append a second,
                // conflicting Cache-Control value. Other negative statuses
                // (451, 403 …) need the route-local header.
                if (result.status != 404) {
                    call.response.header(HttpHeaders.CacheControl, "public, max-age=60, s-maxage=300")
                }
                call.respond(HttpStatusCode.fromValue(result.status), mapOf("error" to "upstream_${result.status}"))
            }
            is GitHubResourceClient.Result.UpstreamError -> {
                call.respond(HttpStatusCode.BadGateway, mapOf("error" to "github_unreachable"))
            }
        }
    }
}

// The Detail page asks for a single latest release on every open; a newly
// published stable release should replace an older "latest" within minutes
// on both the server cache and the edge. The release LIST keeps its 1h TTL
// because it carries many bodies and is not on the first-paint path.
private const val LATEST_RELEASE_TTL_SECONDS = 300L
