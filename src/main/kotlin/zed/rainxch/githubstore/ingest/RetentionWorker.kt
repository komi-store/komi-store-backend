package zed.rainxch.githubstore.ingest

import io.sentry.Sentry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import zed.rainxch.githubstore.db.ResourceCacheRepository
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Daily retention sweep — drops events older than the 90-day window the rest
 * of the pipeline cares about. The aggregation worker already only looks back
 * 30 days; older rows are dead weight.
 *
 * Also sweeps resource_cache rows not fetched or revalidated in 30 days
 * (ResourceCacheRepository.sweepStale). Unswept, that cache grew to 22 GB and
 * filled the production disk through the nightly pg_dump.
 *
 * Postgres rejects LIMIT directly on DELETE, so we go through a CTID
 * subquery and loop in 10k-row chunks until a chunk deletes fewer rows than
 * the cap. Bounded chunks keep the WAL spike per cycle small and let the
 * advisory-lock release between iterations.
 */
class RetentionWorker(
    private val resourceCache: ResourceCacheRepository,
    private val supervisor: WorkerSupervisor? = null,
) {
    private val log = LoggerFactory.getLogger(RetentionWorker::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val startupDelay = 20.minutes
    private val cycleInterval = 24.hours
    private val chunkSize = 10_000
    private val retentionDays = 90
    private val resourceCacheRetentionDays = 30L

    private val advisoryLockId: Long = 911_003L

    fun start(): Job = scope.launch {
        delay(startupDelay)
        while (true) {
            try {
                if (tryRunCycle()) {
                    supervisor?.recordTick(WORKER_NAME)
                }
            } catch (e: Exception) {
                log.error("Retention cycle failed", e)
                Sentry.captureException(e)
            }
            delay(cycleInterval)
        }
    }.also { supervisor?.register(WORKER_NAME, it) }

    private suspend fun tryRunCycle(): Boolean {
        if (!acquireAdvisoryLock()) {
            log.info("Retention skipped: advisory lock held by another instance")
            return false
        }
        try {
            runCycle()
            return true
        } finally {
            releaseAdvisoryLock()
        }
    }

    suspend fun runCycle() {
        val eventsSwept = sweep("events")
        val cacheSwept = resourceCache.sweepStale(olderThanDays = resourceCacheRetentionDays)
        log.info(
            "Retention cycle done: events={} (>{}d), resource_cache={} (>{}d)",
            eventsSwept, retentionDays, cacheSwept, resourceCacheRetentionDays,
        )
    }

    private fun sweep(table: String): Long {
        var total = 0L
        while (true) {
            val deleted = deleteChunk(table)
            total += deleted
            if (deleted < chunkSize) break
        }
        return total
    }

    private fun deleteChunk(table: String): Int = transaction {
        val conn = TransactionManager.current().connection.connection as java.sql.Connection
        val sql = """
            DELETE FROM $table
            WHERE ctid IN (
                SELECT ctid FROM $table
                WHERE ts < NOW() - INTERVAL '$retentionDays days'
                LIMIT $chunkSize
            )
        """.trimIndent()
        conn.prepareStatement(sql).use { ps -> ps.executeUpdate() }
    }

    private fun acquireAdvisoryLock(): Boolean = transaction {
        val conn = TransactionManager.current().connection.connection as java.sql.Connection
        conn.prepareStatement("SELECT pg_try_advisory_lock(?)").use { ps ->
            ps.setLong(1, advisoryLockId)
            ps.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
        }
    }

    private fun releaseAdvisoryLock() {
        try {
            transaction {
                val conn = TransactionManager.current().connection.connection as java.sql.Connection
                conn.prepareStatement("SELECT pg_advisory_unlock(?)").use { ps ->
                    ps.setLong(1, advisoryLockId)
                    ps.executeQuery().close()
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to release advisory lock {}: {}", advisoryLockId, e.message)
        }
    }

    companion object {
        const val WORKER_NAME = "retention"
    }
}
