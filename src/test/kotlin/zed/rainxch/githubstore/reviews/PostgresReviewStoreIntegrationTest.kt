package zed.rainxch.githubstore.reviews

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assume.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import zed.rainxch.githubstore.db.DatabaseFactory
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Runs PostgresReviewStore's SQL against a real Postgres: aggregate upkeep,
// sorts, repo resolution and the write path's locking.
//
// Database: a throwaway Testcontainers Postgres when Docker is available
// (CI). Without Docker, set TEST_DATABASE_URL (and optionally
// TEST_DATABASE_USER / TEST_DATABASE_PASSWORD, default githubstore) to a
// DISPOSABLE database: the test truncates the review tables and `repos`.
// With neither, it reports SKIPPED.
class PostgresReviewStoreIntegrationTest {

    private var container: PostgreSQLContainer<*>? = null
    private val store = PostgresReviewStore()

    private val github = { id: Long, owner: String, name: String -> RepoTarget(RepoTarget.GITHUB_HOST, id, owner, name) }
    private val app = github(100, "owner", "app")

    private fun user(id: Long, login: String = "user$id") =
        GitHubUser(id, login, "https://avatars.example/$id", Instant.parse("2020-01-01T00:00:00Z"))

    private fun input(rating: Int, text: String? = null) = ReviewInput(rating, text, null, null, null)

    @BeforeTest
    fun setUp() {
        val externalUrl = System.getenv("TEST_DATABASE_URL")?.takeIf { it.isNotBlank() }
        val docker = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
        assumeTrue("Neither Docker nor TEST_DATABASE_URL available, skipping", docker || externalUrl != null)
        if (docker) {
            container = PostgreSQLContainer("postgres:17-alpine")
            container!!.start()
            Database.connect(container!!.jdbcUrl, "org.postgresql.Driver", container!!.username, container!!.password)
        } else {
            Database.connect(
                url = externalUrl!!,
                driver = "org.postgresql.Driver",
                user = System.getenv("TEST_DATABASE_USER") ?: "githubstore",
                password = System.getenv("TEST_DATABASE_PASSWORD") ?: "githubstore",
            )
        }
        DatabaseFactory.runMigrations()
        sql("TRUNCATE review_moderation_log, review_bans, review_replies, review_reports, review_votes, reviews, review_repos, repos CASCADE")
    }

    @AfterTest
    fun tearDown() {
        container?.stop()
    }

    private fun sql(statement: String) = transaction {
        (TransactionManager.current().connection.connection as java.sql.Connection).createStatement().use { it.execute(statement) }
    }

    private fun <T> query(statement: String, map: (java.sql.ResultSet) -> T): List<T> = transaction {
        (TransactionManager.current().connection.connection as java.sql.Connection).createStatement().use { st ->
            st.executeQuery(statement).use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }
    }

    // The invariant: review_repos' aggregate equals a recount of visible reviews.
    private fun assertAggregateMatchesRecount() {
        val stored = query(
            "SELECT id, review_count, rating_sum, stars_1, stars_2, stars_3, stars_4, stars_5 FROM review_repos ORDER BY id",
        ) { rs -> rs.getLong(1) to (2..8).map { rs.getInt(it) } }
        val recount = query(
            """
            SELECT rr.id, COUNT(r.id), COALESCE(SUM(r.rating), 0),
                   COUNT(*) FILTER (WHERE r.rating = 1), COUNT(*) FILTER (WHERE r.rating = 2),
                   COUNT(*) FILTER (WHERE r.rating = 3), COUNT(*) FILTER (WHERE r.rating = 4),
                   COUNT(*) FILTER (WHERE r.rating = 5)
            FROM review_repos rr LEFT JOIN reviews r ON r.repo_id = rr.id AND r.hidden_at IS NULL
            GROUP BY rr.id ORDER BY rr.id
            """.trimIndent(),
        ) { rs -> rs.getLong(1) to (2..8).map { rs.getInt(it) } }
        assertEquals(recount, stored)
    }

    @Test
    fun `aggregate follows creates, edits and deletes`() = runBlocking {
        store.putReview(app, user(1), input(5))
        store.putReview(app, user(2), input(3))
        store.putReview(app, user(1), input(4))
        assertAggregateMatchesRecount()
        store.deleteReview(app, user(2).id)
        assertAggregateMatchesRecount()
        val page = store.publicPage("github.com", "owner", "app", ReviewSort.TOP, null, 0, 20)
        assertEquals(RatingAggregate(1, 4, listOf(0, 0, 0, 1, 0)), page.aggregate)
        assertFalse(store.deleteReview(app, user(2).id), "second delete should find nothing")
    }

    @Test
    fun `editing a hidden review keeps it hidden and out of the aggregate`() = runBlocking {
        val review = store.putReview(app, user(1), input(1))
        // Moderator hide (the moderation store lands next): flag plus aggregate removal.
        sql("UPDATE reviews SET hidden_at = NOW() WHERE id = '${review.id}'")
        sql("UPDATE review_repos SET review_count = 0, rating_sum = 0, stars_1 = 0")
        val edited = store.putReview(app, user(1), input(5, "changed my mind"))
        assertTrue(edited.hidden)
        assertTrue(edited.edited)
        assertAggregateMatchesRecount()
        assertEquals(0, store.publicPage("github.com", "owner", "app", ReviewSort.NEWEST, null, 0, 20).reviews.size)
        assertTrue(store.mine(app, 1).review!!.hidden)
    }

    @Test
    fun `concurrent writers on one repo keep the aggregate exact`() = runBlocking {
        val writers = 40
        (1..writers).map { id ->
            async(Dispatchers.IO) { store.putReview(app, user(id.toLong()), input((id % 5) + 1)) }
        }.awaitAll()
        assertAggregateMatchesRecount()
        val aggregate = store.publicPage("github.com", "owner", "app", ReviewSort.TOP, null, 0, 1).aggregate
        assertEquals(writers, aggregate.count)
    }

    @Test
    fun `sorts order by the contract's keys`() = runBlocking {
        val a = store.putReview(app, user(1), input(5)).id
        val b = store.putReview(app, user(2), input(1)).id
        val c = store.putReview(app, user(3), input(3)).id
        sql("UPDATE reviews SET score = 9, updated_at = NOW() - INTERVAL '3 days' WHERE id = '$b'")
        sql("UPDATE reviews SET score = 2, updated_at = NOW() - INTERVAL '1 day' WHERE id = '$c'")
        sql("UPDATE reviews SET score = 2, updated_at = NOW() - INTERVAL '2 days' WHERE id = '$a'")

        fun order(sort: ReviewSort) = runBlocking {
            store.publicPage("github.com", "owner", "app", sort, null, 0, 20).reviews.map { it.id }
        }
        assertEquals(listOf(b, c, a), order(ReviewSort.TOP))
        assertEquals(listOf(c, a, b), order(ReviewSort.NEWEST))
        assertEquals(listOf(a, c, b), order(ReviewSort.HIGHEST))
        assertEquals(listOf(b, c, a), order(ReviewSort.LOWEST))
    }

    @Test
    fun `rating filter and paging report hasMore`() = runBlocking {
        (1L..5L).forEach { store.putReview(app, user(it), input(if (it <= 3) 5 else 2)) }
        val first = store.publicPage("github.com", "owner", "app", ReviewSort.NEWEST, 5, 0, 2)
        assertEquals(2, first.reviews.size)
        assertTrue(first.hasMore)
        val last = store.publicPage("github.com", "owner", "app", ReviewSort.NEWEST, 5, 2, 2)
        assertEquals(1, last.reviews.size)
        assertFalse(last.hasMore)
        assertEquals(5, first.aggregate.count, "the summary ignores the rating filter")
    }

    @Test
    fun `public reads find a renamed repo through the catalog id, and names case-insensitively`() = runBlocking {
        store.putReview(github(100, "old-owner", "app"), user(1), input(4))
        // Catalog already knows the new name; review_repos still has the old one.
        sql("INSERT INTO repos (id, full_name, owner, name, html_url, topics) VALUES (100, 'new-owner/app', 'new-owner', 'app', 'https://github.com/new-owner/app', ARRAY[]::text[])")
        assertEquals(1, store.publicPage("github.com", "new-owner", "app", ReviewSort.TOP, null, 0, 20).aggregate.count)
        assertEquals(1, store.publicPage("github.com", "OLD-OWNER", "APP", ReviewSort.TOP, null, 0, 20).aggregate.count)
        // Writing again under the new name keeps the same repo row.
        store.putReview(github(100, "new-owner", "app"), user(2), input(2))
        assertEquals(1, query("SELECT COUNT(*) FROM review_repos") { it.getInt(1) }.single())
    }

    @Test
    fun `forge repos are keyed by host, owner and name, apart from GitHub`() = runBlocking {
        val forge = RepoTarget("codeberg.org", null, "Owner", "App")
        store.putReview(forge, user(1), input(5))
        store.putReview(RepoTarget("codeberg.org", null, "owner", "app"), user(2), input(3))
        store.putReview(app, user(3), input(1))
        assertEquals(2, store.publicPage("codeberg.org", "OWNER", "app", ReviewSort.TOP, null, 0, 20).aggregate.count)
        assertEquals(1, store.publicPage("github.com", "owner", "app", ReviewSort.TOP, null, 0, 20).aggregate.count)
        assertEquals(0, store.publicPage("gitea.com", "owner", "app", ReviewSort.TOP, null, 0, 20).aggregate.count)
        assertAggregateMatchesRecount()
    }

    @Test
    fun `a write refreshes the author's login and avatar on all their reviews`() = runBlocking {
        val other = github(200, "owner", "other")
        store.putReview(app, user(1, "before"), input(5))
        store.putReview(other, user(1, "after"), input(4))
        val onApp = store.publicPage("github.com", "owner", "app", ReviewSort.TOP, null, 0, 20).reviews.single()
        assertEquals("after", onApp.author.login)
        assertFalse(onApp.edited, "a snapshot refresh is not an edit")
    }

    @Test
    fun `mine lists the caller's votes and deleting a review removes its votes`() = runBlocking {
        val target = store.putReview(app, user(1), input(5))
        sql("INSERT INTO review_votes (review_id, voter_id, value) VALUES ('${target.id}', 2, -1)")
        assertEquals(mapOf(target.id to -1), store.mine(app, 2).votes)
        store.deleteReview(app, 1)
        assertEquals(0, query("SELECT COUNT(*) FROM review_votes") { it.getInt(1) }.single())
    }

    @Test
    fun `review ids are opaque rv_ plus 22 base64url chars`() = runBlocking {
        val id = store.putReview(app, user(1), input(5)).id
        assertTrue(Regex("^rv_[A-Za-z0-9_-]{22}$").matches(id), id)
    }

    @Test
    fun `bans are visible to isBanned`() = runBlocking {
        sql("INSERT INTO review_bans (github_id) VALUES (7)")
        assertTrue(store.isBanned(7))
        assertFalse(store.isBanned(8))
    }

    // ---- votes, reports, replies ----

    @Test
    fun `votes move the score by each change and refuse own or hidden reviews`() = runBlocking {
        val id = store.putReview(app, user(1), input(5)).id
        suspend fun score() = store.publicPage("github.com", "owner", "app", ReviewSort.TOP, null, 0, 20).reviews.single().score
        assertEquals(ItemResult.OK, store.vote(id, 2, 1))
        assertEquals(ItemResult.OK, store.vote(id, 2, 1))
        assertEquals(ItemResult.OK, store.vote(id, 3, -1))
        assertEquals(ItemResult.OK, store.vote(id, 4, -1))
        assertEquals(-1, score())
        assertEquals(ItemResult.OK, store.vote(id, 3, 1))
        assertEquals(1, score(), "switching a vote moves the score by two")
        assertEquals(ItemResult.OWN_REVIEW, store.vote(id, 1, 1))
        assertEquals(mapOf(id to 1), store.mine(app, 3).votes)
        assertEquals(ItemResult.OK, store.unvote(id, 2))
        assertEquals(ItemResult.OK, store.unvote(id, 2))
        assertEquals(0, score())
        sql("UPDATE reviews SET hidden_at = NOW() WHERE id = '$id'")
        assertEquals(ItemResult.NOT_FOUND, store.vote(id, 5, 1))
        assertEquals(ItemResult.NOT_FOUND, store.vote("rv_missingMissingMissing0", 5, 1))
    }

    @Test
    fun `concurrent votes keep score equal to the sum of votes`() = runBlocking {
        val id = store.putReview(app, user(1), input(5)).id
        (2L..41L).map { voter ->
            async(Dispatchers.IO) { store.vote(id, voter, if (voter % 3 == 0L) -1 else 1) }
        }.awaitAll()
        val (score, sum) = query(
            "SELECT score, (SELECT SUM(value) FROM review_votes WHERE review_id = '$id') FROM reviews WHERE id = '$id'",
        ) { it.getInt(1) to it.getInt(2) }.single()
        assertEquals(sum, score)
    }

    @Test
    fun `replies are created, replaced by another maintainer and deleted`() = runBlocking {
        val id = store.putReview(app, user(1), input(4)).id
        val first = store.putReply(id, user(7, "maint-a"), "Thanks")!!
        sql("UPDATE review_replies SET created_at = NOW() - INTERVAL '1 day', updated_at = NOW() - INTERVAL '1 day'")
        val second = store.putReply(id, user(8, "maint-b"), "Fixed in v2")!!
        assertEquals("maint-b", second.author.login)
        assertTrue(second.createdAt < second.updatedAt, "a replaced reply keeps its createdAt")
        assertEquals("maint-a", first.author.login)
        val listed = store.publicPage("github.com", "owner", "app", ReviewSort.TOP, null, 0, 20).reviews.single()
        assertEquals("Fixed in v2", listed.reply!!.text)
        assertTrue(store.deleteReply(id))
        assertEquals(null, store.publicPage("github.com", "owner", "app", ReviewSort.TOP, null, 0, 20).reviews.single().reply)
        assertEquals(app, store.reviewRepo(id))
        sql("UPDATE reviews SET hidden_at = NOW() WHERE id = '$id'")
        assertEquals(null, store.putReply(id, user(7), "late"))
        assertEquals(null, store.reviewRepo(id))
    }

    @Test
    fun `forge reviews resolve to a target without a GitHub id`() = runBlocking {
        val forge = RepoTarget("codeberg.org", null, "Owner", "App")
        val id = store.putReview(forge, user(1), input(3)).id
        assertEquals(forge, store.reviewRepo(id))
    }
}
