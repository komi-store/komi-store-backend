package zed.rainxch.githubstore.reviews

import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

// Per-GitHub-user write limit (contract: reviews + replies 20/hr, votes
// 120/hr, reports 20/hr). The Ktor RateLimit plugin keys before the handler
// runs, but the user id is only known after the token resolves, so these
// limits live here. Fixed window per user, in memory: one VPS, one process.
class WindowRateLimiter(
    private val limit: Int,
    private val window: Duration,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Window(var start: Long, var count: Int)

    private val windows = ConcurrentHashMap<Long, Window>()

    /** Takes one slot for [userId]. Null when allowed, else seconds until the window resets. */
    fun tryAcquire(userId: Long): Long? {
        val now = clock()
        val windowMs = window.inWholeMilliseconds
        if (windows.size > SWEEP_THRESHOLD) windows.entries.removeIf { now - it.value.start >= windowMs }
        val w = windows.computeIfAbsent(userId) { Window(now, 0) }
        synchronized(w) {
            if (now - w.start >= windowMs) {
                w.start = now
                w.count = 0
            }
            if (w.count >= limit) return ((w.start + windowMs - now + 999) / 1000).coerceAtLeast(1)
            w.count++
            return null
        }
    }

    private companion object {
        const val SWEEP_THRESHOLD = 10_000
    }
}

// The contract's per-user limits. Reviews and replies share one bucket.
class ReviewRateLimits(
    val reviews: WindowRateLimiter = WindowRateLimiter(limit = 20, window = 1.hours),
    val votes: WindowRateLimiter = WindowRateLimiter(limit = 120, window = 1.hours),
    val reports: WindowRateLimiter = WindowRateLimiter(limit = 20, window = 1.hours),
)
