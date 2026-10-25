package zed.rainxch.githubstore.reviews

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import zed.rainxch.githubstore.configureHTTP
import zed.rainxch.githubstore.configureSerialization
import zed.rainxch.githubstore.db.ResourceCacheRepository
import zed.rainxch.githubstore.match.ForgejoResourceClient
import zed.rainxch.githubstore.model.RepoOwner
import zed.rainxch.githubstore.model.RepoResponse
import zed.rainxch.githubstore.routes.reviewRoutes
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

private const val USER = "ghp_user"
private const val NEWBIE = "ghp_newbie"
private const val MAINTAINER = "ghp_maintainer"
private const val BANNED = "ghp_banned"

class ReviewRoutesTest {

    private val now = Instant.parse("2026-10-25T10:00:00Z")
    private val github = FakeGitHub().apply {
        user(USER, id = 1, login = "user")
        user(NEWBIE, id = 2, login = "newbie", createdAt = "2026-10-20T00:00:00Z")
        user(MAINTAINER, id = 3, login = "maintainer")
        user(BANNED, id = 4, login = "banned")
        repo("owner", "app", id = 100, pushTokens = setOf(MAINTAINER))
        repo("owner", "private", id = 101, private = true)
    }
    private val store = InMemoryReviewStore().apply { banned += 4 }

    private class FakeForge : ForgejoResourceClient(object : ResourceCacheRepository() {}, setOf("codeberg.org")) {
        override suspend fun fetchRepo(host: String, owner: String, name: String): RepoResponse? =
            if (name != "app") null else RepoResponse(
                id = 9, name = "app", fullName = "$owner/app", owner = RepoOwner(owner, null),
                description = null, defaultBranch = null, htmlUrl = "https://$host/$owner/app",
                stargazersCount = 0, forksCount = 0, language = null, topics = emptyList(),
                releasesUrl = null, updatedAt = null, createdAt = null, sourceHost = host,
            )
    }

    private fun ApplicationTestBuilder.app(
        enabled: Boolean = true,
        reviewLimit: Int = 20,
        moderators: Set<Long> = emptySet(),
    ) {
        application {
            configureSerialization()
            configureHTTP()
            routing {
                route("/v1") {
                    reviewRoutes(
                        store = store,
                        identity = ReviewIdentity(GitHubReviewClient(github.engine)),
                        forgejoResourceClient = FakeForge(),
                        config = ReviewsConfig(enabled = enabled, minAccountAgeDays = 30, moderatorIds = moderators),
                        reviewLimiter = WindowRateLimiter(reviewLimit, 1.hours),
                        now = { now },
                    )
                }
            }
        }
    }

    private suspend fun ApplicationTestBuilder.putReview(
        body: String,
        token: String? = USER,
        path: String = "owner/app",
        query: String = "",
    ): HttpResponse = client.put("/v1/reviews/repo/$path$query") {
        if (token != null) header("X-GitHub-Token", token)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.assertError(status: HttpStatusCode, code: String) {
        assertEquals(status, this.status, bodyAsText())
        assertEquals(code, json()["error"]?.jsonPrimitive?.content, bodyAsText())
    }

    // ---- public list ----

    @Test
    fun `empty repo is a 200 with a zero summary and the public cache header`() = testApplication {
        app()
        val response = client.get("/v1/reviews/repo/owner/app")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("public, max-age=60, s-maxage=300", response.headers[HttpHeaders.CacheControl])
        val body = response.json()
        val summary = body["summary"]!!.jsonObject
        assertEquals(0, summary["count"]!!.jsonPrimitive.int)
        assertEquals("null", summary["average"].toString())
        assertEquals(5, summary["histogram"]!!.jsonObject.size)
        assertEquals(0, body["reviews"]!!.jsonArray.size)
        assertFalse(body["hasMore"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `public list never reads the token, so every caller gets the same bytes`() = testApplication {
        app()
        putReview("""{"rating":5,"text":"Great"}""")
        val callsBefore = github.calls.get()
        val anon = client.get("/v1/reviews/repo/owner/app").bodyAsText()
        val authed = client.get("/v1/reviews/repo/owner/app") { header("X-GitHub-Token", "ghp_garbage") }
        assertEquals(HttpStatusCode.OK, authed.status)
        assertEquals(anon, authed.bodyAsText())
        assertEquals(callsBefore, github.calls.get(), "public read called GitHub")
    }

    @Test
    fun `public list rejects bad path, host, sort and rating filter`() = testApplication {
        app()
        client.get("/v1/reviews/repo/-bad/app").assertError(HttpStatusCode.BadRequest, "invalid_owner")
        client.get("/v1/reviews/repo/owner/bad%20name").assertError(HttpStatusCode.BadRequest, "invalid_name")
        client.get("/v1/reviews/repo/owner/app?host=evil.example").assertError(HttpStatusCode.BadRequest, "invalid_host")
        client.get("/v1/reviews/repo/owner/app?sort=random").assertError(HttpStatusCode.BadRequest, "invalid_request")
        client.get("/v1/reviews/repo/owner/app?rating=6").assertError(HttpStatusCode.BadRequest, "invalid_request")
        assertEquals(HttpStatusCode.OK, client.get("/v1/reviews/repo/owner/app?host=codeberg.org&sort=lowest&rating=1").status)
    }

    @Test
    fun `hidden reviews leave the public list`() = testApplication {
        app()
        val id = putReview("""{"rating":1,"text":"spam"}""").json()["id"]!!.jsonPrimitive.content
        store.hide(id)
        val body = client.get("/v1/reviews/repo/owner/app").json()
        assertEquals(0, body["summary"]!!.jsonObject["count"]!!.jsonPrimitive.int)
        assertEquals(0, body["reviews"]!!.jsonArray.size)
    }

    // ---- disabled ----

    @Test
    fun `REVIEWS_ENABLED off makes every review route 404 not_found`() = testApplication {
        app(enabled = false)
        client.get("/v1/reviews/repo/owner/app").assertError(HttpStatusCode.NotFound, "not_found")
        putReview("""{"rating":5}""").assertError(HttpStatusCode.NotFound, "not_found")
    }

    // ---- auth ----

    @Test
    fun `missing token is auth_required, a rejected or malformed token is auth_invalid`() = testApplication {
        app()
        client.get("/v1/reviews/repo/owner/app/mine").assertError(HttpStatusCode.Unauthorized, "auth_required")
        putReview("""{"rating":5}""", token = null).assertError(HttpStatusCode.Unauthorized, "auth_required")
        client.delete("/v1/reviews/repo/owner/app").assertError(HttpStatusCode.Unauthorized, "auth_required")
        putReview("""{"rating":5}""", token = "ghp_revoked").assertError(HttpStatusCode.Unauthorized, "auth_invalid")
        putReview("""{"rating":5}""", token = "bad-token!").assertError(HttpStatusCode.Unauthorized, "auth_invalid")
    }

    @Test
    fun `GitHub unreachable while resolving the user is 502 upstream_unavailable`() = testApplication {
        app()
        github.outage = HttpStatusCode.ServiceUnavailable
        putReview("""{"rating":5}""").assertError(HttpStatusCode.BadGateway, "upstream_unavailable")
    }

    // ---- validation ----

    @Test
    fun `field validation returns the contract's 422 codes`() = testApplication {
        app()
        putReview("""{"text":"no rating"}""").assertError(HttpStatusCode.UnprocessableEntity, "invalid_rating")
        putReview("""{"rating":0}""").assertError(HttpStatusCode.UnprocessableEntity, "invalid_rating")
        putReview("""{"rating":5,"platform":"ios"}""").assertError(HttpStatusCode.UnprocessableEntity, "invalid_platform")
        val long = "a".repeat(2001)
        putReview("""{"rating":5,"text":"$long"}""").assertError(HttpStatusCode.UnprocessableEntity, "text_too_long")
    }

    @Test
    fun `text length counts code points, so 2000 emoji fit`() = testApplication {
        app()
        val emoji = "😀".repeat(2000)
        assertEquals(HttpStatusCode.OK, putReview("""{"rating":5,"text":"$emoji"}""").status)
    }

    @Test
    fun `malformed bodies are invalid_request`() = testApplication {
        app()
        putReview("""{"rating":""").assertError(HttpStatusCode.BadRequest, "invalid_request")
        putReview("""{"rating":4.5}""").assertError(HttpStatusCode.BadRequest, "invalid_request")
        putReview("""{"rating":5,"version":"${"v".repeat(129)}"}""").assertError(HttpStatusCode.BadRequest, "invalid_request")
        putReview("""{"rating":5,"text":"a\u0000b"}""").assertError(HttpStatusCode.BadRequest, "invalid_request")
    }

    // ---- eligibility ----

    @Test
    fun `young accounts, maintainers and banned users get their 403 codes`() = testApplication {
        app()
        putReview("""{"rating":5}""", token = NEWBIE).assertError(HttpStatusCode.Forbidden, "account_too_new")
        putReview("""{"rating":5}""", token = MAINTAINER).assertError(HttpStatusCode.Forbidden, "own_repo")
        putReview("""{"rating":5}""", token = BANNED).assertError(HttpStatusCode.Forbidden, "banned")
    }

    @Test
    fun `missing and private repos are 404 repo_not_found with the code kept`() = testApplication {
        app()
        val missing = putReview("""{"rating":5}""", path = "owner/nope")
        missing.assertError(HttpStatusCode.NotFound, "repo_not_found")
        assertEquals("no-store", missing.headers[HttpHeaders.CacheControl])
        putReview("""{"rating":5}""", path = "owner/private").assertError(HttpStatusCode.NotFound, "repo_not_found")
        putReview("""{"rating":5}""", path = "owner/nope", query = "?host=codeberg.org")
            .assertError(HttpStatusCode.NotFound, "repo_not_found")
    }

    @Test
    fun `per-user review limit answers 429 rate_limited with Retry-After`() = testApplication {
        app(reviewLimit = 1)
        assertEquals(HttpStatusCode.OK, putReview("""{"rating":5}""").status)
        val limited = putReview("""{"rating":4}""")
        limited.assertError(HttpStatusCode.TooManyRequests, "rate_limited")
        assertEquals("3600", limited.headers[HttpHeaders.RetryAfter])
    }

    // ---- happy paths ----

    @Test
    fun `PUT creates then edits the caller's review and returns it`() = testApplication {
        app()
        val created = putReview("""{"rating":4,"text":"  Works  ","version":"v1.9.3","platform":"android","appLabel":"Auth"}""")
        assertEquals(HttpStatusCode.OK, created.status)
        assertEquals("no-store", created.headers[HttpHeaders.CacheControl])
        val review = created.json()
        assertEquals("Works", review["text"]!!.jsonPrimitive.content)
        assertEquals("v1.9.3", review["version"]!!.jsonPrimitive.content)
        assertEquals("android", review["platform"]!!.jsonPrimitive.content)
        assertEquals("Auth", review["appLabel"]!!.jsonPrimitive.content)
        assertEquals(1L, review["author"]!!.jsonObject["id"]!!.jsonPrimitive.content.toLong())
        assertFalse(review["hidden"]!!.jsonPrimitive.boolean)

        val edited = putReview("""{"rating":2,"text":"   "}""").json()
        assertEquals(review["id"], edited["id"])
        assertEquals("null", edited["text"].toString())
        assertTrue(edited["edited"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `mine reports the review, eligibility, reply rights and moderator flag`() = testApplication {
        app(moderators = setOf(3))
        val empty = client.get("/v1/reviews/repo/owner/app/mine") { header("X-GitHub-Token", USER) }
        assertEquals("no-store", empty.headers[HttpHeaders.CacheControl])
        val emptyBody = empty.json()
        assertEquals("null", emptyBody["review"].toString())
        assertTrue(emptyBody["canReview"]!!.jsonPrimitive.boolean)
        assertEquals("null", emptyBody["cannotReviewReason"].toString())
        assertFalse(emptyBody["canReply"]!!.jsonPrimitive.boolean)
        assertFalse(emptyBody["isModerator"]!!.jsonPrimitive.boolean)

        putReview("""{"rating":5}""")
        val mine = client.get("/v1/reviews/repo/owner/app/mine") { header("X-GitHub-Token", USER) }.json()
        assertEquals(5, mine["review"]!!.jsonObject["rating"]!!.jsonPrimitive.int)

        val maintainer = client.get("/v1/reviews/repo/owner/app/mine") { header("X-GitHub-Token", MAINTAINER) }.json()
        assertFalse(maintainer["canReview"]!!.jsonPrimitive.boolean)
        assertEquals("own_repo", maintainer["cannotReviewReason"]!!.jsonPrimitive.content)
        assertTrue(maintainer["canReply"]!!.jsonPrimitive.boolean)
        assertTrue(maintainer["isModerator"]!!.jsonPrimitive.boolean)

        val banned = client.get("/v1/reviews/repo/owner/app/mine") { header("X-GitHub-Token", BANNED) }.json()
        assertEquals("banned", banned["cannotReviewReason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `mine shows the caller their own hidden review`() = testApplication {
        app()
        val id = putReview("""{"rating":1}""").json()["id"]!!.jsonPrimitive.content
        store.hide(id)
        val mine = client.get("/v1/reviews/repo/owner/app/mine") { header("X-GitHub-Token", USER) }.json()
        assertTrue(mine["review"]!!.jsonObject["hidden"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `forge repos can be reviewed but never replied to`() = testApplication {
        app()
        assertEquals(HttpStatusCode.OK, putReview("""{"rating":3}""", token = MAINTAINER, query = "?host=codeberg.org").status)
        val mine = client.get("/v1/reviews/repo/owner/app/mine?host=codeberg.org") { header("X-GitHub-Token", MAINTAINER) }.json()
        assertTrue(mine["canReview"]!!.jsonPrimitive.boolean)
        assertFalse(mine["canReply"]!!.jsonPrimitive.boolean)
        val list = client.get("/v1/reviews/repo/owner/app?host=codeberg.org").json()
        assertEquals(1, list["summary"]!!.jsonObject["count"]!!.jsonPrimitive.int)
    }

    @Test
    fun `DELETE is idempotent and allowed for banned users`() = testApplication {
        app()
        putReview("""{"rating":5}""")
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/reviews/repo/owner/app") { header("X-GitHub-Token", USER) }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/reviews/repo/owner/app") { header("X-GitHub-Token", USER) }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/reviews/repo/owner/app") { header("X-GitHub-Token", BANNED) }.status)
        val mine = client.get("/v1/reviews/repo/owner/app/mine") { header("X-GitHub-Token", USER) }.json()
        assertNull(mine["review"]?.takeIf { it.toString() != "null" })
    }
}
