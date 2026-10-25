package zed.rainxch.githubstore.reviews

import zed.rainxch.githubstore.util.PrivacyHash
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

// Resolves X-GitHub-Token to a GitHub user, and a repo to the caller's push
// access, with a 10-minute in-memory cache. Cache keys are
// HmacSHA256(DEVICE_ID_PEPPER, token) via PrivacyHash, never the token, and
// nothing here is persisted. Only successful lookups are cached, so a revoked
// token stops working as soon as its entry expires.
class ReviewIdentity(
    private val client: GitHubReviewClient,
    ttl: Duration = 10.minutes,
    clock: () -> Long = System::currentTimeMillis,
    // Hashes the token into a cache key; tests may substitute.
    private val tokenKey: (String) -> String = PrivacyHash::hash,
) {
    private val users = ExpiringCache<GitHubUser>(ttl, MAX_ENTRIES, clock)
    private val repos = ExpiringCache<GitHubRepoAccess>(ttl, MAX_ENTRIES, clock)

    suspend fun user(token: String): GitHubReviewClient.UserResult {
        val key = tokenKey(token)
        users.get(key)?.let { return GitHubReviewClient.UserResult.Ok(it) }
        val result = client.fetchUser(token)
        if (result is GitHubReviewClient.UserResult.Ok) users.put(key, result.user)
        return result
    }

    suspend fun repo(token: String, owner: String, name: String): GitHubReviewClient.RepoResult {
        val key = tokenKey(token) + "|" + owner.lowercase() + "/" + name.lowercase()
        repos.get(key)?.let { return GitHubReviewClient.RepoResult.Ok(it) }
        val result = client.fetchRepo(token, owner, name)
        if (result is GitHubReviewClient.RepoResult.Ok) repos.put(key, result.repo)
        return result
    }

    // For tests: the keys held, to assert no raw token is ever one of them.
    internal fun cachedKeys(): Set<String> = users.keys() + repos.keys()

    private companion object {
        const val MAX_ENTRIES = 50_000
    }
}

// Bounded TTL map. When full, expired entries are swept; if it is still full
// the new entry is dropped and the caller simply refetches next time.
internal class ExpiringCache<V : Any>(
    private val ttl: Duration,
    private val maxEntries: Int,
    private val clock: () -> Long,
) {
    private data class Entry<V>(val value: V, val expiresAt: Long)

    private val map = ConcurrentHashMap<String, Entry<V>>()

    fun get(key: String): V? {
        val entry = map[key] ?: return null
        if (entry.expiresAt <= clock()) {
            map.remove(key, entry)
            return null
        }
        return entry.value
    }

    fun put(key: String, value: V) {
        if (map.size >= maxEntries && !map.containsKey(key)) {
            val now = clock()
            map.entries.removeIf { it.value.expiresAt <= now }
            if (map.size >= maxEntries) return
        }
        map[key] = Entry(value, clock() + ttl.inWholeMilliseconds)
    }

    fun keys(): Set<String> = map.keys.toSet()
}
