package zed.rainxch.githubstore.reviews

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger

// Minimal api.github.com for review tests: GET /user and GET /repos/{o}/{n},
// answered per token. Unknown token -> 401, unknown repo -> 404.
class FakeGitHub {
    private class FakeRepo(val id: Long, val owner: String, val name: String, val private: Boolean, val pushTokens: Set<String>)

    private val users = mutableMapOf<String, String>()
    private val repos = mutableMapOf<String, FakeRepo>()

    // Status to answer every call with instead of the normal behaviour.
    var outage: HttpStatusCode? = null
    var outageHeaders: List<Pair<String, String>> = emptyList()

    val calls = AtomicInteger()
    val authHeaders = mutableListOf<String?>()

    fun user(token: String, id: Long, login: String, createdAt: String = "2020-01-01T00:00:00Z") {
        users[token] = """{"id":$id,"login":"$login","avatar_url":"https://avatars.example/$id","created_at":"$createdAt"}"""
    }

    fun repo(owner: String, name: String, id: Long, private: Boolean = false, pushTokens: Set<String> = emptySet()) {
        repos["$owner/$name".lowercase()] = FakeRepo(id, owner, name, private, pushTokens)
    }

    val engine = MockEngine { request ->
        calls.incrementAndGet()
        val auth = request.headers[HttpHeaders.Authorization]
        synchronized(authHeaders) { authHeaders += auth }
        val token = auth?.removePrefix("token ")
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        outage?.let { status ->
            return@MockEngine respond("""{"message":"outage"}""", status, headersOf(*outageHeaders.map { it.first to listOf(it.second) }.toTypedArray()))
        }
        val path = request.url.encodedPath
        when {
            path == "/user" -> {
                val body = users[token] ?: return@MockEngine respond("""{"message":"Bad credentials"}""", HttpStatusCode.Unauthorized, json)
                respond(body, HttpStatusCode.OK, json)
            }
            path.startsWith("/repos/") -> {
                if (token !in users) return@MockEngine respond("""{"message":"Bad credentials"}""", HttpStatusCode.Unauthorized, json)
                val repo = repos[path.removePrefix("/repos/").lowercase()]
                    ?: return@MockEngine respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound, json)
                val push = token in repo.pushTokens
                respond(
                    """{"id":${repo.id},"name":"${repo.name}","owner":{"login":"${repo.owner}"},"private":${repo.private},""" +
                        """"permissions":{"admin":false,"push":$push,"pull":true}}""",
                    HttpStatusCode.OK,
                    json,
                )
            }
            else -> respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound, json)
        }
    }
}
