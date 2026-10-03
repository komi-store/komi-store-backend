package zed.rainxch.githubstore.ingest

import zed.rainxch.githubstore.model.PlatformRelease
import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformAvailabilityTest {

    private fun release(
        tag: String,
        publishedAt: String,
        vararg assets: String,
        prerelease: Boolean = false,
    ) = GitHubRelease(
        tagName = tag,
        publishedAt = publishedAt,
        prerelease = prerelease,
        assets = assets.map { GitHubAsset(name = it) },
    )

    private val desktop = arrayOf(
        "notesnook_win_x64.exe",
        "notesnook_mac_arm64.dmg",
        "notesnook_linux_x86_64.AppImage",
    )

    @Test
    fun platforms_from_older_releases_count_when_the_newest_skips_them() {
        val flags = PlatformAvailability.flags(
            listOf(
                release("3.4.13-android", "2026-09-15T10:00:00Z", "notesnook-arm64-v8a.apk"),
                release("v3.4.8", "2026-09-14T10:00:00Z", *desktop),
                release("3.4.7-android", "2026-09-01T10:00:00Z", "notesnook-arm64-v8a.apk"),
            ),
        )
        assertEquals(
            mapOf("android" to true, "windows" to true, "macos" to true, "linux" to true),
            flags,
        )
    }

    @Test
    fun a_platform_older_than_a_year_behind_the_newest_release_is_dropped() {
        val flags = PlatformAvailability.flags(
            listOf(
                release("v2.0.0", "2026-09-01T00:00:00Z", "app-release.apk"),
                release("v1.0.0", "2025-08-01T00:00:00Z", "app-setup.exe"),
            ),
        )
        assertEquals(true, flags["android"])
        assertEquals(false, flags["windows"])
    }

    @Test
    fun a_stale_repo_keeps_platforms_measured_against_its_own_newest_release() {
        val flags = PlatformAvailability.flags(
            listOf(
                release("v1.1.0", "2021-03-01T00:00:00Z", "app-release.apk"),
                release("v1.0.0", "2020-06-01T00:00:00Z", "app-setup.exe"),
            ),
        )
        assertEquals(true, flags["android"])
        assertEquals(true, flags["windows"])
    }

    @Test
    fun pre_releases_and_drafts_are_ignored() {
        val flags = PlatformAvailability.flags(
            listOf(
                release("v2.3.0-beta", "2026-09-10T00:00:00Z", *desktop, prerelease = true),
                GitHubRelease(tagName = "v2.2.0", draft = true, assets = listOf(GitHubAsset("app-setup.exe"))),
                release("v2.1.0", "2026-08-01T00:00:00Z", "app-release.apk"),
            ),
        )
        assertEquals(
            mapOf("android" to true, "windows" to false, "macos" to false, "linux" to false),
            flags,
        )
    }

    @Test
    fun alpine_apks_in_older_releases_do_not_count_as_android() {
        val flags = PlatformAvailability.flags(
            listOf(
                release("v1.2.0", "2026-09-01T00:00:00Z", "tool_1.2.0_linux_amd64.deb"),
                release("v1.1.0", "2026-08-01T00:00:00Z", "tool_1.1.0_linux_amd64.apk"),
            ),
        )
        assertEquals(false, flags["android"])
        assertEquals(true, flags["linux"])
    }

    @Test
    fun each_platform_carries_the_tag_and_date_of_its_own_newest_build() {
        val releases = PlatformAvailability.newestReleases(
            listOf(
                release("3.4.13-android", "2026-09-15T10:00:00Z", "notesnook-arm64-v8a.apk"),
                release("v3.4.8", "2026-09-14T10:00:00Z", *desktop),
                release("v3.4.7", "2026-09-01T10:00:00Z", "notesnook-arm64-v8a.apk", *desktop),
            ),
        )
        assertEquals(PlatformRelease("3.4.13-android", "2026-09-15T10:00:00Z"), releases["android"])
        assertEquals(PlatformRelease("v3.4.8", "2026-09-14T10:00:00Z"), releases["windows"])
        assertEquals(PlatformRelease("v3.4.8", "2026-09-14T10:00:00Z"), releases["linux"])
    }

    @Test
    fun a_dropped_platform_has_no_release_entry() {
        val releases = PlatformAvailability.newestReleases(
            listOf(
                release("v2.0.0", "2026-09-01T00:00:00Z", "app-release.apk"),
                release("v1.0.0", "2025-08-01T00:00:00Z", "app-setup.exe"),
            ),
        )
        assertEquals(setOf("android"), releases.keys)
    }

    @Test
    fun no_stable_release_means_no_platforms() {
        val flags = PlatformAvailability.flags(
            listOf(release("v1.0.0-rc1", "2026-09-01T00:00:00Z", "app-release.apk", prerelease = true)),
        )
        assertEquals(false, flags.values.any { it })
    }
}
