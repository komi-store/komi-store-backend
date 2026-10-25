package zed.rainxch.githubstore.reviews

import java.util.concurrent.atomic.AtomicInteger

// Route-test stand-in for PostgresReviewStore. Keeps just enough state for the
// route contract (who wrote what, hidden, banned, votes, reports, replies);
// the real SQL, sorts and aggregate upkeep are covered by
// PostgresReviewStoreIntegrationTest.
class InMemoryReviewStore : ReviewStore, ReviewModerationStore {
    private class Stored(val target: RepoTarget, var review: Review)

    private val reviews = mutableListOf<Stored>()
    private val votes = mutableMapOf<Pair<String, Long>, Int>()
    private val reports = mutableMapOf<Pair<String, Long>, String>()
    val banned = mutableSetOf<Long>()
    val moderationLog = mutableListOf<String>()
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

    private fun aggregateOf(match: (Stored) -> Boolean): RatingAggregate =
        reviews.filter { match(it) && !it.review.hidden }.fold(RatingAggregate()) { acc, r -> acc.transition(null, r.review.rating) }

    override suspend fun ratings(targets: List<RepoTarget>): Map<RepoTarget, RatingAggregate> = synchronized(this) {
        targets.mapNotNull { target ->
            val aggregate = aggregateOf { stored ->
                if (target.githubId != null) stored.target.githubId == target.githubId
                else stored.target.githubId == null && key(stored.target) == key(target)
            }
            if (aggregate.count > 0) target to aggregate else null
        }.toMap()
    }

    override suspend fun summaries(host: String, repos: List<Pair<String, String>>): List<RatingAggregate?> = synchronized(this) {
        repos.map { (owner, name) -> aggregateOf { key(it.target) == key(host, owner, name) } }
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

    // ---- moderation ----

    override suspend fun moderationQueue(status: ModerationQueueStatus, offset: Int, limit: Int): ModerationPage = synchronized(this) {
        val matching = reviews.filter {
            when (status) {
                ModerationQueueStatus.REPORTED -> reports.keys.any { (id, _) -> id == it.review.id }
                ModerationQueueStatus.HIDDEN -> it.review.hidden
            }
        }
        val items = matching.drop(offset).take(limit).map { stored ->
            val reviewReports = reports.filterKeys { it.first == stored.review.id }.values
                .map { ModerationReport(it, null, "2026-10-25T10:00:00Z") }
            ModerationQueueItem(
                review = stored.review,
                repo = ModerationRepo(stored.target.host, stored.target.owner, stored.target.name),
                reportCount = reviewReports.size,
                reports = reviewReports,
                lastReportedAt = reviewReports.firstOrNull()?.createdAt,
                hidden = stored.review.hidden,
                hiddenReason = null,
            )
        }
        ModerationPage(items, hasMore = matching.size > offset + limit)
    }

    fun hideNow(reviewId: String) = synchronized(this) {
        find(reviewId)!!.let { it.review = it.review.copy(hidden = true) }
    }

    override suspend fun hide(reviewId: String, moderatorId: Long?, reason: String?): Boolean {
        return synchronized(this) {
            val stored = find(reviewId) ?: return false
            stored.review = stored.review.copy(hidden = true)
            reports.keys.removeIf { it.first == reviewId }
            moderationLog += "hide $reviewId by $moderatorId: $reason"
            true
        }
    }

    override suspend fun unhide(reviewId: String, moderatorId: Long?): Boolean {
        return synchronized(this) {
            val stored = find(reviewId) ?: return false
            stored.review = stored.review.copy(hidden = false)
            moderationLog += "unhide $reviewId by $moderatorId"
            true
        }
    }

    override suspend fun dismiss(reviewId: String, moderatorId: Long?): Boolean {
        return synchronized(this) {
            find(reviewId) ?: return false
            reports.keys.removeIf { it.first == reviewId }
            moderationLog += "dismiss $reviewId by $moderatorId"
            true
        }
    }

    override suspend fun moderatorDelete(reviewId: String, moderatorId: Long?): Boolean = synchronized(this) {
        val removed = reviews.removeIf { it.review.id == reviewId }
        if (removed) moderationLog += "delete $reviewId by $moderatorId"
        removed
    }

    override suspend fun ban(userId: Long, moderatorId: Long?, reason: String?) = synchronized(this) {
        banned += userId
        moderationLog += "ban $userId by $moderatorId: $reason"
    }

    override suspend fun unban(userId: Long, moderatorId: Long?) = synchronized(this) {
        banned -= userId
        moderationLog += "unban $userId by $moderatorId"
    }
}
