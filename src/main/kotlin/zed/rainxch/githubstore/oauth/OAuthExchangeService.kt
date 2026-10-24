package zed.rainxch.githubstore.oauth

import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// GitHub's primary rate limit advertises its remaining budget in this header
// while still answering 403. Ktor has no constant for it (it has one for
// Retry-After), so keep the literal in one place.
private const val RATE_LIMIT_REMAINING_HEADER = "X-RateLimit-Remaining"

// Fallback back-off when GitHub rate-limits us without a usable Retry-After.
// Primary-limit windows are hourly; the website/app treat 10 minutes as the
// safe retry horizon and back off harder on the next 429.
private const val DEFAULT_RETRY_AFTER_SECONDS = 600L

// Server-side half of the GitHub OAuth web flow. Holds the client_secret —
// the whole point of having a backend in the mix. Client never sees it.
open class OAuthExchangeService(
    private val clientId: String,
    private val clientSecret: String,
    private val callbackUrl: String,
    // Tests pass a MockEngine; production uses CIO.
    private val engine: HttpClientEngine? = null,
) {

    // Lazy so the CIO engine + non-daemon selector threads only spawn on
    // first /v1/oauth/exchange call.
    private val http: HttpClient by lazy {
        HttpClient(engine ?: CIO.create()) {
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 10_000
            }
            // Read GitHub's error bodies verbatim so we can forward the upstream
            // error code; otherwise ktor would throw on non-2xx.
            expectSuccess = false
        }
    }

    private val responseJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Exchange GitHub authorization code for an access token.
     *
     * Returns one of:
     *   - Success(accessToken)            — GitHub returned 2xx with a token
     *   - UpstreamError(errorCode)        — GitHub rejected the exchange with a
     *                                       documented `error` code (2xx body or
     *                                       a 400 response)
     *   - RateLimited(retryAfterSeconds)  — GitHub throttled us (429, or 403
     *                                       with `x-ratelimit-remaining: 0` /
     *                                       `retry-after`); callers should retry
     *                                       after the advertised delay
     *   - UpstreamFailure                 — GitHub 5xx / timeout / network error
     *
     * The handler maps Success → mint handoff, UpstreamError → 400 with the
     * upstream error code, RateLimited → 429 + Retry-After,
     * UpstreamFailure → 502.
     */
    open suspend fun exchange(code: String): Result {
        val response = try {
            http.post("https://github.com/login/oauth/access_token") {
                accept(ContentType.Application.Json)
                header(HttpHeaders.UserAgent, "GithubStoreBackend/1.0 (OAuthExchange)")
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(FormDataContent(Parameters.build {
                    append("client_id", clientId)
                    append("client_secret", clientSecret)
                    append("code", code)
                    append("redirect_uri", callbackUrl)
                }))
            }
        } catch (_: Exception) {
            return Result.UpstreamFailure
        }

        if (!response.status.isSuccess()) {
            // Check throttling before the generic failure: a rate-limited
            // exchange is retryable after a known delay, a plain 401/403
            // (bad client credentials, revoked app) is not.
            if (isRateLimited(response.status, response.headers)) {
                return Result.RateLimited(retryAfterSeconds(response.headers))
            }
            // GitHub's documented OAuth rejections normally arrive as a 2xx
            // body with an `error` field; some gateway/proxy paths answer 400
            // instead. Map those the same way so the caller still gets the
            // actionable github_<code> contract instead of an opaque 502.
            // Only the `error` field is read — the raw body never leaves this
            // function.
            if (response.status == HttpStatusCode.BadRequest) {
                readErrorCode(response)?.let { return Result.UpstreamError(it) }
            }
            return Result.UpstreamFailure
        }

        val body = try {
            response.bodyAsText()
        } catch (_: Exception) {
            return Result.UpstreamFailure
        }

        val parsed = try {
            responseJson.decodeFromString(TokenResponse.serializer(), body)
        } catch (_: Exception) {
            return Result.UpstreamFailure
        }

        return when {
            parsed.access_token != null -> Result.Success(parsed.access_token)
            parsed.error != null -> Result.UpstreamError(parsed.error)
            else -> Result.UpstreamFailure
        }
    }

    // Reads only the `error` field off a non-2xx GitHub body. Returns null when
    // the body isn't the JSON shape we understand, so the caller falls back to
    // UpstreamFailure rather than surfacing anything upstream-controlled.
    private suspend fun readErrorCode(response: HttpResponse): String? {
        val body = try {
            response.bodyAsText()
        } catch (_: Exception) {
            return null
        }
        return try {
            responseJson.decodeFromString(TokenResponse.serializer(), body).error
        } catch (_: Exception) {
            null
        }
    }

    // GitHub rate-limit detection, aligned with GitHubResourceClient's:
    //   * primary limit — 403 with `x-ratelimit-remaining: 0`
    //   * secondary/abuse limit — 429 (newer responses) or 403 with
    //     `retry-after` (older responses)
    // A bare 403 without either marker is an auth/scope problem, not a
    // throttle, and must stay a non-retryable failure.
    private fun isRateLimited(status: HttpStatusCode, headers: Headers): Boolean {
        if (status == HttpStatusCode.TooManyRequests) return true
        if (status != HttpStatusCode.Forbidden) return false
        return headers[RATE_LIMIT_REMAINING_HEADER]?.trim() == "0" ||
            headers[HttpHeaders.RetryAfter] != null
    }

    // Retry-After is defined as either delta-seconds or an HTTP-date. GitHub
    // only sends delta-seconds; an HTTP-date (or garbage) falls back to the
    // safe default rather than inventing a parse error on an error path.
    private fun retryAfterSeconds(headers: Headers): Long =
        headers[HttpHeaders.RetryAfter]
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?: DEFAULT_RETRY_AFTER_SECONDS

    // Same DTO trick as AuthRoutes.kt: only declare the fields we need to
    // read. access_token + error are the entire surface; access_token never
    // becomes a logged-or-persisted value beyond the in-memory `Success`
    // wrapper that immediately enters Redis-equivalent storage with a 60s
    // TTL.
    @Serializable
    private data class TokenResponse(
        val access_token: String? = null,
        val error: String? = null,
    )

    sealed class Result {
        data class Success(val accessToken: String) : Result()
        data class UpstreamError(val errorCode: String) : Result()

        // GitHub is throttling the exchange. retryAfterSeconds is always > 0;
        // the handler forwards it verbatim as the Retry-After header.
        data class RateLimited(val retryAfterSeconds: Long) : Result()
        data object UpstreamFailure : Result()
    }
}
