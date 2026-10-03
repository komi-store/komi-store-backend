package zed.rainxch.githubstore.db

import zed.rainxch.githubstore.model.PlatformRelease
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlatformReleasesColumnTest {

    @Test
    fun round_trips_through_the_column() {
        val value = mapOf(
            "android" to PlatformRelease("3.4.13-android", "2026-09-15T10:00:00Z"),
            "windows" to PlatformRelease("v3.4.8", "2026-09-14T10:00:00Z"),
        )
        assertEquals(value, PlatformReleasesColumn.decode(PlatformReleasesColumn.encode(value)))
    }

    @Test
    fun reads_the_fetcher_json_dumps_shape() {
        val written = """{"windows": {"tag": "v3.4.8", "publishedAt": "2026-09-14T10:00:00Z"}}"""
        assertEquals(
            mapOf("windows" to PlatformRelease("v3.4.8", "2026-09-14T10:00:00Z")),
            PlatformReleasesColumn.decode(written),
        )
    }

    @Test
    fun empty_writes_null_and_null_or_garbage_reads_null() {
        assertNull(PlatformReleasesColumn.encode(emptyMap()))
        assertNull(PlatformReleasesColumn.decode(null))
        assertNull(PlatformReleasesColumn.decode("not json"))
    }
}
