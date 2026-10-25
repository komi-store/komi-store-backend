package zed.rainxch.githubstore.reviews

import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import zed.rainxch.githubstore.util.FeatureFlags
import java.time.Instant

data class GitHubUser(
    val id: Long,
    val login: String,
    val avatarUrl: String?,
    val createdAt: Instant,
)

// A public GitHub repo as the caller sees it. canPush comes from the
// `permissions` object GitHub adds to authenticated repo responses.
data class GitHubRepoAccess(
    val id: Long,
    val owner: String,
    val name: String,
    val canPush: Boolean,
)

// GitHub calls made with the caller's own X-GitHub-Token on the review write
// path. Never the rotation pool: identity and push access are per user. The
// token goes into the Authorization header and nowhere else (no logs, no
// cache keys; ReviewIdentity keys its cache by an HMAC of it).
class GitHubReviewClient(
    // Tests pass a MockEngine; production uses CIO.
    private val engine: HttpClientEngine? = null,
) {
    sealed interface UserResult {
        data class Ok(val user: GitHubUser) : UserResult
        data object Invalid : UserResult
        data object Unavailable : UserResult
    }

    sealed interface RepoResult {
        data class Ok(val repo: GitHubRepoAccess) : RepoResult
        data object NotFound : RepoResult
        data object Invalid : RepoResult
        data object Unavailable : RepoResult
    }

    private val log = LoggerFactory.getLogger(GitHubReviewClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    // Lazy so the CIO engine only spawns on the first authed review call.
    private val http: HttpClient by lazy {
        HttpClient(engine ?: CIO.create()) {
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 10_000
            }
            expectSuccess = false
        }
    }

    suspend fun fetchUser(token: String): UserResult {
        val response = get("https://api.github.com/user", token) ?: return UserResult.Unavailable
        return when (response.status.value) {
            200 -> parse(response, UserDto.serializer())?.let { dto ->
                val createdAt = runCatching { Instant.parse(dto.createdAt) }.getOrNull()
                    ?: return UserResult.Unavailable
                UserResult.Ok(GitHubUser(dto.id, dto.login, dto.avatarUrl, createdAt))
            } ?: UserResult.Unavailable
            401 -> UserResult.Invalid
            else -> {
                log.info("GitHub /user returned {}", response.status.value)
                UserResult.Unavailable
            }
        }
    }

    suspend fun fetchRepo(token: String, owner: String, name: String): RepoResult =
        repoAt("https://api.github.com/repos/$owner/$name", token)

    // By numeric id, so a repo renamed since its reviews were written still resolves.
    suspend fun fetchRepoById(token: String, id: Long): RepoResult =
        repoAt("https://api.github.com/repositories/$id", token)

    private suspend fun repoAt(url: String, token: String): RepoResult {
        val response = get(url, token) ?: return RepoResult.Unavailable
        val status = response.status.value
        return when {
            status == 200 -> {
                val dto = parse(response, RepoDto.serializer()) ?: return RepoResult.Unavailable
                // Reviews are public, so a repo the public can't see takes none.
                if (dto.private) {
                    RepoResult.NotFound
                } else {
                    RepoResult.Ok(GitHubRepoAccess(dto.id, dto.owner.login, dto.name, dto.permissions?.push == true))
                }
            }
            status == 401 -> RepoResult.Invalid
            isRateLimited(status, response.headers) || status >= 500 -> RepoResult.Unavailable
            // 404, 451 (DMCA) and a bare 403 (blocked / SSO-gated repo).
            status == 404 || status == 451 || status == 403 -> RepoResult.NotFound
            else -> {
                log.info("GitHub /repos returned {}", status)
                RepoResult.Unavailable
            }
        }
    }

    private suspend fun get(url: String, token: String): HttpResponse? {
        // Same kill switch as every other outbound GitHub call.
        if (FeatureFlags.disableLiveGitHubPassthrough) return null
        return try {
            http.get(url) {
                header(HttpHeaders.Accept, "application/vnd.github+json")
                header(HttpHeaders.UserAgent, "GithubStoreBackend/1.0 (Reviews)")
                header(HttpHeaders.Authorization, "token $token")
            }
        } catch (e: Exception) {
            log.warn("GitHub review call failed: {}", e.javaClass.simpleName)
            null
        }
    }

    private suspend fun <T> parse(response: HttpResponse, serializer: kotlinx.serialization.KSerializer<T>): T? =
        try {
            json.decodeFromString(serializer, response.bodyAsText())
        } catch (e: Exception) {
            log.warn("GitHub review response unparseable: {}", e.javaClass.simpleName)
            null
        }

    // Same markers GitHubResourceClient uses: 429, or 403 with
    // x-ratelimit-remaining: 0 or a retry-after header.
    private fun isRateLimited(status: Int, headers: Headers): Boolean =
        status == 429 || (status == 403 && (headers["x-ratelimit-remaining"] == "0" || headers["retry-after"] != null))

    @Serializable
    private data class UserDto(
        val id: Long,
        val login: String,
        @SerialName("avatar_url") val avatarUrl: String? = null,
        @SerialName("created_at") val createdAt: String,
    )

    @Serializable
    private data class RepoDto(
        val id: Long,
        val name: String,
        val owner: OwnerDto,
        val private: Boolean = false,
        val permissions: PermissionsDto? = null,
    )

    @Serializable
    private data class OwnerDto(val login: String)

    @Serializable
    private data class PermissionsDto(val push: Boolean = false)
}
