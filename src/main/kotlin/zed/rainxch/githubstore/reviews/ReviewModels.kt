package zed.rainxch.githubstore.reviews

import kotlinx.serialization.Serializable

// Wire shapes for /v1/reviews. The contract lives in the client repo at
// docs/agents/reviews.md; change both sides together.

@Serializable
data class ReviewAuthor(
    val id: Long,
    val login: String,
    val avatarUrl: String?,
)

@Serializable
data class ReviewReply(
    val author: ReviewAuthor,
    val text: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class Review(
    val id: String,
    val rating: Int,
    val text: String?,
    val version: String?,
    val platform: String?,
    val appLabel: String?,
    val author: ReviewAuthor,
    val createdAt: String,
    val updatedAt: String,
    val edited: Boolean,
    // Upvotes minus downvotes; can be negative.
    val score: Int,
    val reply: ReviewReply?,
    // Always false on the public list. True in /mine when a moderator hid the
    // caller's review, and in the moderation queue.
    val hidden: Boolean = false,
)

@Serializable
data class RatingSummary(
    val average: Double?,
    val count: Int,
    // Always all five keys "1".."5".
    val histogram: Map<String, Int>,
)

@Serializable
data class ReviewListResponse(
    val summary: RatingSummary,
    val reviews: List<Review>,
    val page: Int,
    val hasMore: Boolean,
)

@Serializable
data class MyReviewResponse(
    val review: Review?,
    // The caller's votes on this repo's reviews: reviewId to 1 or -1.
    val votes: Map<String, Int>,
    val canReview: Boolean,
    val cannotReviewReason: String?,
    val canReply: Boolean,
    val isModerator: Boolean,
)

@Serializable
data class ReviewWriteRequest(
    val rating: Int? = null,
    val text: String? = null,
    val version: String? = null,
    val platform: String? = null,
    val appLabel: String? = null,
)

enum class ReviewSort(val wire: String) {
    TOP("top"),
    NEWEST("newest"),
    HIGHEST("highest"),
    LOWEST("lowest");

    companion object {
        fun fromWire(value: String?): ReviewSort? =
            if (value == null) TOP else entries.firstOrNull { it.wire == value }
    }
}
