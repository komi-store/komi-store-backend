package zed.rainxch.githubstore.reviews

import java.util.concurrent.atomic.AtomicInteger

// Route-test stand-in for PostgresReviewStore. Keeps just enough state for the
// route contract (who wrote what, hidden, banned, votes, reports, replies);
// the real SQL, sorts and aggregate upkeep are covered by
// PostgresReviewStoreIntegrationTest.
class InMemoryReviewStore : ReviewStore {
    private class Stored(val target: RepoTarget, var review: Review)

    private val reviews = mutableListOf<Stored>()
    private val votes = mutableMapOf<Pair<String, Long>, Int>()
    private val reports = mutableMapOf<Pair<String, Long>, String>()
    val banned = mutableSetOf<Long>()
    val publicPageCalls = AtomicInteger()
    private var nextId = 0

    // Public reads key GitHub repos by name; writes by id. The fake keys
    // everything by lowercase host/owner/name, which is all route tests need.
    private fun key(host: String, owner: String, name: String) = "$host/$owner/$name".lowercase()
    private fun key(target: RepoTarget) = key(target.host, target.owner, target.name)
    private fun find(id: String) = reviews.firstOrNull { it.review.id == id }
    private fun visible(id: String) = find(id)?.takeUnless { it.review.hidden }

    fun reportCount(reviewId: String) = synchronized(this) { reports.keys.count { it.first == reviewId } }

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
        val shown = reviews.filter { key(it.target) == key(host, owner, name) && !it.review.hidden }.map { it.review }
        val aggregate = shown.fold(RatingAggregate()) { acc, r -> acc.transition(null, r.rating) }
        val filtered = shown.filter { rating == null || it.rating == rating }
        ReviewPage(aggregate, filtered.drop(offset).take(limit), hasMore = filtered.size > offset + limit)
    }

    override suspend fun mine(target: RepoTarget, userId: Long): MyReviewState = synchronized(this) {
        val voted = votes.filterKeys { (id, voter) -> voter == userId && visible(id)?.let { key(it.target) == key(target) } == true }
        MyReviewState(
            reviews.firstOrNull { key(it.target) == key(target) && it.review.author.id == userId }?.review,
            voted.mapKeys { it.key.first },
        )
    }

    override suspend fun isBanned(userId: Long): Boolean = synchronized(this) { userId in banned }

    override suspend fun putReview(target: RepoTarget, author: GitHubUser, input: ReviewInput): Review = synchronized(this) {
        val existing = reviews.firstOrNull { key(it.target) == key(target) && it.review.author.id == author.id }
        val now = "2026-10-25T10:00:00Z"
        val review = Review(
            id = existing?.review?.id ?: "rv_test%018d".format(nextId++),
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
        if (existing != null) existing.review = review else reviews += Stored(target, review)
        review
    }

    override suspend fun deleteReview(target: RepoTarget, userId: Long): Boolean = synchronized(this) {
        reviews.removeIf { key(it.target) == key(target) && it.review.author.id == userId }
    }

    override suspend fun vote(reviewId: String, voterId: Long, value: Int): ItemResult {
        return synchronized(this) {
            val stored = visible(reviewId) ?: return ItemResult.NOT_FOUND
            if (stored.review.author.id == voterId) return ItemResult.OWN_REVIEW
            val previous = votes.put(reviewId to voterId, value) ?: 0
            stored.review = stored.review.copy(score = stored.review.score + value - previous)
            ItemResult.OK
        }
    }

    override suspend fun unvote(reviewId: String, voterId: Long): ItemResult {
        return synchronized(this) {
            val stored = visible(reviewId) ?: return ItemResult.NOT_FOUND
            val previous = votes.remove(reviewId to voterId) ?: 0
            stored.review = stored.review.copy(score = stored.review.score - previous)
            ItemResult.OK
        }
    }

    override suspend fun report(reviewId: String, reporterId: Long, reason: String, note: String?): ItemResult {
        return synchronized(this) {
            val stored = visible(reviewId) ?: return ItemResult.NOT_FOUND
            if (stored.review.author.id == reporterId) return ItemResult.OWN_REVIEW
            reports.putIfAbsent(reviewId to reporterId, reason)
            ItemResult.OK
        }
    }

    override suspend fun reviewRepo(reviewId: String): RepoTarget? = synchronized(this) { visible(reviewId)?.target }

    override suspend fun putReply(reviewId: String, author: GitHubUser, text: String): ReviewReply? {
        return synchronized(this) {
            val stored = visible(reviewId) ?: return null
            val now = "2026-10-25T10:00:00Z"
            val reply = ReviewReply(ReviewAuthor(author.id, author.login, author.avatarUrl), text, stored.review.reply?.createdAt ?: now, now)
            stored.review = stored.review.copy(reply = reply)
            reply
        }
    }

    override suspend fun deleteReply(reviewId: String): Boolean {
        return synchronized(this) {
            val stored = visible(reviewId) ?: return false
            stored.review = stored.review.copy(reply = null)
            true
        }
    }

    fun hideNow(reviewId: String) = synchronized(this) {
        find(reviewId)!!.let { it.review = it.review.copy(hidden = true) }
    }
}
