package zed.rainxch.githubstore.db

import kotlinx.serialization.json.Json
import zed.rainxch.githubstore.model.PlatformRelease

// repos.platform_releases is TEXT holding the RepoResponse.platformReleases
// JSON verbatim. The fetcher's db_writer writes the same shape via json.dumps.
// A row written before V22 (or by a fetcher without the field) reads as null.
internal object PlatformReleasesColumn {

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(value: Map<String, PlatformRelease>): String? =
        value.takeIf { it.isNotEmpty() }?.let { json.encodeToString(it) }

    fun decode(value: String?): Map<String, PlatformRelease>? =
        value?.let { runCatching { json.decodeFromString<Map<String, PlatformRelease>>(it) }.getOrNull() }
}
