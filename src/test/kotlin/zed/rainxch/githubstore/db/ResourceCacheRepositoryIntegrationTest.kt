package zed.rainxch.githubstore.db

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assume.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Runs ResourceCacheRepository.sweepStale's batched ctid DELETE against a real
// Postgres: only rows past the cutoff go, and the batch loop ends whether the
// last batch is partial or exactly full.
//
// Database: a throwaway Testcontainers Postgres when Docker is available
// (CI). Without Docker, set TEST_DATABASE_URL (and optionally
// TEST_DATABASE_USER / TEST_DATABASE_PASSWORD, default githubstore) to a
// DISPOSABLE database: the test truncates resource_cache. With neither, it
// reports SKIPPED.
class ResourceCacheRepositoryIntegrationTest {

    private val repository = ResourceCacheRepository()

    @BeforeTest
    fun setUp() {
        assumeTrue("Neither Docker nor TEST_DATABASE_URL available, skipping", database != null)
        sql("TRUNCATE resource_cache")
    }

    private companion object {
        // One database for the whole class, connected on first use; the
        // container is left to Testcontainers' reaper when the test JVM exits.
        // sweepStale runs on Dispatchers.IO threads, and Exposed caches its
        // transaction manager per thread (see PostgresReviewStoreIntegrationTest).
        val database: Database? by lazy {
            val externalUrl = System.getenv("TEST_DATABASE_URL")?.takeIf { it.isNotBlank() }
            val docker = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
            if (!docker && externalUrl == null) return@lazy null
            val (url, user, password) = if (docker) {
                val container = PostgreSQLContainer("postgres:17-alpine").apply { start() }
                Triple(container.jdbcUrl, container.username, container.password)
            } else {
                Triple(
                    externalUrl!!,
                    System.getenv("TEST_DATABASE_USER") ?: "githubstore",
                    System.getenv("TEST_DATABASE_PASSWORD") ?: "githubstore",
                )
            }
            Database.connect(url = url, driver = "org.postgresql.Driver", user = user, password = password)
                .also { DatabaseFactory.runMigrations() }
        }
    }

    private fun sql(statement: String) = transaction {
        (TransactionManager.current().connection.connection as java.sql.Connection).createStatement().use { it.execute(statement) }
    }

    private fun insert(key: String, fetchedDaysAgo: Int) = sql(
        """
        INSERT INTO resource_cache (cache_key, body, status, fetched_at, expires_at)
        VALUES ('$key', '{}', 200, NOW() - INTERVAL '$fetchedDaysAgo days', NOW() - INTERVAL '$fetchedDaysAgo days' + INTERVAL '1 hour')
        """.trimIndent(),
    )

    private fun remainingKeys(): List<String> = transaction {
        (TransactionManager.current().connection.connection as java.sql.Connection).createStatement().use { st ->
            st.executeQuery("SELECT cache_key FROM resource_cache ORDER BY cache_key").use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }
    }

    @Test
    fun `sweepStale deletes only rows past the cutoff, across several batches`() = runBlocking {
        (1..5).forEach { insert("stale-$it", fetchedDaysAgo = 40) }
        insert("fresh-29d", fetchedDaysAgo = 29)
        insert("fresh-1d", fetchedDaysAgo = 1)

        val swept = repository.sweepStale(olderThanDays = 30, batchSize = 2)

        assertEquals(5, swept)
        assertEquals(listOf("fresh-1d", "fresh-29d"), remainingKeys())
    }

    @Test
    fun `sweepStale ends when the last batch is exactly full`() = runBlocking {
        (1..4).forEach { insert("stale-$it", fetchedDaysAgo = 31) }

        val swept = repository.sweepStale(olderThanDays = 30, batchSize = 2)

        assertEquals(4, swept)
        assertEquals(emptyList<String>(), remainingKeys())
    }
}
