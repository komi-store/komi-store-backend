package zed.rainxch.githubstore.reviews

import zed.rainxch.githubstore.util.FeatureFlags

data class ReviewsConfig(
    val enabled: Boolean,
    // GitHub accounts younger than this can't write reviews.
    val minAccountAgeDays: Long = DEFAULT_MIN_ACCOUNT_AGE_DAYS,
    // GitHub user ids allowed to moderate (REVIEW_MODERATOR_IDS).
    val moderatorIds: Set<Long> = emptySet(),
) {
    companion object {
        const val DEFAULT_MIN_ACCOUNT_AGE_DAYS = 30L

        fun fromEnv(): ReviewsConfig = ReviewsConfig(
            enabled = FeatureFlags.reviewsEnabled,
            minAccountAgeDays = System.getenv("REVIEW_MIN_ACCOUNT_AGE_DAYS")?.trim()?.toLongOrNull()
                ?.coerceAtLeast(0)
                ?: DEFAULT_MIN_ACCOUNT_AGE_DAYS,
            moderatorIds = parseIds(System.getenv("REVIEW_MODERATOR_IDS")),
        )

        fun parseIds(raw: String?): Set<Long> =
            raw?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.toSet().orEmpty()
    }
}
