package zed.rainxch.githubstore.reviews

import java.util.concurrent.atomic.AtomicInteger

// Route-test stand-in for PostgresReviewStore. Keeps just enough state for the
// route contract (who wrote what, hidden, banned); the real SQL, sorts and
// aggregate maintenance are covered by PostgresReviewStoreIntegrationTest.
class InMemoryReviewStore : ReviewStore {
    private class Stored(val repoKey: String, var review: Review)

    private val reviews = mutableListOf<Stored>()
    val banned = mutableSetOf<Long>()
    val publicPageCalls = AtomicInteger()
    private var nextId = 0

    // Public reads key GitHub repos by name; writes by id. The fake keys
    // everything by lowercase host/owner/name, which is all route tests need.
    private fun key(host: String, owner: String, name: String) = "$host/$owner/$name".lowercase()
    private fun key(target: RepoTarget) = key(target.host, target.owner, target.name)

    fun hide(reviewId: String) = synchronized(this) {
        reviews.first { it.review.id == reviewId }.let { it.review = it.review.copy(hidden = true) }
    }

    override suspend fun publicPage(
        host: String,
        owner: String,
        name: String,
        sort: ReviewSort,
        rating: Int?,
        offset: Int,
        limit: Int,
    ): ReviewPage = synchronized(this) {
        publicPageCalls.incrementAndGet()
        val visible = reviews.filter { it.repoKey == key(host, owner, name) && !it.review.hidden }.map { it.review }
        val aggregate = visible.fold(RatingAggregate()) { acc, r -> acc.transition(null, r.rating) }
        val filtered = visible.filter { rating == null || it.rating == rating }
        ReviewPage(aggregate, filtered.drop(offset).take(limit), hasMore = filtered.size > offset + limit)
    }

    override suspend fun mine(target: RepoTarget, userId: Long): MyReviewState = synchronized(this) {
        MyReviewState(reviews.firstOrNull { it.repoKey == key(target) && it.review.author.id == userId }?.review, emptyMap())
    }

    override suspend fun isBanned(userId: Long): Boolean = synchronized(this) { userId in banned }

    override suspend fun putReview(target: RepoTarget, author: GitHubUser, input: ReviewInput): Review = synchronized(this) {
        val existing = reviews.firstOrNull { it.repoKey == key(target) && it.review.author.id == author.id }
        val now = "2026-10-25T10:00:00Z"
        val review = Review(
            id = existing?.review?.id ?: "rv_test${nextId++}",
            rating = input.rating,
            text = input.text,
            version = input.version,
            platform = input.platform,
            appLabel = input.appLabel,
            author = ReviewAuthor(author.id, author.login, author.avatarUrl),
            createdAt = existing?.review?.createdAt ?: now,
            updatedAt = now,
            edited = existing != null,
            score = existing?.review?.score ?: 0,
            reply = existing?.review?.reply,
            hidden = existing?.review?.hidden ?: false,
        )
        if (existing != null) existing.review = review else reviews += Stored(key(target), review)
        review
    }

    override suspend fun deleteReview(target: RepoTarget, userId: Long): Boolean = synchronized(this) {
        reviews.removeIf { it.repoKey == key(target) && it.review.author.id == userId }
    }
}
