package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import zed.rainxch.githubstore.db.RepoRepository
import zed.rainxch.githubstore.ingest.RepoRefreshCoordinator
import zed.rainxch.githubstore.reviews.RepoRatings
import zed.rainxch.githubstore.util.ApiError
import zed.rainxch.githubstore.util.GitHubIdentifiers

// POST /v1/repo/{owner}/{name}/refresh -- user-triggered refetch of a single
// repo's metadata + latest release. Re-fetches from GitHub, upserts the row
// in Postgres, and pushes the update to Meilisearch. Response is the same
// shape as GET /v1/repo/{owner}/{name} -- callers can render the result
// directly without a follow-up GET.
//
// Cooldown: 30s per (owner, name) -- user spam-clicks return 429 with
// Retry-After. Global hourly budget of 1000 refreshes caps pool consumption
// even when many repos are refreshed at once.
//
// POST chosen over GET so Cloudflare doesn't cache the trigger and so the
// state-changing nature is reflected in the verb. Cache-Control on the
// response is `no-store` -- the response is always live, never cached.
internal fun Route.repoRefreshRoutes(
    coordinator: RepoRefreshCoordinator,
    repoRepository: RepoRepository,
    repoRatings: RepoRatings,
) {
    post("/repo/{owner}/{name}/refresh") {
        val owner = GitHubIdentifiers.validOwner(call.parameters["owner"])
            ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_owner"))
        val name = GitHubIdentifiers.validName(call.parameters["name"])
            ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("invalid_name"))

        val userToken = call.request.headers["X-GitHub-Token"]?.takeIf { it.isNotBlank() }

        when (val outcome = coordinator.refresh(owner, name, userToken)) {
            is RepoRefreshCoordinator.Outcome.Ok -> {
                // Prefer the freshly-persisted DB row when available -- it
                // includes release / installer / download fields that the
                // metadata-only fallback can't produce.
                val response = if (outcome.metadataPersisted) {
                    repoRepository.findByOwnerAndName(owner, name)
                        ?: outcome.repo.toMetadataOnlyResponse()
                } else {
                    outcome.repo.toMetadataOnlyResponse()
                }
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respond(repoRatings.attach(response))
            }
            is RepoRefreshCoordinator.Outcome.Cooldown -> {
                call.response.header(HttpHeaders.RetryAfter, outcome.retryAfterSeconds.toString())
                call.respond(
                    HttpStatusCode.TooManyRequests,
                    ApiError("cooldown", "Try again in ${outcome.retryAfterSeconds}s"),
                )
            }
            is RepoRefreshCoordinator.Outcome.BudgetExhausted -> {
                call.response.header(HttpHeaders.RetryAfter, outcome.retryAfterSeconds.toString())
                call.respond(
                    HttpStatusCode.TooManyRequests,
                    ApiError(
                        "budget_exhausted",
                        "Refresh budget exhausted, try again in ${outcome.retryAfterSeconds}s",
                    ),
                )
            }
            RepoRefreshCoordinator.Outcome.NotFound -> {
                call.respond(HttpStatusCode.NotFound, ApiError("not_found"))
            }
            RepoRefreshCoordinator.Outcome.Archived -> {
                // 410 Gone matches the semantics: "this repo used to exist
                // but is archived/disabled now -- don't poll it again".
                call.respond(HttpStatusCode.Gone, ApiError("archived"))
            }
            RepoRefreshCoordinator.Outcome.UpstreamError -> {
                call.respond(HttpStatusCode.BadGateway, ApiError("github_unreachable"))
            }
        }
    }
}
