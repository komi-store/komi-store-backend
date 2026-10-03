package zed.rainxch.githubstore.ingest

import zed.rainxch.githubstore.util.AssetPlatform
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

// Which platforms a repo ships installers for, across its stable releases
// rather than only the newest one. Repos that publish desktop and mobile builds
// in separate releases (notesnook `v3.4.8` desktop vs `3.4.13-android`, ente
// `photos-v*` vs `auth-v*`) would otherwise lose a platform every time the other
// one releases. A platform whose newest build is more than STALE_PLATFORM_DAYS
// older than the repo's newest stable release counts as dropped.
//
// MUST match the fetcher's `platform_availability` in komi-store-backend-data
// (`fetch_all_categories.py`): both writers overwrite the same four flags.
internal object PlatformAvailability {

    const val STALE_PLATFORM_DAYS = 365L

    private val PLATFORMS = listOf("android", "windows", "macos", "linux")

    // `releases` newest-first, as GitHub's /releases returns them.
    fun flags(releases: List<GitHubRelease>): Map<String, Boolean> {
        val stable = releases.filter { !it.draft && !it.prerelease }
        val newestByPlatform = mutableMapOf<String, GitHubRelease>()
        for (release in stable) {
            AssetPlatform.installFlags(release.assets.map { it.name }).forEach { (platform, ships) ->
                if (ships) newestByPlatform.putIfAbsent(platform, release)
            }
        }
        val latestDate = stable.firstOrNull()?.publishedAt?.let(::parseDate)
        return PLATFORMS.associateWith { platform ->
            val release = newestByPlatform[platform] ?: return@associateWith false
            val date = release.publishedAt?.let(::parseDate)
            latestDate == null || date == null ||
                ChronoUnit.DAYS.between(date, latestDate) <= STALE_PLATFORM_DAYS
        }
    }

    private fun parseDate(value: String): OffsetDateTime? =
        try { OffsetDateTime.parse(value) } catch (_: Exception) { null }
}
