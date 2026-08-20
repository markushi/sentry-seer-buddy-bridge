package io.sentry.buddy.enrichment

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import io.sentry.buddy.AnalysisStatus
import io.sentry.buddy.FlowAnalysisEvent
import io.sentry.buddy.FlowAnalysisRequest
import io.sentry.buddy.FlowAnalysisResponse
import io.sentry.buddy.ActionStatus
import io.sentry.buddy.RecommendationStatus
import io.sentry.buddy.SentryIssue
import io.sentry.buddy.Severity
import io.sentry.buddy.seer.SeerClient
import io.sentry.buddy.seer.seerJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SeerRecommendationEnrichmentTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun sampleRequest() = FlowAnalysisRequest(
        flowId = "flow-1",
        traceIds = listOf("trace-1"),
        startTimeMs = 1000L,
        endTimeMs = 2000L,
        dsn = "https://key@o123.ingest.sentry.io/456",
        userAnnotation = "tapped checkout twice",
        sdk = "io.sentry.android@8.40.0",
        events = listOf(FlowAnalysisEvent(type = "click", timestamp = 1500L, data = JsonObject(emptyMap())))
    )

    private fun sampleResponse(issues: List<SentryIssue> = emptyList()) = FlowAnalysisResponse(
        flowId = "flow-1",
        status = AnalysisStatus.PROCESSING,
        issues = issues
    )

    private fun clientOf(vararg responses: Pair<String, HttpStatusCode>): HttpClient {
        var index = 0
        val engine = MockEngine { _ ->
            val (body, status) = responses[minOf(index++, responses.size - 1)]
            respond(
                content = ByteReadChannel(body),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        return HttpClient(engine) { install(ContentNegotiation) { json(seerJson) } }
    }

    private fun enrichmentWith(client: HttpClient) = SeerRecommendationEnrichment(
        seerClient = SeerClient(
            authToken = "token",
            org = "sentry-sdks",
            projectId = "5428559",
            httpClient = client,
            pollIntervalMs = 1L,
            timeoutMs = 1000L
        ),
        json = json
    )

    @Test
    fun `parseRecommendations maps a well-formed JSON array to Recommendations with fresh ids`() {
        val output = """
            [
              {"title": "Debounce the checkout button", "description": "It was tapped twice within 200ms.", "severity": "MEDIUM"},
              {"title": "Retry failed network request", "description": "The submit call timed out.", "link": "https://docs.sentry.io/retries", "severity": "HIGH"}
            ]
        """.trimIndent()

        val recommendations = parseRecommendations(output, json)

        assertEquals(2, recommendations.size)
        assertEquals("Debounce the checkout button", recommendations[0].title)
        assertEquals(Severity.MEDIUM, recommendations[0].severity)
        assertEquals(RecommendationStatus.OPEN, recommendations[0].status)
        assertEquals("https://docs.sentry.io/retries", recommendations[1].link)
        assertNotEquals(recommendations[0].id, recommendations[1].id)
        assertTrue(recommendations[0].id.isNotBlank())
    }

    @Test
    fun `parseRecommendations maps the actions with fresh ids and an OPEN status`() {
        val output = """
            [
              {
                "title": "Add OkHttp instrumentation",
                "description": "No network spans are recorded.",
                "severity": "HIGH",
                "actions": [
                  {"action_label": "Open a PR", "description": "Add the Sentry OkHttp interceptor.", "actionable_for_seer": true},
                  {
                    "action_label": "Open dashboard",
                    "description": "Compare against production.",
                    "link": "https://sentry.io/dashboard/1"
                  }
                ]
              }
            ]
        """.trimIndent()

        val actions = parseRecommendations(output, json).single().actions

        assertEquals(listOf("Open a PR", "Open dashboard"), actions.map { it.actionLabel })
        assertEquals("Add the Sentry OkHttp interceptor.", actions[0].description)
        assertEquals(null, actions[0].link)
        assertEquals("https://sentry.io/dashboard/1", actions[1].link)
        assertEquals(true, actions[0].actionableForSeer)
        assertEquals(false, actions[1].actionableForSeer)
        assertEquals(ActionStatus.OPEN, actions[0].status)
        assertNotEquals(actions[0].id, actions[1].id)
        assertTrue(actions[0].id.isNotBlank())
    }

    @Test
    fun `parseRecommendations maps the performance characteristics`() {
        val output = """
            [
              {
                "title": "Optimize the db query",
                "description": "It is slower than production.",
                "performance_characteristics": {
                  "span.op": "db.sql.query",
                  "link": "https://sentry.io/explore/traces/?query=db",
                  "duration": 820,
                  "avg": 120,
                  "p50": 90,
                  "p75": 140,
                  "p90": 210,
                  "p95": 300
                }
              }
            ]
        """.trimIndent()

        val performance = parseRecommendations(output, json).single().performanceCharacteristics!!

        assertEquals("db.sql.query", performance.spanOp)
        assertEquals("https://sentry.io/explore/traces/?query=db", performance.link)
        assertEquals(820.0, performance.duration)
        assertEquals(120.0, performance.avg)
        assertEquals(90.0, performance.p50)
        assertEquals(140.0, performance.p75)
        assertEquals(210.0, performance.p90)
        assertEquals(300.0, performance.p95)
    }

    @Test
    fun `parseRecommendations gives no performance characteristics when the answer has none`() {
        val output = """[{"title": "T", "description": "D"}]"""

        assertEquals(null, parseRecommendations(output, json).single().performanceCharacteristics)
    }

    @Test
    fun `parseRecommendations skips performance characteristics it cannot decode and keeps the recommendation`() {
        val output = """[{"title": "T", "description": "D", "performance_characteristics": "not an object"}]"""

        val recommendation = parseRecommendations(output, json).single()

        assertEquals("T", recommendation.title)
        assertEquals(null, recommendation.performanceCharacteristics)
    }

    @Test
    fun `parseRecommendations gives no actions when the answer has none`() {
        val output = """[{"title": "T", "description": "D", "severity": "LOW"}]"""

        assertEquals(emptyList(), parseRecommendations(output, json).single().actions)
    }

    @Test
    fun `parseRecommendations skips an action that cannot be decoded and keeps its recommendation`() {
        val output = """
            [
              {
                "title": "T",
                "description": "D",
                "actions": [
                  {"description": "no label at all"},
                  {"action_label": "Open a PR", "description": "Do it."}
                ]
              }
            ]
        """.trimIndent()

        val recommendation = parseRecommendations(output, json).single()

        assertEquals("T", recommendation.title)
        assertEquals(listOf("Open a PR"), recommendation.actions.map { it.actionLabel })
    }

    @Test
    fun `parseRecommendations tolerates markdown fences and surrounding prose`() {
        val output = "Here you are:\n```json\n[{\"title\": \"T\", \"description\": \"D\", \"severity\": \"LOW\"}]\n```\nHope that helps."

        val recommendations = parseRecommendations(output, json)

        assertEquals(1, recommendations.size)
        assertEquals("T", recommendations.single().title)
    }

    @Test
    fun `parseRecommendations takes the fenced block even when the prose has a stray bracket`() {
        val output = """
            First, [1] the checkout is slow. Here is the answer:

            ```json
            [{"title": "T", "description": "D", "severity": "LOW"}]
            ```

            I hope that helps [see above].
        """.trimIndent()

        val recommendations = parseRecommendations(output, json)

        assertEquals(1, recommendations.size)
        assertEquals("T", recommendations.single().title)
    }

    @Test
    fun `parseRecommendations keeps the elements that decode and skips the ones that do not`() {
        val output = """
            [
              {"title": "Good one", "description": "D", "severity": "HIGH"},
              {"description": "no title at all"},
              {"title": "Second good one", "description": "D"}
            ]
        """.trimIndent()

        val recommendations = parseRecommendations(output, json)

        assertEquals(listOf("Good one", "Second good one"), recommendations.map { it.title })
    }

    @Test
    fun `parseRecommendations reads a severity case-insensitively and falls back to MEDIUM`() {
        val output = """
            [
              {"title": "A", "description": "D", "severity": "high"},
              {"title": "B", "description": "D", "severity": "catastrophic"}
            ]
        """.trimIndent()

        val recommendations = parseRecommendations(output, json)

        assertEquals(Severity.HIGH, recommendations[0].severity)
        assertEquals(Severity.MEDIUM, recommendations[1].severity)
    }

    @Test
    fun `parseRecommendations throws when no element of the array can be decoded`() {
        assertFailsWith<IllegalStateException> { parseRecommendations("""[{"nonsense": 1}]""", json) }
    }

    @Test
    fun `parseRecommendations returns an empty list for an empty array`() {
        assertEquals(emptyList(), parseRecommendations("[]", json))
    }

    @Test
    fun `enrich starts a run, polls until completed, and appends the recommendations`() = runBlocking {
        val client = clientOf(
            """{"run_id": 42, "sentry_run_id": "uuid"}""" to HttpStatusCode.OK,
            """{"session": {"run_id": 42, "status": "processing", "blocks": []}}""" to HttpStatusCode.OK,
            """{"session": {"run_id": 42, "status": "completed", "blocks": [
                 {"id": "b1", "message": {"role": "assistant", "content": "thinking"}, "loading": true},
                 {"id": "b2", "message": {"role": "assistant", "content": "[{\"title\": \"T\", \"description\": \"D\", \"severity\": \"LOW\"}]"}, "loading": false}
               ]}}""" to HttpStatusCode.OK
        )

        val result = enrichmentWith(client).enrich(sampleRequest(), sampleResponse())

        assertEquals(1, result.recommendations.size)
        assertEquals("T", result.recommendations.single().title)
    }

    @Test
    fun `enrich retries while the run is not yet created`() = runBlocking {
        val client = clientOf(
            """{"run_id": 42, "sentry_run_id": "uuid"}""" to HttpStatusCode.OK,
            """{"detail": "This run is still being created; retry shortly."}""" to HttpStatusCode.Conflict,
            """{"session": {"run_id": 42, "status": "completed", "blocks": [
                 {"id": "b1", "message": {"role": "assistant", "content": "[]"}, "loading": false}
               ]}}""" to HttpStatusCode.OK
        )

        val result = enrichmentWith(client).enrich(sampleRequest(), sampleResponse())

        assertEquals(emptyList(), result.recommendations)
    }

    @Test
    fun `enrich throws when the run ends with an error status`() = runBlocking {
        val client = clientOf(
            """{"run_id": 42, "sentry_run_id": "uuid"}""" to HttpStatusCode.OK,
            """{"session": {"run_id": 42, "status": "error", "blocks": []}}""" to HttpStatusCode.OK
        )

        assertFailsWith<IllegalStateException> {
            enrichmentWith(client).enrich(sampleRequest(), sampleResponse())
        }
        Unit
    }

    @Test
    fun `enrich throws when the start call is denied`() = runBlocking {
        val client = clientOf(
            """{"detail": "A user account is required to continue a conversation."}""" to HttpStatusCode.Forbidden
        )

        assertFailsWith<IllegalStateException> {
            enrichmentWith(client).enrich(sampleRequest(), sampleResponse())
        }
        Unit
    }

    @Test
    fun `enrich throws when the answer is not valid JSON`() = runBlocking {
        val client = clientOf(
            """{"run_id": 42, "sentry_run_id": "uuid"}""" to HttpStatusCode.OK,
            """{"session": {"run_id": 42, "status": "completed", "blocks": [
                 {"id": "b1", "message": {"role": "assistant", "content": "Sure, here are some recommendations: not json"}, "loading": false}
               ]}}""" to HttpStatusCode.OK
        )

        assertFailsWith<IllegalStateException> {
            enrichmentWith(client).enrich(sampleRequest(), sampleResponse())
        }
        Unit
    }
}
