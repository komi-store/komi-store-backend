package zed.rainxch.githubstore.reviews

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RatingAggregateTest {

    private fun agg(vararg ratings: Int) =
        ratings.fold(RatingAggregate()) { acc, r -> acc.transition(null, r) }

    @Test
    fun `create adds to count, sum and the right bucket`() {
        val a = agg(5, 4, 5)
        assertEquals(3, a.count)
        assertEquals(14, a.sum)
        assertEquals(listOf(0, 0, 0, 1, 2), a.stars)
    }

    @Test
    fun `edit moves one review between buckets without changing the count`() {
        val a = agg(5, 1).transition(1, 3)
        assertEquals(2, a.count)
        assertEquals(8, a.sum)
        assertEquals(listOf(0, 0, 1, 0, 1), a.stars)
    }

    @Test
    fun `delete and hide remove, unhide restores`() {
        val a = agg(5, 2)
        val hidden = a.transition(2, null)
        assertEquals(RatingAggregate(1, 5, listOf(0, 0, 0, 0, 1)), hidden)
        assertEquals(a, hidden.transition(null, 2))
    }

    @Test
    fun `editing a hidden review leaves the aggregate alone`() {
        val a = agg(4)
        assertEquals(a, a.transition(null, null))
    }

    @Test
    fun `average is rounded to one decimal and null when empty`() {
        assertNull(RatingAggregate().average)
        assertEquals(4.3, agg(5, 4, 4).average)
        assertEquals(4.7, agg(5, 5, 4).average)
    }

    @Test
    fun `summary histogram always has all five keys`() {
        val summary = agg(5).toSummary()
        assertEquals(mapOf("1" to 0, "2" to 0, "3" to 0, "4" to 0, "5" to 1), summary.histogram)
        assertEquals(1, summary.count)
        assertEquals(5.0, summary.average)
    }
}
