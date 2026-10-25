package zed.rainxch.githubstore.reviews

import org.slf4j.LoggerFactory
import zed.rainxch.githubstore.model.RepoResponse

// Fills RepoResponse.rating on the way out of every route that returns repos,
// from review_repos (the aggregate each review write keeps exact). Attaching
// at response time instead of storing it on `repos` / Meili means catalog,
// feed, Meili search, GitHub passthrough and forge results all carry it, with
// no fetcher or meili_sync.py change. Off (rating stays null) while
// REVIEWS_ENABLED is off. A failed lookup leaves rating null rather than
// failing the catalog response it decorates.
class RepoRatings(
    private val store: ReviewStore,
    private val enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(RepoRatings::class.java)

    suspend fun attach(repos: List<RepoResponse>): List<RepoResponse> {
        if (!enabled || repos.isEmpty()) return repos
        val targets = repos.map { it.ratingTarget() }
        val ratings = try {
            store.ratings(targets)
        } catch (e: Exception) {
            log.warn("Rating lookup failed, serving repos without ratings: {}", e.javaClass.simpleName)
            return repos
        }
        return repos.mapIndexed { i, repo ->
            ratings[targets[i]]?.toRepoRating()?.let { repo.copy(rating = it) } ?: repo
        }
    }

    suspend fun attach(repo: RepoResponse): RepoResponse = attach(listOf(repo)).single()

    // GitHub repos by numeric id (sourceHost is null for them); forge repos
    // by host + owner + name, the same keys the review write path stores.
    private fun RepoResponse.ratingTarget(): RepoTarget =
        if (sourceHost == null) {
            RepoTarget(RepoTarget.GITHUB_HOST, id, owner.login, name)
        } else {
            RepoTarget(sourceHost, null, owner.login, name)
        }
}
