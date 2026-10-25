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
import zed.rainxch.githubstore.reviews.ItemResult
import zed.rainxch.githubstore.reviews.MyReviewResponse
import zed.rainxch.githubstore.reviews.REPORT_REASONS
import zed.rainxch.githubstore.reviews.ReplyRequest
import zed.rainxch.githubstore.reviews.RepoTarget
import zed.rainxch.githubstore.reviews.ReportRequest
import zed.rainxch.githubstore.reviews.ReviewEligibility
import zed.rainxch.githubstore.reviews.ReviewIdentity
import zed.rainxch.githubstore.reviews.ReviewInput
import zed.rainxch.githubstore.reviews.ReviewListResponse
import zed.rainxch.githubstore.reviews.ReviewRateLimits
import zed.rainxch.githubstore.reviews.ReviewSort
import zed.rainxch.githubstore.reviews.ReviewStore
import zed.rainxch.githubstore.reviews.ReviewWriteRequest
import zed.rainxch.githubstore.reviews.ReviewsConfig
import zed.rainxch.githubstore.reviews.VoteRequest
import zed.rainxch.githubstore.util.ApiError
import zed.rainxch.githubstore.util.GitHubIdentifiers
import java.time.Instant

private const val WRITE_BODY_MAX = 8L * 1024
private const val MAX_PAGE = 500
private const val TEXT_MAX = 2000
private const val FIELD_MAX = 128
private const val NOTE_MAX = 500
private val REVIEW_ID_RE = Regex("^rv_[A-Za-z0-9_-]{22}$")
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
    limits: ReviewRateLimits,
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
                limits.reviews.tryAcquire(user.id)?.let { return@put call.rateLimited(it) }

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

        route("/item/{reviewId}") {
            // Up/down votes stay out of the per-IP write bucket (one tap
            // each, and mobile CGNAT shares IPs); 120/hr per user is the guard.
            put("/vote") {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                val reviewId = call.reviewIdParam() ?: return@put
                val token = call.requireToken() ?: return@put
                if (!call.requireMaxBody(WRITE_BODY_MAX)) return@put
                val value = try {
                    call.receive<VoteRequest>().value
                } catch (_: Exception) {
                    null
                }?.takeIf { it == 1 || it == -1 }
                    ?: return@put call.reviewError(HttpStatusCode.BadRequest, "invalid_request")
                val user = call.resolveUser(identity, token) ?: return@put
                if (!call.checkWriter(store, user, config, now())) return@put
                limits.votes.tryAcquire(user.id)?.let { return@put call.rateLimited(it) }
                when (store.vote(reviewId, user.id, value)) {
                    ItemResult.OK -> call.respond(HttpStatusCode.NoContent)
                    ItemResult.NOT_FOUND -> call.reviewError(HttpStatusCode.NotFound, "review_not_found")
                    ItemResult.OWN_REVIEW -> call.reviewError(HttpStatusCode.Forbidden, "own_review")
                }
            }

            // Taking a vote back is allowed even for banned users.
            delete("/vote") {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                val reviewId = call.reviewIdParam() ?: return@delete
                val token = call.requireToken() ?: return@delete
                val user = call.resolveUser(identity, token) ?: return@delete
                limits.votes.tryAcquire(user.id)?.let { return@delete call.rateLimited(it) }
                when (store.unvote(reviewId, user.id)) {
                    ItemResult.NOT_FOUND -> call.reviewError(HttpStatusCode.NotFound, "review_not_found")
                    else -> call.respond(HttpStatusCode.NoContent)
                }
            }

            rateLimit(RateLimitName("review-writes")) {
                // A report never hides anything; it queues the review for a moderator.
                post("/report") {
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    val reviewId = call.reviewIdParam() ?: return@post
                    val token = call.requireToken() ?: return@post
                    if (!call.requireMaxBody(WRITE_BODY_MAX)) return@post
                    val body = try {
                        call.receive<ReportRequest>()
                    } catch (_: Exception) {
                        return@post call.reviewError(HttpStatusCode.BadRequest, "invalid_request")
                    }
                    val reason = body.reason?.takeIf { it in REPORT_REASONS }
                        ?: return@post call.reviewError(Unprocessable, "invalid_reason")
                    val note = body.note.clean()
                    if (note != null && '\u0000' in note) return@post call.reviewError(HttpStatusCode.BadRequest, "invalid_request")
                    if (note != null && note.codePointLength() > NOTE_MAX) return@post call.reviewError(Unprocessable, "note_too_long")

                    val user = call.resolveUser(identity, token) ?: return@post
                    if (!call.checkWriter(store, user, config, now())) return@post
                    limits.reports.tryAcquire(user.id)?.let { return@post call.rateLimited(it) }
                    when (store.report(reviewId, user.id, reason, note)) {
                        ItemResult.OK -> call.respond(HttpStatusCode.Accepted)
                        ItemResult.NOT_FOUND -> call.reviewError(HttpStatusCode.NotFound, "review_not_found")
                        ItemResult.OWN_REVIEW -> call.reviewError(HttpStatusCode.Forbidden, "own_review")
                    }
                }

                // One developer reply per review, from anyone with push access
                // to the GitHub repo. Replaces the existing reply.
                put("/reply") {
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    val reviewId = call.reviewIdParam() ?: return@put
                    val token = call.requireToken() ?: return@put
                    if (!call.requireMaxBody(WRITE_BODY_MAX)) return@put
                    val body = try {
                        call.receive<ReplyRequest>()
                    } catch (_: Exception) {
                        return@put call.reviewError(HttpStatusCode.BadRequest, "invalid_request")
                    }
                    val text = body.text.clean()
                    if (text == null || '\u0000' in text) return@put call.reviewError(HttpStatusCode.BadRequest, "invalid_request")
                    if (text.codePointLength() > TEXT_MAX) return@put call.reviewError(Unprocessable, "text_too_long")

                    val user = call.resolveUser(identity, token) ?: return@put
                    if (store.isBanned(user.id)) return@put call.reviewError(HttpStatusCode.Forbidden, "banned")
                    val target = store.reviewRepo(reviewId)
                        ?: return@put call.reviewError(HttpStatusCode.NotFound, "review_not_found")
                    if (!call.requireMaintainer(identity, token, target)) return@put
                    limits.reviews.tryAcquire(user.id)?.let { return@put call.rateLimited(it) }
                    val reply = store.putReply(reviewId, user, text)
                        ?: return@put call.reviewError(HttpStatusCode.NotFound, "review_not_found")
                    call.respond(reply)
                }

                delete("/reply") {
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    val reviewId = call.reviewIdParam() ?: return@delete
                    val token = call.requireToken() ?: return@delete
                    call.resolveUser(identity, token) ?: return@delete
                    val target = store.reviewRepo(reviewId)
                        ?: return@delete call.reviewError(HttpStatusCode.NotFound, "review_not_found")
                    if (!call.requireMaintainer(identity, token, target)) return@delete
                    if (!store.deleteReply(reviewId)) return@delete call.reviewError(HttpStatusCode.NotFound, "review_not_found")
                    call.respond(HttpStatusCode.NoContent)
                }
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

internal val Unprocessable = HttpStatusCode.UnprocessableEntity

internal fun String.codePointLength(): Int = codePointCount(0, length)

// Trimmed; blank becomes null.
internal fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

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

// Opaque review id from the path; anything malformed is simply not found.
private suspend fun ApplicationCall.reviewIdParam(): String? {
    val id = parameters["reviewId"]?.takeIf { REVIEW_ID_RE.matches(it) }
    if (id == null) reviewError(HttpStatusCode.NotFound, "review_not_found")
    return id
}

// Votes and reports: banned users and too-new accounts are refused.
private suspend fun ApplicationCall.checkWriter(store: ReviewStore, user: GitHubUser, config: ReviewsConfig, now: Instant): Boolean {
    val reason = ReviewEligibility.check(store.isBanned(user.id), user.createdAt, now, config.minAccountAgeDays, hasPushAccess = false)
        ?: return true
    reviewError(HttpStatusCode.Forbidden, reason.code)
    return false
}

// Push access to the review's GitHub repo, looked up by numeric id so a
// rename doesn't matter. Forge repos have no maintainers we can verify.
private suspend fun ApplicationCall.requireMaintainer(identity: ReviewIdentity, token: String, target: RepoTarget): Boolean {
    val githubId = target.githubId
    if (githubId == null) {
        reviewError(HttpStatusCode.Forbidden, "not_maintainer")
        return false
    }
    return when (val result = identity.repoById(token, githubId)) {
        is GitHubReviewClient.RepoResult.Ok -> result.repo.canPush.also {
            if (!it) reviewError(HttpStatusCode.Forbidden, "not_maintainer")
        }
        GitHubReviewClient.RepoResult.NotFound -> false.also { reviewError(HttpStatusCode.Forbidden, "not_maintainer") }
        GitHubReviewClient.RepoResult.Invalid -> false.also { reviewError(HttpStatusCode.Unauthorized, "auth_invalid") }
        GitHubReviewClient.RepoResult.Unavailable -> false.also { reviewError(HttpStatusCode.BadGateway, "upstream_unavailable") }
    }
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
