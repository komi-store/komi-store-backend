package zed.rainxch.githubstore.reviews

// A reviewed repo on the authed path, after it was resolved upstream: GitHub
// repos by numeric id (so renames keep their reviews), forge repos by host +
// owner + name. owner/name are the canonical spelling, stored as last seen.
data class RepoTarget(
    val host: String,
    val githubId: Long?,
    val owner: String,
    val name: String,
) {
    companion object {
        const val GITHUB_HOST = "github.com"
    }
}

data class ReviewInput(
    val rating: Int,
    val text: String?,
    val version: String?,
    val platform: String?,
    val appLabel: String?,
)

data class ReviewPage(
    val aggregate: RatingAggregate,
    val reviews: List<Review>,
    val hasMore: Boolean,
)

data class MyReviewState(
    // The caller's review, hidden or not.
    val review: Review?,
    // reviewId to the caller's vote, 1 or -1.
    val votes: Map<String, Int>,
)

// Persistence for reviews. Every write keeps review_repos' aggregate in step
// with the visible reviews inside the same transaction.
interface ReviewStore {
    // Public list, hidden reviews excluded. Resolves the repo without calling
    // GitHub (catalog id, else the last-seen name); unknown repo = empty page.
    suspend fun publicPage(
        host: String,
        owner: String,
        name: String,
        sort: ReviewSort,
        rating: Int?,
        offset: Int,
        limit: Int,
    ): ReviewPage

    suspend fun mine(target: RepoTarget, userId: Long): MyReviewState

    suspend fun isBanned(userId: Long): Boolean

    // Creates or replaces the author's review and refreshes their login/avatar
    // snapshot. A hidden review stays hidden.
    suspend fun putReview(target: RepoTarget, author: GitHubUser, input: ReviewInput): Review

    // False when there was nothing to delete.
    suspend fun deleteReview(target: RepoTarget, userId: Long): Boolean
}
