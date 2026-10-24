package zed.rainxch.githubstore.oauth

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

// Unit-level contract for OAuthExchangeService's classification of GitHub's
// responses. The route-level test (OAuthExchangeErrorContractTest) covers the
// status/body/header mapping; this file pins the classification itself,
// including the rate-limit marker precedence and the Retry-After fallbacks.
class OAuthExchangeServiceTest {

    private fun service(
        status: HttpStatusCode,
        body: String = "",
        headers: Map<String, String> = emptyMap(),
    ): OAuthExchangeService = OAuthExchangeService(
        clientId = "test-client-id",
        clientSecret = "test-client-secret",
        callbackUrl = "https://test/callback",
        engine = MockEngine {
            respond(
                content = body,
                status = status,
                headers = headerPairs(headers),
            )
        },
    )

    private fun headerPairs(headers: Map<String, String>): Headers =
        headersOf(*headers.map { it.key to listOf(it.value) }.toTypedArray())

    // ---------- rate limiting ----------

    @Test
    fun `429 with Retry-After is RateLimited with the advertised delay`() = runBlocking {
        val result = service(
            HttpStatusCode.TooManyRequests,
            """{"message":"API rate limit exceeded"}""",
            mapOf(HttpHeaders.RetryAfter to "42"),
        ).exchange("gh-code")

        assertEquals(42L, assertIs<OAuthExchangeService.Result.RateLimited>(result).retryAfterSeconds)
    }

    @Test
    fun `429 without Retry-After falls back to the 600s default`() = runBlocking {
        val result = service(
            HttpStatusCode.TooManyRequests,
            """{"message":"You have exceeded a secondary rate limit"}""",
        ).exchange("gh-code")

        assertEquals(600L, assertIs<OAuthExchangeService.Result.RateLimited>(result).retryAfterSeconds)
    }

    @Test
    fun `403 with x-ratelimit-remaining 0 is RateLimited and defaults to 600s`() = runBlocking {
        val result = service(
            HttpStatusCode.Forbidden,
            """{"message":"API rate limit exceeded for 1.2.3.4"}""",
            mapOf("X-RateLimit-Remaining" to "0"),
        ).exchange("gh-code")

        assertEquals(600L, assertIs<OAuthExchangeService.Result.RateLimited>(result).retryAfterSeconds)
    }

    @Test
    fun `403 with retry-after is a secondary rate limit and keeps the delay`() = runBlocking {
        val result = service(
            HttpStatusCode.Forbidden,
            """{"message":"You have exceeded a secondary rate limit"}""",
            mapOf(HttpHeaders.RetryAfter to "90"),
        ).exchange("gh-code")

        assertEquals(90L, assertIs<OAuthExchangeService.Result.RateLimited>(result).retryAfterSeconds)
    }

    @Test
    fun `403 without rate-limit markers stays UpstreamFailure`() = runBlocking {
        // A bare 403 is an auth/scope problem (bad client credentials, app
        // suspended); telling the caller to retry after 10 minutes would be
        // wrong.
        val result = service(
            HttpStatusCode.Forbidden,
            """{"message":"Resource not accessible"}""",
            mapOf("X-RateLimit-Remaining" to "4999"),
        ).exchange("gh-code")

        assertEquals(OAuthExchangeService.Result.UpstreamFailure, result)
    }

    @Test
    fun `unparsable Retry-After falls back to the 600s default`() = runBlocking {
        val result = service(
            HttpStatusCode.TooManyRequests,
            "",
            mapOf(HttpHeaders.RetryAfter to "Wed, 21 Oct 2015 07:28:00 GMT"),
        ).exchange("gh-code")

        assertEquals(600L, assertIs<OAuthExchangeService.Result.RateLimited>(result).retryAfterSeconds)
    }

    // ---------- GitHub error codes ----------

    @Test
    fun `400 bad_verification_code maps to UpstreamError`() = runBlocking {
        val result = service(
            HttpStatusCode.BadRequest,
            """{"error":"bad_verification_code","error_description":"The code passed is incorrect or expired."}""",
        ).exchange("gh-code")

        assertEquals("bad_verification_code", assertIs<OAuthExchangeService.Result.UpstreamError>(result).errorCode)
    }

    @Test
    fun `400 with a non-JSON body stays UpstreamFailure and carries nothing upstream`() = runBlocking {
        val result = service(
            HttpStatusCode.BadRequest,
            """<html><body>client_secret=test-client-secret</body></html>""",
        ).exchange("gh-code")

        assertEquals(OAuthExchangeService.Result.UpstreamFailure, result)
    }

    @Test
    fun `200 with an unknown error code is forwarded for the route to normalise`() = runBlocking {
        val result = service(
            HttpStatusCode.OK,
            """{"error":"some_future_github_code"}""",
        ).exchange("gh-code")

        assertEquals("some_future_github_code", assertIs<OAuthExchangeService.Result.UpstreamError>(result).errorCode)
    }

    @Test
    fun `200 with an access token is Success`() = runBlocking {
        val result = service(
            HttpStatusCode.OK,
            """{"access_token":"gho_test_token","token_type":"bearer","scope":"read:user"}""",
        ).exchange("gh-code")

        assertEquals("gho_test_token", assertIs<OAuthExchangeService.Result.Success>(result).accessToken)
    }

    @Test
    fun `500 is UpstreamFailure`() = runBlocking {
        val result = service(
            HttpStatusCode.InternalServerError,
            """{"message":"Server Error"}""",
        ).exchange("gh-code")

        assertEquals(OAuthExchangeService.Result.UpstreamFailure, result)
    }
}
