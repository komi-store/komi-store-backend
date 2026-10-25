package zed.rainxch.githubstore.reviews

import kotlinx.coroutines.runBlocking
import zed.rainxch.githubstore.model.RepoOwner
import zed.rainxch.githubstore.model.RepoRating
import zed.rainxch.githubstore.model.RepoResponse
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class RepoRatingsTest {

    private val store = InMemoryReviewStore()

    private fun user(id: Long) = GitHubUser(id, "user$id", null, Instant.EPOCH)

    private fun repo(id: Long, owner: String, name: String, host: String? = null) = RepoResponse(
        id = id, name = name, fullName = "$owner/$name", owner = RepoOwner(owner, null),
        description = null, defaultBranch = null, htmlUrl = "https://example/$owner/$name",
        stargazersCount = 0, forksCount = 0, language = null, topics = emptyList(),
        releasesUrl = null, updatedAt = null, createdAt = null, sourceHost = host,
    )

    private suspend fun review(target: RepoTarget, userId: Long, rating: Int) =
        store.putReview(target, user(userId), ReviewInput(rating, null, null, null, null))

    @Test
    fun `GitHub repos get their rating by numeric id, so a rename keeps it`() = runBlocking {
        val old = RepoTarget(RepoTarget.GITHUB_HOST, 100, "old-owner", "app")
        review(old, 1, 4)
        review(old, 2, 5)
        val out = RepoRatings(store, enabled = true).attach(listOf(repo(100, "new-owner", "app"), repo(200, "x", "y")))
        assertEquals(RepoRating(average = 4.5, count = 2), out[0].rating)
        assertNull(out[1].rating, "a repo without reviews keeps rating null")
    }

    @Test
    fun `forge repos get their rating by host, owner and name`() = runBlocking {
        review(RepoTarget("codeberg.org", null, "Owner", "App"), 1, 3)
        val out = RepoRatings(store, enabled = true).attach(
            listOf(repo(0, "owner", "app", host = "codeberg.org"), repo(0, "owner", "app", host = "gitea.com")),
        )
        assertEquals(RepoRating(3.0, 1), out[0].rating)
        assertNull(out[1].rating)
    }

    @Test
    fun `hidden reviews don't count`() = runBlocking {
        val target = RepoTarget(RepoTarget.GITHUB_HOST, 100, "owner", "app")
        review(target, 1, 5)
        store.hideNow(review(target, 2, 1).id)
        assertEquals(RepoRating(5.0, 1), RepoRatings(store, enabled = true).attach(repo(100, "owner", "app")).rating)
    }

    @Test
    fun `off leaves every rating null`() = runBlocking {
        review(RepoTarget(RepoTarget.GITHUB_HOST, 100, "owner", "app"), 1, 5)
        val input = listOf(repo(100, "owner", "app"))
        assertSame(input, RepoRatings(store, enabled = false).attach(input))
    }

    @Test
    fun `a failed lookup serves the repos without ratings`() = runBlocking {
        val broken = object : ReviewStore by store {
            override suspend fun ratings(targets: List<RepoTarget>): Map<RepoTarget, RatingAggregate> = error("db down")
        }
        val input = listOf(repo(100, "owner", "app"))
        assertSame(input, RepoRatings(broken, enabled = true).attach(input))
    }
}
