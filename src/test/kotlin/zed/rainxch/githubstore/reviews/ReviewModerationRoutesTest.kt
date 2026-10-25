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
import zed.rainxch.githubstore.routes.reviewModerationRoutes
import zed.rainxch.githubstore.routes.reviewRoutes
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val AUTHOR = "ghp_author"
private const val MOD = "ghp_moderator"
private const val OTHER = "ghp_other"
private const val ADMIN = "admin-secret"

class ReviewModerationRoutesTest {

    private val github = FakeGitHub().apply {
        user(AUTHOR, id = 1, login = "author")
        user(MOD, id = 5, login = "mod")
        user(OTHER, id = 6, login = "other")
        repo("owner", "app", id = 100)
    }
    private val store = InMemoryReviewStore()

    private fun ApplicationTestBuilder.app(enabled: Boolean = true) {
        application {
            configureSerialization()
            configureHTTP()
            val config = ReviewsConfig(enabled = enabled, moderatorIds = setOf(5), adminToken = ADMIN)
            val identity = ReviewIdentity(GitHubReviewClient(github.engine))
            routing {
                route("/v1") {
                    reviewRoutes(
                        store = store,
                        identity = identity,
                        forgejoResourceClient = ForgejoResourceClient(object : ResourceCacheRepository() {}),
                        config = config,
                        limits = ReviewRateLimits(),
                        now = { Instant.parse("2026-10-25T10:00:00Z") },
                    )
                    reviewModerationRoutes(store, identity, config)
                }
            }
        }
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.assertError(status: HttpStatusCode, code: String) {
        assertEquals(status, this.status, bodyAsText())
        assertEquals(code, json()["error"]?.jsonPrimitive?.content, bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.writeAndReport(): String {
        val id = client.put("/v1/reviews/repo/owner/app") {
            header("X-GitHub-Token", AUTHOR)
            contentType(ContentType.Application.Json)
            setBody("""{"rating":1,"text":"spam spam"}""")
        }.json()["id"]!!.jsonPrimitive.content
        client.post("/v1/reviews/item/$id/report") {
            header("X-GitHub-Token", OTHER)
            contentType(ContentType.Application.Json)
            setBody("""{"reason":"spam"}""")
        }
        return id
    }

    private suspend fun ApplicationTestBuilder.mod(
        method: HttpMethod,
        path: String,
        body: String? = null,
        token: String? = MOD,
        admin: String? = null,
    ): HttpResponse = client.request("/v1/reviews/moderation$path") {
        this.method = method
        if (token != null) header("X-GitHub-Token", token)
        if (admin != null) header("X-Admin-Token", admin)
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    private suspend fun ApplicationTestBuilder.queue(status: String) =
        mod(HttpMethod.Get, "/queue?status=$status").json()["items"]!!.jsonArray

    private suspend fun ApplicationTestBuilder.publicCount() =
        client.get("/v1/reviews/repo/owner/app").json()["summary"]!!.jsonObject["count"]!!.jsonPrimitive.int

    @Test
    fun `moderation needs a moderator token or the admin token`() = testApplication {
        app()
        mod(HttpMethod.Get, "/queue", token = null).assertError(HttpStatusCode.Unauthorized, "auth_required")
        mod(HttpMethod.Get, "/queue", token = null, admin = "wrong").assertError(HttpStatusCode.Unauthorized, "auth_required")
        mod(HttpMethod.Get, "/queue", token = OTHER).assertError(HttpStatusCode.Forbidden, "not_moderator")
        mod(HttpMethod.Get, "/queue", token = "ghp_revoked").assertError(HttpStatusCode.Unauthorized, "auth_invalid")
        val asMod = mod(HttpMethod.Get, "/queue")
        assertEquals(HttpStatusCode.OK, asMod.status)
        assertEquals("no-store", asMod.headers[HttpHeaders.CacheControl])
        assertEquals(HttpStatusCode.OK, mod(HttpMethod.Get, "/queue", token = null, admin = ADMIN).status)
    }

    @Test
    fun `reported reviews show in the queue with their reports`() = testApplication {
        app()
        val id = writeAndReport()
        val item = queue("reported").single().jsonObject
        assertEquals(id, item["review"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(1, item["reportCount"]!!.jsonPrimitive.int)
        assertEquals("spam", item["reports"]!!.jsonArray.single().jsonObject["reason"]!!.jsonPrimitive.content)
        assertEquals("owner", item["repo"]!!.jsonObject["owner"]!!.jsonPrimitive.content)
        assertFalse(item["hidden"]!!.jsonPrimitive.boolean)
        mod(HttpMethod.Get, "/queue?status=open").assertError(HttpStatusCode.BadRequest, "invalid_request")
    }

    @Test
    fun `hide removes from the list and the reported queue, unhide restores`() = testApplication {
        app()
        val id = writeAndReport()
        assertEquals(HttpStatusCode.NoContent, mod(HttpMethod.Post, "/item/$id/hide", """{"reason":"spam"}""").status)
        assertEquals(0, publicCount())
        assertEquals(0, queue("reported").size)
        assertTrue(queue("hidden").single().jsonObject["hidden"]!!.jsonPrimitive.boolean)
        val mine = client.get("/v1/reviews/repo/owner/app/mine") { header("X-GitHub-Token", AUTHOR) }.json()
        assertTrue(mine["review"]!!.jsonObject["hidden"]!!.jsonPrimitive.boolean)

        assertEquals(HttpStatusCode.NoContent, mod(HttpMethod.Post, "/item/$id/unhide").status)
        assertEquals(1, publicCount())
        assertEquals(listOf("hide $id by 5: spam", "unhide $id by 5"), store.moderationLog)
    }

    @Test
    fun `dismiss closes reports and keeps the review visible`() = testApplication {
        app()
        val id = writeAndReport()
        assertEquals(HttpStatusCode.NoContent, mod(HttpMethod.Post, "/item/$id/dismiss", admin = ADMIN, token = null).status)
        assertEquals(0, queue("reported").size)
        assertEquals(1, publicCount())
        assertEquals(listOf("dismiss $id by null"), store.moderationLog)
    }

    @Test
    fun `delete is a hard delete and unknown reviews are review_not_found`() = testApplication {
        app()
        val id = writeAndReport()
        assertEquals(HttpStatusCode.NoContent, mod(HttpMethod.Delete, "/item/$id").status)
        assertEquals(0, publicCount())
        mod(HttpMethod.Delete, "/item/$id").assertError(HttpStatusCode.NotFound, "review_not_found")
        mod(HttpMethod.Post, "/item/$id/hide").assertError(HttpStatusCode.NotFound, "review_not_found")
        mod(HttpMethod.Post, "/item/bad-id/unhide").assertError(HttpStatusCode.NotFound, "review_not_found")
    }

    @Test
    fun `hide reason over 500 code points is note_too_long`() = testApplication {
        app()
        val id = writeAndReport()
        mod(HttpMethod.Post, "/item/$id/hide", """{"reason":"${"r".repeat(501)}"}""")
            .assertError(HttpStatusCode.UnprocessableEntity, "note_too_long")
        assertEquals(1, publicCount())
    }

    @Test
    fun `ban stops writes and unban allows them again`() = testApplication {
        app()
        assertEquals(HttpStatusCode.NoContent, mod(HttpMethod.Post, "/users/1/ban", """{"reason":"brigading"}""").status)
        assertEquals(HttpStatusCode.NoContent, mod(HttpMethod.Post, "/users/1/ban").status)
        val put = client.put("/v1/reviews/repo/owner/app") {
            header("X-GitHub-Token", AUTHOR)
            contentType(ContentType.Application.Json)
            setBody("""{"rating":5}""")
        }
        put.assertError(HttpStatusCode.Forbidden, "banned")
        assertEquals(HttpStatusCode.NoContent, mod(HttpMethod.Delete, "/users/1/ban").status)
        assertEquals(
            HttpStatusCode.OK,
            client.put("/v1/reviews/repo/owner/app") {
                header("X-GitHub-Token", AUTHOR)
                contentType(ContentType.Application.Json)
                setBody("""{"rating":5}""")
            }.status,
        )
        mod(HttpMethod.Post, "/users/abc/ban").assertError(HttpStatusCode.BadRequest, "invalid_request")
        assertEquals("ban 1 by 5: brigading", store.moderationLog.first())
    }

    @Test
    fun `moderation routes 404 when reviews are off`() = testApplication {
        app(enabled = false)
        mod(HttpMethod.Get, "/queue").assertError(HttpStatusCode.NotFound, "not_found")
    }
}
