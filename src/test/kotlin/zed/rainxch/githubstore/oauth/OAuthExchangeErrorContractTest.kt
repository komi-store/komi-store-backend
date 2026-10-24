package zed.rainxch.githubstore.oauth

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import zed.rainxch.githubstore.respondRateLimitedEnvelope
import zed.rainxch.githubstore.routes.oauthRoutes
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

private const val SERVICE_TOKEN = "test-shared-secret"
private const val ALLOWED_HOST = "api.github-store.org"

// End-to-end error contract for POST /v1/oauth/exchange: the real route, the
// real OAuthExchangeService, and a stubbed GitHub (MockEngine). Each test
// pins exactly what the website/app consume — status, error code, Retry-After
// — and asserts the upstream body, client_secret and access token never reach
// the response.
class OAuthExchangeErrorContractTest {

    private val urlBase64 = Base64.getUrlEncoder().withoutPadding()
    private val rng = SecureRandom()

    private val clientId = "contract-client-id"
    private val clientSecret = "contract-client-secret-must-never-leak"
    private val callbackUrl = "https://test/callback"

    private data class Upstream(
        val status: HttpStatusCode,
        val body: String,
        val headers: Map<String, String> = emptyMap(),
    )

    private fun ApplicationTestBuilder.setupApp(upstream: Upstream, store: OAuthEphemeralStore) {
        val exchangeService = OAuthExchangeService(
            clientId = clientId,
            clientSecret = clientSecret,
            callbackUrl = callbackUrl,
            engine = MockEngine {
                respond(
                    content = upstream.body,
                    status = upstream.status,
                    headers = headersOf(
                        *upstream.headers.map { it.key to listOf(it.value) }.toTypedArray(),
                    ),
                )
            },
        )

        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
            install(RateLimit) {
                register(RateLimitName("oauth-state")) {
                    rateLimiter(limit = Int.MAX_VALUE, refillPeriod = 1.minutes)
                }
                register(RateLimitName("oauth-exchange")) {
                    rateLimiter(limit = Int.MAX_VALUE, refillPeriod = 1.minutes)
                }
                register(RateLimitName("oauth-handoff")) {
                    rateLimiter(limit = Int.MAX_VALUE, refillPeriod = 1.minutes)
                }
            }
            install(StatusPages) {
                // The same helper the production plugin config installs. If
                // the generic edge-limiter envelope ever starts clobbering the
                // OAuth-specific body, the 429 tests below fail.
                status(HttpStatusCode.TooManyRequests) { call, _ ->
                    call.respondRateLimitedEnvelope()
                }
            }
            routing {
                route("/v1") {
                    oauthRoutes(store, exchangeService, OAuthServiceAuth(SERVICE_TOKEN, ALLOWED_HOST))
                }
                // Probe for the other half of the StatusPages branch: a 429
                // that is NOT the OAuth route must still get the generic
                // `rate_limited` envelope.
                get("/test/edge-429") {
                    call.response.header(HttpHeaders.RetryAfter, "60")
                    call.respond(HttpStatusCode.TooManyRequests)
                }
            }
        }
    }

    private fun random43(): String {
        val bytes = ByteArray(32)
        rng.nextBytes(bytes)
        return urlBase64.encodeToString(bytes)
    }

    private fun challengeOf(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8))
        return urlBase64.encodeToString(digest)
    }

    private suspend fun registerState(store: OAuthEphemeralStore): Pair<String, String> {
        val state = random43()
        val verifier = random43()
        val stored = """{"code_challenge":"${challengeOf(verifier)}","created_at_ms":0}"""
        assertTrue(store.setEx(OAuthEphemeralStore.NAMESPACE_STATE, state, stored, Duration.ofSeconds(60)))
        return state to verifier
    }

    private suspend fun ApplicationTestBuilder.postExchange(store: OAuthEphemeralStore): HttpResponse {
        val (state, verifier) = registerState(store)
        return client.post("/v1/oauth/exchange") {
            header("X-Oauth-Service-Token", SERVICE_TOKEN)
            header(HttpHeaders.Host, ALLOWED_HOST)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"code":"gh-code-abc","state":"$state","code_verifier":"$verifier"}""")
        }
    }

    private fun errorCodeOf(body: String): String? =
        Json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content

    // ---------- rate limiting ----------

    @Test
    fun `GitHub 429 becomes 429 github_rate_limit and forwards Retry-After`() = testApplication {
        val store = InMemoryOAuthEphemeralStore()
        setupApp(
            Upstream(
                status = HttpStatusCode.TooManyRequests,
                body = """{"message":"API rate limit exceeded for 1.2.3.4"}""",
                headers = mapOf(HttpHeaders.RetryAfter to "42"),
            ),
            store,
        )

        val resp = postExchange(store)
        val body = resp.bodyAsText()

        assertEquals(HttpStatusCode.TooManyRequests, resp.status)
        assertEquals("42", resp.headers[HttpHeaders.RetryAfter])
        assertEquals("github_rate_limit", errorCodeOf(body))
        assertFalse(body.contains(clientSecret), "client_secret leaked into the response: $body")
        assertFalse(body.contains("API rate limit exceeded"), "raw upstream body leaked: $body")
    }

    @Test
    fun `GitHub 403 with x-ratelimit-remaining 0 becomes 429 with the 600s default`() = testApplication {
        val store = InMemoryOAuthEphemeralStore()
        setupApp(
            Upstream(
                status = HttpStatusCode.Forbidden,
                body = """{"message":"API rate limit exceeded for 1.2.3.4"}""",
                headers = mapOf("X-RateLimit-Remaining" to "0"),
            ),
            store,
        )

        val resp = postExchange(store)
        val body = resp.bodyAsText()

        assertEquals(HttpStatusCode.TooManyRequests, resp.status)
        assertEquals("600", resp.headers[HttpHeaders.RetryAfter])
        assertEquals("github_rate_limit", errorCodeOf(body))
        assertFalse(body.contains(clientSecret), "client_secret leaked into the response: $body")
        assertFalse(body.contains("API rate limit exceeded"), "raw upstream body leaked: $body")
    }

    // ---------- GitHub error codes ----------

    @Test
    fun `GitHub 400 bad_verification_code becomes 400 github_bad_verification_code`() = testApplication {
        val store = InMemoryOAuthEphemeralStore()
        setupApp(
            Upstream(
                status = HttpStatusCode.BadRequest,
                body = """{"error":"bad_verification_code","error_description":"The code passed is incorrect or expired."}""",
            ),
            store,
        )

        val resp = postExchange(store)
        val body = resp.bodyAsText()

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertEquals("github_bad_verification_code", errorCodeOf(body))
        assertFalse(body.contains("error_description"), "raw upstream body leaked: $body")
        assertFalse(body.contains(clientSecret), "client_secret leaked into the response: $body")
    }

    @Test
    fun `GitHub 400 with an unknown error code becomes 400 github_unknown without leaking it`() = testApplication {
        val store = InMemoryOAuthEphemeralStore()
        setupApp(
            Upstream(
                status = HttpStatusCode.BadRequest,
                body = """{"error":"some_future_github_code","error_description":"internal upstream detail"}""",
            ),
            store,
        )

        val resp = postExchange(store)
        val body = resp.bodyAsText()

        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertEquals("github_unknown", errorCodeOf(body))
        assertFalse(body.contains("some_future_github_code"), "unsanitised error code leaked: $body")
        assertFalse(body.contains("internal upstream detail"), "raw upstream body leaked: $body")
    }

    @Test
    fun `unknown GitHub 500 becomes 502 github_unreachable without leaking the body`() = testApplication {
        val store = InMemoryOAuthEphemeralStore()
        setupApp(
            Upstream(
                status = HttpStatusCode.InternalServerError,
                body = """{"message":"Internal Server Error (client_secret=$clientSecret)"}""",
            ),
            store,
        )

        val resp = postExchange(store)
        val body = resp.bodyAsText()

        assertEquals(HttpStatusCode.BadGateway, resp.status)
        assertEquals("github_unreachable", errorCodeOf(body))
        assertFalse(body.contains("Internal Server Error"), "raw upstream body leaked: $body")
        assertFalse(body.contains(clientSecret), "client_secret leaked into the response: $body")
    }

    // ---------- success + non-OAuth 429 ----------

    @Test
    fun `successful exchange returns only handoff_id - never the token or client_secret`() = testApplication {
        val upstreamToken = "gho_contract_access_token_must_not_leak"
        val store = InMemoryOAuthEphemeralStore()
        setupApp(
            Upstream(
                status = HttpStatusCode.OK,
                body = """{"access_token":"$upstreamToken","token_type":"bearer"}""",
            ),
            store,
        )

        val resp = postExchange(store)
        val body = resp.bodyAsText()

        assertEquals(HttpStatusCode.OK, resp.status)
        val handoffId = Json.parseToJsonElement(body).jsonObject["handoff_id"]?.jsonPrimitive?.content
        assertNotNull(handoffId, "missing handoff_id: $body")
        assertNull(Json.parseToJsonElement(body).jsonObject["access_token"], "token exposed in body: $body")
        assertFalse(body.contains(upstreamToken), "access_token leaked into the response: $body")
        assertFalse(body.contains(clientSecret), "client_secret leaked into the response: $body")
        // The token went into the one-shot handoff row instead.
        assertEquals(upstreamToken, store.get(OAuthEphemeralStore.NAMESPACE_HANDOFF, handoffId))
    }

    @Test
    fun `generic edge 429 still gets the rate_limited envelope`() = testApplication {
        val store = InMemoryOAuthEphemeralStore()
        setupApp(Upstream(status = HttpStatusCode.OK, body = """{"access_token":"unused"}"""), store)

        val resp = client.get("/test/edge-429")
        val body = resp.bodyAsText()

        assertEquals(HttpStatusCode.TooManyRequests, resp.status)
        assertEquals("60", resp.headers[HttpHeaders.RetryAfter])
        assertEquals("rate_limited", errorCodeOf(body))
        assertFalse(body.contains("github_rate_limit"), "OAuth marker leaked onto another route: $body")
    }
}
