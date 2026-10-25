package zed.rainxch.githubstore.reviews

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReviewEligibilityTest {

    private val now = Instant.parse("2026-10-25T10:00:00Z")
    private val old = now.minus(Duration.ofDays(400))

    private fun check(
        banned: Boolean = false,
        createdAt: Instant = old,
        push: Boolean = false,
    ) = ReviewEligibility.check(banned, createdAt, now, minAccountAgeDays = 30, hasPushAccess = push)

    @Test
    fun `an old account without push access may review`() {
        assertNull(check())
    }

    @Test
    fun `accounts younger than the minimum are refused, exactly the minimum is fine`() {
        assertEquals(CannotReviewReason.ACCOUNT_TOO_NEW, check(createdAt = now.minus(Duration.ofDays(29))))
        assertNull(check(createdAt = now.minus(Duration.ofDays(30))))
    }

    @Test
    fun `push access means own repo`() {
        assertEquals(CannotReviewReason.OWN_REPO, check(push = true))
    }

    @Test
    fun `reasons follow contract order banned, account age, own repo`() {
        val young = now.minus(Duration.ofDays(1))
        assertEquals(CannotReviewReason.BANNED, check(banned = true, createdAt = young, push = true))
        assertEquals(CannotReviewReason.ACCOUNT_TOO_NEW, check(createdAt = young, push = true))
    }

    @Test
    fun `wire codes match the contract`() {
        assertEquals(
            listOf("banned", "account_too_new", "own_repo"),
            CannotReviewReason.entries.map { it.code },
        )
    }
}
