package io.sentry.buddy.seer

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SeerClientTest {

    private val requestedUrls = mutableListOf<String>()

    private fun clientOf(vararg responses: Pair<String, HttpStatusCode>): HttpClient {
        var index = 0
        val engine = MockEngine { request ->
            requestedUrls += request.url.toString()
            val (body, status) = responses[minOf(index++, responses.size - 1)]
            respond(
                content = ByteReadChannel(body),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        // The same value SeerClient's own default httpClient installs, not a second literal that
        // could drift from it.
        return HttpClient(engine) { install(ContentNegotiation) { json(seerJson) } }
    }

    private fun seerClient(httpClient: HttpClient, projectId: String? = "5428559") = SeerClient(
        authToken = "token",
        org = "sentry-sdks",
        projectId = projectId,
        httpClient = httpClient,
        pollIntervalMs = 1L,
        timeoutMs = 1000L
    )

    @Test
    fun `startRun posts to the org explorer-chat endpoint and returns both run ids`() = runBlocking {
        val client = seerClient(clientOf("""{"run_id": 42, "sentry_run_id": "3f2c-uuid"}""" to HttpStatusCode.OK))

        val run = client.startRun("analyze this")

        assertEquals(42L, run.runId)
        assertEquals("3f2c-uuid", run.sentryRunId)
        assertEquals("https://sentry.io/api/0/organizations/sentry-sdks/seer/explorer-chat/", requestedUrls.single())
    }

    @Test
    fun `startRun throws when the endpoint denies the call`() = runBlocking {
        val client = seerClient(clientOf("""{"detail": "no access"}""" to HttpStatusCode.Forbidden))

        val error = assertFailsWith<IllegalStateException> { client.startRun("analyze this") }

        assertTrue(error.message!!.contains("403"))
    }

    @Test
    fun `awaitAnswer polls until the run is completed and gives the last finished block`() = runBlocking {
        val client = seerClient(
            clientOf(
                """{"session": {"run_id": 42, "status": "processing", "blocks": []}}""" to HttpStatusCode.OK,
                """{"session": {"run_id": 42, "status": "completed", "blocks": [
                     {"id": "b1", "message": "thinking", "loading": true},
                     {"id": "b2", "message": "the answer", "loading": false}
                   ]}}""" to HttpStatusCode.OK
            )
        )

        assertEquals("the answer", client.awaitAnswer(42L))
    }

    @Test
    fun `awaitAnswer retries while the run is not yet created`() = runBlocking {
        val client = seerClient(
            clientOf(
                """{"detail": "This run is still being created; retry shortly."}""" to HttpStatusCode.Conflict,
                """{"session": {"run_id": 42, "status": "completed", "blocks": [
                     {"id": "b1", "message": "the answer", "loading": false}
                   ]}}""" to HttpStatusCode.OK
            )
        )

        assertEquals("the answer", client.awaitAnswer(42L))
    }

    @Test
    fun `awaitAnswer throws when the run ends with an error status`() = runBlocking {
        val client = seerClient(
            clientOf("""{"session": {"run_id": 42, "status": "error", "blocks": []}}""" to HttpStatusCode.OK)
        )

        assertFailsWith<IllegalStateException> { client.awaitAnswer(42L) }
        Unit
    }

    @Test
    fun `awaitAnswer throws when the run does not complete in time`() = runBlocking {
        val client = seerClient(
            clientOf("""{"session": {"run_id": 42, "status": "processing", "blocks": []}}""" to HttpStatusCode.OK)
        )

        assertFailsWith<IllegalStateException> { client.awaitAnswer(42L) }
        Unit
    }

    @Test
    fun `awaitAnswer tolerates the extra fields a real poll response carries`() = runBlocking {
        val client = seerClient(
            clientOf(
                """
                {
                  "sentry_run_id": "3f2c-uuid",
                  "session": {
                    "run_id": 42,
                    "status": "completed",
                    "updated_at": "2026-08-18T09:12:00Z",
                    "owner_user_id": 7,
                    "pending_user_input": null,
                    "repo_pr_states": [],
                    "blocks": [
                      {
                        "id": "b1",
                        "message": "the answer",
                        "loading": false,
                        "timestamp": "2026-08-18T09:12:05Z",
                        "artifacts": [],
                        "file_patches": [],
                        "merged_file_patches": [],
                        "pr_commit_shas": [],
                        "todos": [],
                        "tool_links": [],
                        "tool_results": []
                      }
                    ]
                  }
                }
                """.trimIndent() to HttpStatusCode.OK
            )
        )

        assertEquals("the answer", client.awaitAnswer(42L))
    }

    @Test
    fun `runUrl builds the explorer link for the org and the project`() {
        val client = seerClient(clientOf("{}" to HttpStatusCode.OK))

        assertEquals(
            "https://sentry-sdks.sentry.io/issues/?project=5428559&statsPeriod=10m&explorerRunId=3f2c-uuid",
            client.runUrl("3f2c-uuid")
        )
    }

    @Test
    fun `runUrl leaves out the project parameter when there is no project id`() {
        val client = seerClient(clientOf("{}" to HttpStatusCode.OK), projectId = null)

        assertEquals(
            "https://sentry-sdks.sentry.io/issues/?statsPeriod=10m&explorerRunId=3f2c-uuid",
            client.runUrl("3f2c-uuid")
        )
    }
}
