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

// Outcome of a vote or report on one review.
enum class ItemResult { OK, NOT_FOUND, OWN_REVIEW }

data class ModerationPage(
    val items: List<ModerationQueueItem>,
    val hasMore: Boolean,
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

    // Visible-review aggregate per repo for RepoResponse.rating: GitHub
    // targets by githubId, forge targets by host + owner + name. Repos with
    // no visible reviews are absent from the map.
    suspend fun ratings(targets: List<RepoTarget>): Map<RepoTarget, RatingAggregate>

    // One aggregate per (owner, name), resolved like publicPage; null when the
    // repo has no review row.
    suspend fun summaries(host: String, repos: List<Pair<String, String>>): List<RatingAggregate?>

    suspend fun mine(target: RepoTarget, userId: Long): MyReviewState

    suspend fun isBanned(userId: Long): Boolean

    // Creates or replaces the author's review and refreshes their login/avatar
    // snapshot. A hidden review stays hidden.
    suspend fun putReview(target: RepoTarget, author: GitHubUser, input: ReviewInput): Review

    // False when there was nothing to delete.
    suspend fun deleteReview(target: RepoTarget, userId: Long): Boolean

    // Up (1) or down (-1) votes, one per user per review: voting again
    // replaces it, unvote is idempotent, and score moves with each change.
    // NOT_FOUND for a missing or hidden review.
    suspend fun vote(reviewId: String, voterId: Long, value: Int): ItemResult
    suspend fun unvote(reviewId: String, voterId: Long): ItemResult

    // One report per user per review; a repeat is a no-op OK.
    suspend fun report(reviewId: String, reporterId: Long, reason: String, note: String?): ItemResult

    // The repo a visible review belongs to; null when missing or hidden.
    suspend fun reviewRepo(reviewId: String): RepoTarget?

    // Creates or replaces the review's one reply (author becomes the caller).
    // Null when the review is missing or hidden.
    suspend fun putReply(reviewId: String, author: GitHubUser, text: String): ReviewReply?

    // False when the review is missing or hidden.
    suspend fun deleteReply(reviewId: String): Boolean
}

// Moderator actions. moderatorId null means the operator's X-Admin-Token.
// Each one writes review_moderation_log in the same transaction.
interface ReviewModerationStore {
    suspend fun moderationQueue(status: ModerationQueueStatus, offset: Int, limit: Int): ModerationPage

    // The Boolean ones return false when the review doesn't exist.
    suspend fun hide(reviewId: String, moderatorId: Long?, reason: String?): Boolean
    suspend fun unhide(reviewId: String, moderatorId: Long?): Boolean
    suspend fun dismiss(reviewId: String, moderatorId: Long?): Boolean
    suspend fun moderatorDelete(reviewId: String, moderatorId: Long?): Boolean

    suspend fun ban(userId: Long, moderatorId: Long?, reason: String?)
    suspend fun unban(userId: Long, moderatorId: Long?)
}
