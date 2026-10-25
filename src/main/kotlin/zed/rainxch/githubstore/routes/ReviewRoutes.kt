package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import zed.rainxch.githubstore.match.ForgejoResourceClient
import zed.rainxch.githubstore.requireMaxBody
import zed.rainxch.githubstore.respondNotFoundCode
import zed.rainxch.githubstore.reviews.GitHubReviewClient
import zed.rainxch.githubstore.reviews.GitHubUser
import zed.rainxch.githubstore.reviews.MyReviewResponse
import zed.rainxch.githubstore.reviews.RepoTarget
import zed.rainxch.githubstore.reviews.ReviewEligibility
import zed.rainxch.githubstore.reviews.ReviewIdentity
import zed.rainxch.githubstore.reviews.ReviewInput
import zed.rainxch.githubstore.reviews.ReviewListResponse
import zed.rainxch.githubstore.reviews.ReviewSort
import zed.rainxch.githubstore.reviews.ReviewStore
import zed.rainxch.githubstore.reviews.ReviewWriteRequest
import zed.rainxch.githubstore.reviews.ReviewsConfig
import zed.rainxch.githubstore.reviews.WindowRateLimiter
import zed.rainxch.githubstore.util.ApiError
import zed.rainxch.githubstore.util.GitHubIdentifiers
import java.time.Instant

private const val WRITE_BODY_MAX = 8L * 1024
private const val MAX_PAGE = 500
private const val TEXT_MAX = 2000
private const val FIELD_MAX = 128
private val REVIEW_PLATFORMS = setOf("android", "windows", "macos", "linux")

// Every GitHub token format (classic 40-hex, ghp_/gho_/ghu_/ghs_, github_pat_)
// fits this. Anything else is rejected before it reaches a request header.
private val TOKEN_RE = Regex("^[A-Za-z0-9_]{1,255}$")

// Ratings and reviews (contract: docs/agents/reviews.md in the client repo).
// Reads are anonymous and CDN-shared; /mine and the writes resolve the
// caller's X-GitHub-Token through ReviewIdentity and are never cached.
fun Route.reviewRoutes(
    store: ReviewStore,
    identity: ReviewIdentity,
    forgejoResourceClient: ForgejoResourceClient,
    config: ReviewsConfig,
    // Reviews and replies: 20/hr per GitHub user.
    reviewLimiter: WindowRateLimiter,
    now: () -> Instant = Instant::now,
) {
    // REVIEWS_ENABLED off: register nothing, so every review path is an
    // unmatched-route 404 not_found, which the client treats as unavailable.
    if (!config.enabled) return

    route("/reviews") {
        // Never reads X-GitHub-Token: the response must be identical for
        // every caller so the CDN can share it. Personal state is /mine.
        get("/repo/{owner}/{name}") {
            val path = call.repoPath(forgejoResourceClient) ?: return@get
            val query = call.request.queryParameters
            val sort = ReviewSort.fromWire(query["sort"]) ?: return@get call.reviewError(HttpStatusCode.BadRequest, "invalid_request")
            val rating = query["rating"]?.let { raw ->
                raw.toIntOrNull()?.takeIf { it in 1..5 } ?: return@get call.reviewError(HttpStatusCode.BadRequest, "invalid_request")
            }
            val page = (query["page"]?.toIntOrNull() ?: 1).coerceIn(1, MAX_PAGE)
            val limit = (query["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 50)

            val result = store.publicPage(path.host, path.owner, path.name, sort, rating, (page - 1) * limit, limit)
            call.response.header(HttpHeaders.CacheControl, "public, max-age=60, s-maxage=300")
            call.respond(
                ReviewListResponse(
                    summary = result.aggregate.toSummary(),
                    reviews = result.reviews,
                    page = page,
                    hasMore = result.hasMore,
                ),
            )
        }

        get("/repo/{owner}/{name}/mine") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val path = call.repoPath(forgejoResourceClient) ?: return@get
            val token = call.requireToken() ?: return@get
            val user = call.resolveUser(identity, token) ?: return@get
            val repo = call.resolveRepo(path, token, identity, forgejoResourceClient) ?: return@get

            val mine = store.mine(repo.target, user.id)
            val banned = store.isBanned(user.id)
            val reason = ReviewEligibility.check(banned, user.createdAt, now(), config.minAccountAgeDays, repo.canPush)
            call.respond(
                MyReviewResponse(
                    review = mine.review,
                    votes = mine.votes,
                    canReview = reason == null,
                    cannotReviewReason = reason?.code,
                    // canPush is never true for forge repos.
                    canReply = repo.canPush && !banned,
                    isModerator = user.id in config.moderatorIds,
                ),
            )
        }

        rateLimit(RateLimitName("review-writes")) {
            put("/repo/{owner}/{name}") {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                val path = call.repoPath(forgejoResourceClient) ?: return@put
                val token = call.requireToken() ?: return@put
                if (!call.requireMaxBody(WRITE_BODY_MAX)) return@put
                val body = try {
                    call.receive<ReviewWriteRequest>()
                } catch (_: Exception) {
                    return@put call.reviewError(HttpStatusCode.BadRequest, "invalid_request")
                }
                val input = when (val v = validateReview(body)) {
                    is Validated.Ok -> v.input
                    is Validated.Error -> return@put call.reviewError(v.status, v.code)
                }
                val user = call.resolveUser(identity, token) ?: return@put
                val repo = call.resolveRepo(path, token, identity, forgejoResourceClient) ?: return@put

                val banned = store.isBanned(user.id)
                ReviewEligibility.check(banned, user.createdAt, now(), config.minAccountAgeDays, repo.canPush)?.let {
                    return@put call.reviewError(HttpStatusCode.Forbidden, it.code)
                }
                reviewLimiter.tryAcquire(user.id)?.let { return@put call.rateLimited(it) }

                call.respond(store.putReview(repo.target, user, input))
            }

            // Deleting your own review is always allowed: no ban, account-age
            // or per-user limit check. Idempotent.
            delete("/repo/{owner}/{name}") {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                val path = call.repoPath(forgejoResourceClient) ?: return@delete
                val token = call.requireToken() ?: return@delete
                val user = call.resolveUser(identity, token) ?: return@delete
                val repo = call.resolveRepo(path, token, identity, forgejoResourceClient) ?: return@delete
                store.deleteReview(repo.target, user.id)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private class RepoPath(val host: String, val owner: String, val name: String)

private class ResolvedRepo(val target: RepoTarget, val canPush: Boolean)

private sealed interface Validated {
    class Ok(val input: ReviewInput) : Validated
    class Error(val status: HttpStatusCode, val code: String) : Validated
}

private val Unprocessable = HttpStatusCode.UnprocessableEntity

internal fun String.codePointLength(): Int = codePointCount(0, length)

// Trimmed; blank becomes null.
private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun validateReview(body: ReviewWriteRequest): Validated {
    val rating = body.rating?.takeIf { it in 1..5 } ?: return Validated.Error(Unprocessable, "invalid_rating")
    val text = body.text.clean()
    val version = body.version.clean()
    val platform = body.platform.clean()
    val appLabel = body.appLabel.clean()
    // Postgres TEXT can't hold NUL: reject instead of failing with a 500.
    if (listOfNotNull(text, version, platform, appLabel).any { '\u0000' in it }) {
        return Validated.Error(HttpStatusCode.BadRequest, "invalid_request")
    }
    if (text != null && text.codePointLength() > TEXT_MAX) return Validated.Error(Unprocessable, "text_too_long")
    if (platform != null && platform !in REVIEW_PLATFORMS) return Validated.Error(Unprocessable, "invalid_platform")
    if (listOfNotNull(version, appLabel).any { it.codePointLength() > FIELD_MAX }) {
        return Validated.Error(HttpStatusCode.BadRequest, "invalid_request")
    }
    return Validated.Ok(ReviewInput(rating, text, version, platform, appLabel))
}

internal suspend fun ApplicationCall.reviewError(status: HttpStatusCode, code: String) {
    if (status == HttpStatusCode.NotFound) respondNotFoundCode(code) else respond(status, ApiError(code))
}

internal suspend fun ApplicationCall.rateLimited(retryAfterSeconds: Long) {
    response.header(HttpHeaders.RetryAfter, retryAfterSeconds.toString())
    respond(HttpStatusCode.TooManyRequests, ApiError("rate_limited", "Retry after ${retryAfterSeconds}s"))
}

// Validates owner, name and ?host= (github.com by default, else a trusted
// forge host). Responds 400 and returns null when any is invalid.
private suspend fun ApplicationCall.repoPath(forge: ForgejoResourceClient): RepoPath? {
    val owner = GitHubIdentifiers.validOwner(parameters["owner"])
        ?: return null.also { reviewError(HttpStatusCode.BadRequest, "invalid_owner") }
    val name = GitHubIdentifiers.validName(parameters["name"])
        ?: return null.also { reviewError(HttpStatusCode.BadRequest, "invalid_name") }
    val host = request.queryParameters["host"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        ?: RepoTarget.GITHUB_HOST
    if (host != RepoTarget.GITHUB_HOST && !forge.isTrusted(host)) {
        reviewError(HttpStatusCode.BadRequest, "invalid_host")
        return null
    }
    return RepoPath(host, owner, name)
}

internal suspend fun ApplicationCall.requireToken(): String? {
    val token = request.headers["X-GitHub-Token"]?.trim()?.takeIf { it.isNotEmpty() }
    if (token == null) {
        reviewError(HttpStatusCode.Unauthorized, "auth_required")
        return null
    }
    if (!TOKEN_RE.matches(token)) {
        reviewError(HttpStatusCode.Unauthorized, "auth_invalid")
        return null
    }
    return token
}

internal suspend fun ApplicationCall.resolveUser(identity: ReviewIdentity, token: String): GitHubUser? =
    when (val result = identity.user(token)) {
        is GitHubReviewClient.UserResult.Ok -> result.user
        GitHubReviewClient.UserResult.Invalid -> null.also { reviewError(HttpStatusCode.Unauthorized, "auth_invalid") }
        GitHubReviewClient.UserResult.Unavailable -> null.also { reviewError(HttpStatusCode.BadGateway, "upstream_unavailable") }
    }

// GitHub repos resolve with the caller's token, which yields the numeric id
// and push access in one call. Forge repos only need to exist; a GitHub
// identity can't be matched to a forge account, so canPush is always false.
private suspend fun ApplicationCall.resolveRepo(
    path: RepoPath,
    token: String,
    identity: ReviewIdentity,
    forge: ForgejoResourceClient,
): ResolvedRepo? {
    if (path.host != RepoTarget.GITHUB_HOST) {
        val repo = forge.fetchRepo(path.host, path.owner, path.name)
            ?: return null.also { reviewError(HttpStatusCode.NotFound, "repo_not_found") }
        return ResolvedRepo(RepoTarget(path.host, null, repo.owner.login, repo.name), canPush = false)
    }
    return when (val result = identity.repo(token, path.owner, path.name)) {
        is GitHubReviewClient.RepoResult.Ok -> ResolvedRepo(
            RepoTarget(RepoTarget.GITHUB_HOST, result.repo.id, result.repo.owner, result.repo.name),
            canPush = result.repo.canPush,
        )
        GitHubReviewClient.RepoResult.NotFound -> null.also { reviewError(HttpStatusCode.NotFound, "repo_not_found") }
        GitHubReviewClient.RepoResult.Invalid -> null.also { reviewError(HttpStatusCode.Unauthorized, "auth_invalid") }
        GitHubReviewClient.RepoResult.Unavailable -> null.also { reviewError(HttpStatusCode.BadGateway, "upstream_unavailable") }
    }
}
