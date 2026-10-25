package zed.rainxch.githubstore.reviews

import kotlin.math.roundToInt

// Rating totals over a repo's VISIBLE reviews (review_repos.review_count,
// rating_sum, stars_1..stars_5). Pure so the update rule is unit-testable;
// PostgresReviewStore applies it while holding the review_repos row lock.
data class RatingAggregate(
    val count: Int = 0,
    val sum: Int = 0,
    // Index 0 counts 1-star reviews, index 4 counts 5-star reviews.
    val stars: List<Int> = List(5) { 0 },
) {
    init {
        require(stars.size == 5) { "stars must have five buckets" }
    }

    /**
     * Moves one review from [before] to [after], where each is the rating the
     * review contributes while visible, or null when it doesn't count (absent
     * or hidden). Create = (null, r), edit = (old, new), delete or hide =
     * (r, null), unhide = (null, r), editing a hidden review = (null, null).
     */
    fun transition(before: Int?, after: Int?): RatingAggregate {
        var next = this
        if (before != null) next = next.adjust(before, -1)
        if (after != null) next = next.adjust(after, +1)
        return next
    }

    private fun adjust(rating: Int, delta: Int): RatingAggregate {
        require(rating in 1..5) { "rating out of range: $rating" }
        val updated = stars.toMutableList()
        updated[rating - 1] += delta
        return RatingAggregate(count = count + delta, sum = sum + rating * delta, stars = updated)
    }

    // Rounded to one decimal; null when nothing is visible.
    val average: Double?
        get() = if (count <= 0) null else (sum.toDouble() / count * 10).roundToInt() / 10.0

    fun toSummary(): RatingSummary = RatingSummary(
        average = average,
        count = count,
        histogram = (1..5).associate { it.toString() to stars[it - 1] },
    )
}
