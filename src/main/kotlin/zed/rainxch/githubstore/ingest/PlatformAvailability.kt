package zed.rainxch.githubstore.ingest

import zed.rainxch.githubstore.model.PlatformRelease
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
// (`fetch_all_categories.py`): both writers overwrite the same four flags and
// the same platform_releases column.
internal object PlatformAvailability {

    const val STALE_PLATFORM_DAYS = 365L

    val PLATFORMS = listOf("android", "windows", "macos", "linux")

    // Newest stable release per available platform. `releases` newest-first,
    // as GitHub's /releases returns them.
    fun newestReleases(releases: List<GitHubRelease>): Map<String, PlatformRelease> {
        val stable = releases.filter { !it.draft && !it.prerelease }
        val newestByPlatform = mutableMapOf<String, GitHubRelease>()
        for (release in stable) {
            AssetPlatform.installFlags(release.assets.map { it.name }).forEach { (platform, ships) ->
                if (ships) newestByPlatform.putIfAbsent(platform, release)
            }
        }
        val latestDate = stable.firstOrNull()?.publishedAt?.let(::parseDate)
        return PLATFORMS.mapNotNull { platform ->
            val release = newestByPlatform[platform] ?: return@mapNotNull null
            val date = release.publishedAt?.let(::parseDate)
            val stale = latestDate != null && date != null &&
                ChronoUnit.DAYS.between(date, latestDate) > STALE_PLATFORM_DAYS
            if (stale) null else platform to PlatformRelease(release.tagName, release.publishedAt)
        }.toMap()
    }

    fun flags(releases: List<GitHubRelease>): Map<String, Boolean> = flagsOf(newestReleases(releases))

    fun flagsOf(platformReleases: Map<String, PlatformRelease>): Map<String, Boolean> =
        PLATFORMS.associateWith { it in platformReleases }

    private fun parseDate(value: String): OffsetDateTime? =
        try { OffsetDateTime.parse(value) } catch (_: Exception) { null }
}
