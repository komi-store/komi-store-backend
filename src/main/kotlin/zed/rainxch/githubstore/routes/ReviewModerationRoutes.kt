package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import zed.rainxch.githubstore.reviews.ModerationQueueResponse
import zed.rainxch.githubstore.reviews.ModerationQueueStatus
import zed.rainxch.githubstore.reviews.ModerationReasonRequest
import zed.rainxch.githubstore.reviews.ReviewIdentity
import zed.rainxch.githubstore.reviews.ReviewModerationStore
import zed.rainxch.githubstore.reviews.ReviewsConfig
import zed.rainxch.githubstore.util.ApiError
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private const val QUEUE_PAGE_SIZE = 20
private const val QUEUE_MAX_PAGE = 500
private const val REASON_BODY_MAX = 4L * 1024
private const val REASON_MAX = 500
private val MODERATION_REVIEW_ID_RE = Regex("^rv_[A-Za-z0-9_-]{22}$")

// Review moderation. A moderator is a GitHub user listed in
// REVIEW_MODERATOR_IDS, signed in with their own X-GitHub-Token, or the
// operator with X-Admin-Token. Reports never hide anything on their own;
// these routes are the only way a review leaves the public list besides its
// author deleting it. Every action is written to review_moderation_log.
fun Route.reviewModerationRoutes(
    store: ReviewModerationStore,
    identity: ReviewIdentity,
    config: ReviewsConfig,
) {
    if (!config.enabled) return

    route("/reviews/moderation") {
        get("/queue") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.requireModerator(identity, config) ?: return@get
            val status = ModerationQueueStatus.fromWire(call.request.queryParameters["status"])
                ?: return@get call.reviewError(HttpStatusCode.BadRequest, "invalid_request")
            val page = (call.request.queryParameters["page"]?.toIntOrNull() ?: 1).coerceIn(1, QUEUE_MAX_PAGE)
            val result = store.moderationQueue(status, (page - 1) * QUEUE_PAGE_SIZE, QUEUE_PAGE_SIZE)
            call.respond(ModerationQueueResponse(result.items, page, result.hasMore))
        }

        route("/item/{reviewId}") {
            post("/hide") {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                val moderator = call.requireModerator(identity, config) ?: return@post
                val reviewId = call.moderationReviewId() ?: return@post
                val reason = call.receiveReason() ?: return@post
                call.respondDone(store.hide(reviewId, moderator.githubId, reason.text))
            }

            post("/unhide") {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                val moderator = call.requireModerator(identity, config) ?: return@post
                val reviewId = call.moderationReviewId() ?: return@post
                call.respondDone(store.unhide(reviewId, moderator.githubId))
            }

            post("/dismiss") {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                val moderator = call.requireModerator(identity, config) ?: return@post
                val reviewId = call.moderationReviewId() ?: return@post
                call.respondDone(store.dismiss(reviewId, moderator.githubId))
            }
        }

        delete("/item/{reviewId}") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val moderator = call.requireModerator(identity, config) ?: return@delete
            val reviewId = call.moderationReviewId() ?: return@delete
            call.respondDone(store.moderatorDelete(reviewId, moderator.githubId))
        }

        route("/users/{githubId}/ban") {
            post {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                val moderator = call.requireModerator(identity, config) ?: return@post
                val userId = call.githubIdParam() ?: return@post
                val reason = call.receiveReason() ?: return@post
                store.ban(userId, moderator.githubId, reason.text)
                call.respond(HttpStatusCode.NoContent)
            }

            delete {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                val moderator = call.requireModerator(identity, config) ?: return@delete
                val userId = call.githubIdParam() ?: return@delete
                store.unban(userId, moderator.githubId)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

// githubId null = the operator's X-Admin-Token.
private class Moderator(val githubId: Long?)

private class Reason(val text: String?)

private suspend fun ApplicationCall.requireModerator(identity: ReviewIdentity, config: ReviewsConfig): Moderator? {
    val adminHeader = request.headers["X-Admin-Token"]
    // Constant-time compare, same as InternalRoutes.
    if (adminHeader != null && config.adminToken != null && MessageDigest.isEqual(
            adminHeader.toByteArray(StandardCharsets.UTF_8),
            config.adminToken.toByteArray(StandardCharsets.UTF_8),
        )
    ) {
        return Moderator(githubId = null)
    }
    val token = requireToken() ?: return null
    val user = resolveUser(identity, token) ?: return null
    if (user.id !in config.moderatorIds) {
        reviewError(HttpStatusCode.Forbidden, "not_moderator")
        return null
    }
    return Moderator(user.id)
}

private suspend fun ApplicationCall.moderationReviewId(): String? {
    val id = parameters["reviewId"]?.takeIf { MODERATION_REVIEW_ID_RE.matches(it) }
    if (id == null) reviewError(HttpStatusCode.NotFound, "review_not_found")
    return id
}

private suspend fun ApplicationCall.githubIdParam(): Long? {
    val id = parameters["githubId"]?.toLongOrNull()?.takeIf { it > 0 }
    if (id == null) respond(HttpStatusCode.BadRequest, ApiError("invalid_request"))
    return id
}

// Optional `{ reason }` body. Null means an error was already sent.
private suspend fun ApplicationCall.receiveReason(): Reason? {
    val length = request.contentLength()
    if (length == null || length == 0L) return Reason(null)
    if (length > REASON_BODY_MAX) {
        respond(HttpStatusCode.PayloadTooLarge, ApiError("payload_too_large"))
        return null
    }
    val body = try {
        receive<ModerationReasonRequest>()
    } catch (_: Exception) {
        reviewError(HttpStatusCode.BadRequest, "invalid_request")
        return null
    }
    val reason = body.reason.clean()
    if (reason != null && '\u0000' in reason) {
        reviewError(HttpStatusCode.BadRequest, "invalid_request")
        return null
    }
    if (reason != null && reason.codePointLength() > REASON_MAX) {
        reviewError(Unprocessable, "note_too_long")
        return null
    }
    return Reason(reason)
}

private suspend fun ApplicationCall.respondDone(found: Boolean) {
    if (found) respond(HttpStatusCode.NoContent) else reviewError(HttpStatusCode.NotFound, "review_not_found")
}
