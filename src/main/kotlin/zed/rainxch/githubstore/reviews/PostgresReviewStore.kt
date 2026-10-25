package zed.rainxch.githubstore.reviews

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.security.SecureRandom
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.Base64

// Raw JDBC (the FeedRepository convention): the write path needs FOR UPDATE,
// ON CONFLICT on partial/expression indexes and RETURNING.
//
// Aggregate consistency: every transaction that changes which reviews are
// visible, or their ratings, first locks the repo's review_repos row, then
// applies RatingAggregate.transition to the locked values. Writes run at READ
// COMMITTED so a writer queued behind that lock sees the previous writer's
// committed rows instead of failing with a serialization error, which is what
// the pool's default REPEATABLE READ would do.
class PostgresReviewStore : ReviewStore {

    override suspend fun publicPage(
        host: String,
        owner: String,
        name: String,
        sort: ReviewSort,
        rating: Int?,
        offset: Int,
        limit: Int,
    ): ReviewPage = readTx { c ->
        val repo = if (host == RepoTarget.GITHUB_HOST) {
            // The catalog id wins over a name match, so a rename the catalog
            // already knows about still finds reviews stored under the old name.
            c.queryOne(
                """
                SELECT $AGGREGATE_COLUMNS FROM review_repos
                WHERE host = 'github.com'
                  AND (github_id = (SELECT id FROM repos WHERE full_name = ?)
                       OR (lower(owner) = lower(?) AND lower(name) = lower(?)))
                ORDER BY (github_id = (SELECT id FROM repos WHERE full_name = ?)) DESC NULLS LAST, updated_at DESC
                LIMIT 1
                """.trimIndent(),
                "$owner/$name", owner, name, "$owner/$name",
            ) { it.toRepoRow() }
        } else {
            c.queryOne(
                "SELECT $AGGREGATE_COLUMNS FROM review_repos $FORGE_MATCH",
                host, owner, name,
            ) { it.toRepoRow() }
        }
        if (repo == null) return@readTx ReviewPage(RatingAggregate(), emptyList(), hasMore = false)

        val sql = buildString {
            append(REVIEW_SELECT)
            append(" WHERE r.repo_id = ? AND r.hidden_at IS NULL")
            if (rating != null) append(" AND r.rating = ?")
            append(" ORDER BY ").append(orderBy(sort))
            append(" LIMIT ? OFFSET ?")
        }
        val args = buildList<Any> {
            add(repo.id)
            if (rating != null) add(rating)
            add(limit + 1)
            add(offset)
        }
        val rows = c.queryList(sql, *args.toTypedArray()) { it.toReview() }
        ReviewPage(repo.aggregate, rows.take(limit), hasMore = rows.size > limit)
    }

    override suspend fun mine(target: RepoTarget, userId: Long): MyReviewState = readTx { c ->
        val repo = c.findRepo(target, forUpdate = false)
            ?: return@readTx MyReviewState(review = null, votes = emptyMap())
        val review = c.queryOne("$REVIEW_SELECT WHERE r.repo_id = ? AND r.author_id = ?", repo.id, userId) { it.toReview() }
        val voted = c.queryList(
            """
            SELECT v.review_id, v.value FROM review_votes v JOIN reviews r ON r.id = v.review_id
            WHERE r.repo_id = ? AND v.voter_id = ? AND r.hidden_at IS NULL
            ORDER BY v.created_at
            """.trimIndent(),
            repo.id, userId,
        ) { it.getString(1) to it.getInt(2) }.toMap()
        MyReviewState(review, voted)
    }

    override suspend fun isBanned(userId: Long): Boolean = readTx { c ->
        c.queryOne("SELECT 1 FROM review_bans WHERE github_id = ?", userId) { true } ?: false
    }

    override suspend fun putReview(target: RepoTarget, author: GitHubUser, input: ReviewInput): Review = writeTx { c ->
        val repo = c.upsertRepo(target)
        val existing = c.queryOne(
            "SELECT id, rating, hidden_at IS NOT NULL FROM reviews WHERE repo_id = ? AND author_id = ? FOR UPDATE",
            repo.id, author.id,
        ) { ExistingReview(it.getString(1), it.getInt(2), it.getBoolean(3)) }

        val reviewId = if (existing == null) {
            val id = newReviewId()
            c.execute(
                """
                INSERT INTO reviews (id, repo_id, author_id, author_login, author_avatar_url,
                                     rating, text, version, platform, app_label)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                id, repo.id, author.id, author.login, author.avatarUrl,
                input.rating, input.text, input.version, input.platform, input.appLabel,
            )
            id
        } else {
            c.execute(
                """
                UPDATE reviews SET rating = ?, text = ?, version = ?, platform = ?, app_label = ?,
                                   author_login = ?, author_avatar_url = ?, updated_at = NOW()
                WHERE id = ?
                """.trimIndent(),
                input.rating, input.text, input.version, input.platform, input.appLabel,
                author.login, author.avatarUrl, existing.id,
            )
            existing.id
        }

        val before = existing?.takeUnless { it.hidden }?.rating
        val after = if (existing?.hidden == true) null else input.rating
        c.writeAggregate(repo.id, repo.aggregate, repo.aggregate.transition(before, after))
        c.refreshAuthorSnapshot(author)

        c.queryOne("$REVIEW_SELECT WHERE r.id = ?", reviewId) { it.toReview() }
            ?: error("review $reviewId vanished inside its own transaction")
    }

    override suspend fun deleteReview(target: RepoTarget, userId: Long): Boolean = writeTx { c ->
        val repo = c.findRepo(target, forUpdate = true) ?: return@writeTx false
        val existing = c.queryOne(
            "SELECT id, rating, hidden_at IS NOT NULL FROM reviews WHERE repo_id = ? AND author_id = ? FOR UPDATE",
            repo.id, userId,
        ) { ExistingReview(it.getString(1), it.getInt(2), it.getBoolean(3)) }
            ?: return@writeTx false
        // Votes, reports and the reply go with it (ON DELETE CASCADE).
        c.execute("DELETE FROM reviews WHERE id = ?", existing.id)
        if (!existing.hidden) {
            c.writeAggregate(repo.id, repo.aggregate, repo.aggregate.transition(existing.rating, null))
        }
        true
    }

    // ---- repo rows ----

    private data class RepoRow(val id: Long, val aggregate: RatingAggregate)

    private data class ExistingReview(val id: String, val rating: Int, val hidden: Boolean)

    private fun ResultSet.toRepoRow() = RepoRow(
        id = getLong("id"),
        aggregate = RatingAggregate(
            count = getInt("review_count"),
            sum = getInt("rating_sum"),
            stars = (1..5).map { getInt("stars_$it") },
        ),
    )

    private fun Connection.findRepo(target: RepoTarget, forUpdate: Boolean): RepoRow? {
        val lock = if (forUpdate) " FOR UPDATE" else ""
        return if (target.githubId != null) {
            queryOne("SELECT $AGGREGATE_COLUMNS FROM review_repos WHERE github_id = ?$lock", target.githubId) { it.toRepoRow() }
        } else {
            queryOne("SELECT $AGGREGATE_COLUMNS FROM review_repos $FORGE_MATCH$lock", target.host, target.owner, target.name) { it.toRepoRow() }
        }
    }

    // Creates the repo row or refreshes its last-seen owner/name. ON CONFLICT
    // DO UPDATE leaves the row locked until commit, which is the aggregate lock.
    private fun Connection.upsertRepo(target: RepoTarget): RepoRow {
        val sql = if (target.githubId != null) {
            """
            INSERT INTO review_repos (host, github_id, owner, name) VALUES ('github.com', ?, ?, ?)
            ON CONFLICT (github_id) WHERE github_id IS NOT NULL
            DO UPDATE SET owner = EXCLUDED.owner, name = EXCLUDED.name, updated_at = NOW()
            RETURNING $AGGREGATE_COLUMNS
            """.trimIndent()
        } else {
            """
            INSERT INTO review_repos (host, github_id, owner, name) VALUES (?, NULL, ?, ?)
            ON CONFLICT (host, lower(owner), lower(name)) WHERE github_id IS NULL
            DO UPDATE SET owner = EXCLUDED.owner, name = EXCLUDED.name, updated_at = NOW()
            RETURNING $AGGREGATE_COLUMNS
            """.trimIndent()
        }
        val args: Array<Any?> = if (target.githubId != null) {
            arrayOf(target.githubId, target.owner, target.name)
        } else {
            arrayOf(target.host, target.owner, target.name)
        }
        return queryOne(sql, *args) { it.toRepoRow() } ?: error("upsert returned no row")
    }

    private fun Connection.writeAggregate(repoId: Long, before: RatingAggregate, after: RatingAggregate) {
        if (after == before) return
        execute(
            """
            UPDATE review_repos SET review_count = ?, rating_sum = ?,
                   stars_1 = ?, stars_2 = ?, stars_3 = ?, stars_4 = ?, stars_5 = ?, updated_at = NOW()
            WHERE id = ?
            """.trimIndent(),
            after.count, after.sum, after.stars[0], after.stars[1], after.stars[2], after.stars[3], after.stars[4], repoId,
        )
    }

    // The login/avatar snapshot follows the author's latest write everywhere.
    // The IS DISTINCT FROM guard keeps this a no-op (no row locks) in the
    // common case where nothing changed.
    private fun Connection.refreshAuthorSnapshot(author: GitHubUser) {
        for (table in listOf("reviews", "review_replies")) {
            execute(
                """
                UPDATE $table SET author_login = ?, author_avatar_url = ?
                WHERE author_id = ? AND (author_login IS DISTINCT FROM ? OR author_avatar_url IS DISTINCT FROM ?)
                """.trimIndent(),
                author.login, author.avatarUrl, author.id, author.login, author.avatarUrl,
            )
        }
    }

    // ---- reviews ----

    private fun orderBy(sort: ReviewSort): String = when (sort) {
        ReviewSort.TOP -> "r.score DESC, r.updated_at DESC, r.id"
        ReviewSort.NEWEST -> "r.updated_at DESC, r.id"
        ReviewSort.HIGHEST -> "r.rating DESC, r.score DESC, r.updated_at DESC, r.id"
        ReviewSort.LOWEST -> "r.rating ASC, r.score DESC, r.updated_at DESC, r.id"
    }

    private fun ResultSet.toReview(): Review {
        val created = getObject("created_at", OffsetDateTime::class.java)
        val updated = getObject("updated_at", OffsetDateTime::class.java)
        val replyText = getString("reply_text")
        return Review(
            id = getString("id"),
            rating = getInt("rating"),
            text = getString("text"),
            version = getString("version"),
            platform = getString("platform"),
            appLabel = getString("app_label"),
            author = ReviewAuthor(getLong("author_id"), getString("author_login"), getString("author_avatar_url")),
            createdAt = created.wire(),
            updatedAt = updated.wire(),
            edited = updated.isAfter(created),
            score = getInt("score"),
            reply = replyText?.let {
                ReviewReply(
                    author = ReviewAuthor(
                        getLong("reply_author_id"),
                        getString("reply_author_login"),
                        getString("reply_author_avatar_url"),
                    ),
                    text = it,
                    createdAt = getObject("reply_created_at", OffsetDateTime::class.java).wire(),
                    updatedAt = getObject("reply_updated_at", OffsetDateTime::class.java).wire(),
                )
            },
            hidden = getObject("hidden_at") != null,
        )
    }

    private fun OffsetDateTime.wire(): String = toInstant().truncatedTo(ChronoUnit.SECONDS).toString()

    private fun newReviewId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return "rv_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    // ---- JDBC plumbing ----

    private suspend fun <T> readTx(block: (Connection) -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block(jdbc()) }

    private suspend fun <T> writeTx(block: (Connection) -> T): T =
        newSuspendedTransaction(Dispatchers.IO, transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) { block(jdbc()) }

    private fun jdbc(): Connection = TransactionManager.current().connection.connection as Connection

    private fun PreparedStatement.bind(args: Array<out Any?>) {
        args.forEachIndexed { i, arg ->
            when (arg) {
                null -> setNull(i + 1, Types.VARCHAR)
                is String -> setString(i + 1, arg)
                is Int -> setInt(i + 1, arg)
                is Long -> setLong(i + 1, arg)
                else -> setObject(i + 1, arg)
            }
        }
    }

    private fun <T> Connection.queryOne(sql: String, vararg args: Any?, map: (ResultSet) -> T): T? =
        prepareStatement(sql).use { st ->
            st.bind(args)
            st.executeQuery().use { rs -> if (rs.next()) map(rs) else null }
        }

    private fun <T> Connection.queryList(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
        prepareStatement(sql).use { st ->
            st.bind(args)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(map(rs)) }
            }
        }

    private fun Connection.execute(sql: String, vararg args: Any?): Int =
        prepareStatement(sql).use { st ->
            st.bind(args)
            st.executeUpdate()
        }

    private companion object {
        val random = SecureRandom()

        const val AGGREGATE_COLUMNS =
            "id, review_count, rating_sum, stars_1, stars_2, stars_3, stars_4, stars_5"

        const val FORGE_MATCH =
            "WHERE github_id IS NULL AND host = ? AND lower(owner) = lower(?) AND lower(name) = lower(?)"

        val REVIEW_SELECT = """
            SELECT r.id, r.rating, r.text, r.version, r.platform, r.app_label,
                   r.author_id, r.author_login, r.author_avatar_url,
                   r.created_at, r.updated_at, r.score, r.hidden_at,
                   rp.author_id AS reply_author_id, rp.author_login AS reply_author_login,
                   rp.author_avatar_url AS reply_author_avatar_url, rp.text AS reply_text,
                   rp.created_at AS reply_created_at, rp.updated_at AS reply_updated_at
            FROM reviews r LEFT JOIN review_replies rp ON rp.review_id = r.id
        """.trimIndent()
    }
}
