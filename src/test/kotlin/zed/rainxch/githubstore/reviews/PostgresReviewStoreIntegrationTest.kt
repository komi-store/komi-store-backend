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
        store.hide(review.id, moderatorId = 9, reason = "spam")
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
        store.hide(id, moderatorId = 9, reason = null)
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
        store.hide(id, null, null)
        assertEquals(null, store.putReply(id, user(7), "late"))
        assertEquals(null, store.reviewRepo(id))
    }

    @Test
    fun `forge reviews resolve to a target without a GitHub id`() = runBlocking {
        val forge = RepoTarget("codeberg.org", null, "Owner", "App")
        val id = store.putReview(forge, user(1), input(3)).id
        assertEquals(forge, store.reviewRepo(id))
    }

    // ---- moderation ----

    @Test
    fun `hide, unhide and moderator delete keep the aggregate exact`() = runBlocking {
        val a = store.putReview(app, user(1), input(5)).id
        val b = store.putReview(app, user(2), input(1)).id
        store.putReview(app, user(3), input(3))
        assertTrue(store.hide(b, moderatorId = 9, reason = "spam"))
        assertTrue(store.hide(b, moderatorId = 9, reason = "spam"), "hiding twice is a no-op")
        assertAggregateMatchesRecount()
        assertEquals(2, store.publicPage("github.com", "owner", "app", ReviewSort.TOP, null, 0, 20).aggregate.count)
        assertTrue(store.unhide(b, moderatorId = null))
        assertAggregateMatchesRecount()
        assertTrue(store.moderatorDelete(a, moderatorId = 9))
        assertAggregateMatchesRecount()
        assertFalse(store.moderatorDelete(a, moderatorId = 9))
        assertFalse(store.hide("rv_missingMissingMissing0", 9, null))
        assertEquals(
            listOf("hide" to 9L, "hide" to 9L, "unhide" to null, "delete" to 9L),
            query("SELECT action, moderator_id FROM review_moderation_log ORDER BY id") { rs ->
                rs.getString(1) to (rs.getObject(2) as? Number)?.toLong()
            },
        )
        assertEquals(1L, query("SELECT target_user_id FROM review_moderation_log WHERE action = 'delete'") { it.getLong(1) }.single())
    }

    @Test
    fun `reports queue a review until a moderator hides or dismisses it`() = runBlocking {
        val first = store.putReview(app, user(1), input(1)).id
        val second = store.putReview(app, user(2), input(2)).id
        assertEquals(ItemResult.OK, store.report(first, 3, "spam", "ads"))
        assertEquals(ItemResult.OK, store.report(first, 3, "other", null))
        assertEquals(ItemResult.OK, store.report(first, 4, "offensive", null))
        assertEquals(ItemResult.OWN_REVIEW, store.report(first, 1, "spam", null))
        sql("UPDATE review_reports SET created_at = NOW() - INTERVAL '1 hour'")
        assertEquals(ItemResult.OK, store.report(second, 3, "off_topic", null))

        val reported = store.moderationQueue(ModerationQueueStatus.REPORTED, 0, 20).items
        assertEquals(listOf(second, first), reported.map { it.review.id }, "newest report first")
        val firstItem = reported.last()
        assertEquals(2, firstItem.reportCount)
        assertEquals(setOf("spam", "offensive"), firstItem.reports.map { it.reason }.toSet())
        assertEquals(ModerationRepo("github.com", "owner", "app"), firstItem.repo)

        assertTrue(store.dismiss(second, moderatorId = 9))
        assertTrue(store.hide(first, moderatorId = 9, reason = "spam"))
        assertEquals(0, store.moderationQueue(ModerationQueueStatus.REPORTED, 0, 20).items.size)

        val hidden = store.moderationQueue(ModerationQueueStatus.HIDDEN, 0, 20).items.single()
        assertEquals(first, hidden.review.id)
        assertTrue(hidden.hidden)
        assertTrue(hidden.review.hidden)
        assertEquals("spam", hidden.hiddenReason)
        assertEquals(2, hidden.reportCount, "the reports a hide closed still explain it")
        assertEquals(ItemResult.NOT_FOUND, store.report(first, 5, "spam", null))
    }

    @Test
    fun `queue pages report hasMore`() = runBlocking {
        val ids = (1L..3L).map { store.putReview(app, user(it), input(2)).id }
        ids.forEach { store.report(it, 99, "spam", null) }
        val page = store.moderationQueue(ModerationQueueStatus.REPORTED, 0, 2)
        assertEquals(2, page.items.size)
        assertTrue(page.hasMore)
        assertFalse(store.moderationQueue(ModerationQueueStatus.REPORTED, 2, 2).hasMore)
    }

    @Test
    fun `ban and unban are idempotent and logged`() = runBlocking {
        store.ban(7, moderatorId = null, reason = "spam ring")
        store.ban(7, moderatorId = 9, reason = "again")
        assertTrue(store.isBanned(7))
        store.unban(7, moderatorId = 9)
        store.unban(7, moderatorId = 9)
        assertFalse(store.isBanned(7))
        assertEquals(
            listOf("ban", "ban", "unban", "unban"),
            query("SELECT action FROM review_moderation_log WHERE target_user_id = 7 ORDER BY id") { it.getString(1) },
        )
    }

    // ---- ratings for repo responses and summaries ----

    @Test
    fun `ratings look GitHub repos up by id and forge repos by host and name`() = runBlocking {
        store.putReview(app, user(1), input(4))
        store.putReview(app, user(2), input(5))
        val hiddenOnly = github(300, "owner", "hidden")
        store.hide(store.putReview(hiddenOnly, user(1), input(1)).id, null, null)
        store.putReview(RepoTarget("codeberg.org", null, "Owner", "App"), user(1), input(2))

        val renamed = github(100, "someone-else", "renamed")
        val forge = RepoTarget("codeberg.org", null, "owner", "APP")
        val ratings = store.ratings(listOf(renamed, hiddenOnly, github(999, "x", "y"), forge))
        assertEquals(RatingAggregate(2, 9, listOf(0, 0, 0, 1, 1)), ratings[renamed])
        assertEquals(RatingAggregate(1, 2, listOf(0, 1, 0, 0, 0)), ratings[forge])
        assertEquals(setOf(renamed, forge), ratings.keys, "repos with no visible reviews are absent")
        assertTrue(store.ratings(emptyList()).isEmpty())
    }

    @Test
    fun `summaries resolve like the public list, in request order`() = runBlocking {
        store.putReview(github(100, "old-owner", "app"), user(1), input(4))
        sql("INSERT INTO repos (id, full_name, owner, name, html_url) VALUES (100, 'new-owner/app', 'new-owner', 'app', 'https://github.com/new-owner/app')")
        val result = store.summaries("github.com", listOf("new-owner" to "app", "nobody" to "here", "OLD-OWNER" to "App"))
        assertEquals(listOf(1, null, 1), result.map { it?.count })
        assertEquals(4.0, result[0]!!.average)
    }
}
