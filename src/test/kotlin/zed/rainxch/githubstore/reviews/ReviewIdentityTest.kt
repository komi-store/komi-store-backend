package zed.rainxch.githubstore.reviews

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class ReviewIdentityTest {

    private val token = "ghp_testtoken123"
    private var now = 0L
    private val github = FakeGitHub().apply {
        user(token, id = 583231, login = "octocat", createdAt = "2011-01-25T18:44:36Z")
        repo("Owner", "App", id = 42)
        repo("owner", "secret", id = 43, private = true)
        repo("me", "mine", id = 44, pushTokens = setOf(token))
    }
    private val identity = ReviewIdentity(GitHubReviewClient(github.engine), ttl = 10.minutes, clock = { now })

    @Test
    fun `resolves the user from GET user with the token in the Authorization header`() = runBlocking {
        val user = assertIs<GitHubReviewClient.UserResult.Ok>(identity.user(token)).user
        assertEquals(GitHubUser(583231, "octocat", "https://avatars.example/583231", Instant.parse("2011-01-25T18:44:36Z")), user)
        assertEquals(listOf<String?>("token $token"), github.authHeaders)
    }

    @Test
    fun `a cached user is served for ten minutes, then refetched`() = runBlocking {
        identity.user(token)
        now += 9.minutes.inWholeMilliseconds
        identity.user(token)
        assertEquals(1, github.calls.get())
        now += 2.minutes.inWholeMilliseconds
        identity.user(token)
        assertEquals(2, github.calls.get())
    }

    @Test
    fun `cache keys are an HMAC of the token, never the token`() = runBlocking {
        identity.user(token)
        identity.repo(token, "owner", "app")
        val keys = identity.cachedKeys()
        assertEquals(2, keys.size)
        assertTrue(keys.none { token in it }, "raw token leaked into a cache key: $keys")
    }

    @Test
    fun `upstream 401 is Invalid and is not cached`() = runBlocking {
        assertEquals(GitHubReviewClient.UserResult.Invalid, identity.user("ghp_revoked"))
        assertEquals(GitHubReviewClient.UserResult.Invalid, identity.user("ghp_revoked"))
        assertEquals(2, github.calls.get())
    }

    @Test
    fun `GitHub rate limit or outage is Unavailable`() = runBlocking {
        github.outage = HttpStatusCode.Forbidden
        github.outageHeaders = listOf("x-ratelimit-remaining" to "0")
        assertEquals(GitHubReviewClient.UserResult.Unavailable, identity.user(token))
        assertEquals(GitHubReviewClient.RepoResult.Unavailable, identity.repo(token, "owner", "app"))
        github.outage = HttpStatusCode.BadGateway
        github.outageHeaders = emptyList()
        assertEquals(GitHubReviewClient.RepoResult.Unavailable, identity.repo(token, "owner", "app"))
    }

    @Test
    fun `repo resolves to its id, canonical name and push access`() = runBlocking {
        val repo = assertIs<GitHubReviewClient.RepoResult.Ok>(identity.repo(token, "owner", "app")).repo
        assertEquals(GitHubRepoAccess(42, "Owner", "App", canPush = false), repo)
        val own = assertIs<GitHubReviewClient.RepoResult.Ok>(identity.repo(token, "me", "mine")).repo
        assertTrue(own.canPush)
    }

    @Test
    fun `private and missing repos are NotFound`() = runBlocking {
        assertEquals(GitHubReviewClient.RepoResult.NotFound, identity.repo(token, "owner", "secret"))
        assertEquals(GitHubReviewClient.RepoResult.NotFound, identity.repo(token, "owner", "nope"))
    }

    @Test
    fun `repo access is cached per token`() = runBlocking {
        identity.repo(token, "owner", "app")
        identity.repo(token, "OWNER", "APP")
        assertEquals(1, github.calls.get())
        val other = "ghp_othertoken"
        github.user(other, id = 7, login = "someone")
        assertFalse(assertIs<GitHubReviewClient.RepoResult.Ok>(identity.repo(other, "me", "mine")).repo.canPush)
        assertEquals(2, github.calls.get())
    }
}
