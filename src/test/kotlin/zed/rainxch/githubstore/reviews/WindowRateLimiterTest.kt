package zed.rainxch.githubstore.reviews

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours

class WindowRateLimiterTest {

    private var now = 1_000_000L
    private val limiter = WindowRateLimiter(limit = 2, window = 1.hours, clock = { now })

    @Test
    fun `allows the limit, then reports seconds until the window resets`() {
        assertNull(limiter.tryAcquire(1))
        assertNull(limiter.tryAcquire(1))
        now += 60_000
        assertEquals(3540L, limiter.tryAcquire(1))
    }

    @Test
    fun `users have separate windows`() {
        assertNull(limiter.tryAcquire(1))
        assertNull(limiter.tryAcquire(1))
        assertNull(limiter.tryAcquire(2))
    }

    @Test
    fun `a new window starts once the old one has passed`() {
        assertNull(limiter.tryAcquire(1))
        assertNull(limiter.tryAcquire(1))
        now += 1.hours.inWholeMilliseconds
        assertNull(limiter.tryAcquire(1))
    }
}
