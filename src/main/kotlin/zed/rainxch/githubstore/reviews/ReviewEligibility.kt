package zed.rainxch.githubstore.reviews

import java.time.Duration
import java.time.Instant

// Why a signed-in user may not rate a repo. `code` is the wire value of both
// the 403 error and /mine's cannotReviewReason.
enum class CannotReviewReason(val code: String) {
    BANNED("banned"),
    ACCOUNT_TOO_NEW("account_too_new"),
    OWN_REPO("own_repo"),
}

object ReviewEligibility {
    // Checked in contract order: banned, then account age, then push access.
    // hasPushAccess is always false for forge repos (no identity mapping).
    fun check(
        banned: Boolean,
        accountCreatedAt: Instant,
        now: Instant,
        minAccountAgeDays: Long,
        hasPushAccess: Boolean,
    ): CannotReviewReason? = when {
        banned -> CannotReviewReason.BANNED
        Duration.between(accountCreatedAt, now) < Duration.ofDays(minAccountAgeDays) -> CannotReviewReason.ACCOUNT_TOO_NEW
        hasPushAccess -> CannotReviewReason.OWN_REPO
        else -> null
    }
}
