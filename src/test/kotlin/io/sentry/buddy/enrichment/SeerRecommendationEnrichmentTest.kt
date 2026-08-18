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
              {"title": "Debounce the checkout button", "description": "It was tapped twice within 200ms.", "severity": "MEDIUM", "resolvable": true},
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
    fun `parseRecommendations tolerates markdown fences and surrounding prose`() {
        val output = "Here you are:\n```json\n[{\"title\": \"T\", \"description\": \"D\", \"severity\": \"LOW\"}]\n```\nHope that helps."

        val recommendations = parseRecommendations(output, json)

        assertEquals(1, recommendations.size)
        assertEquals("T", recommendations.single().title)
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
                 {"id": "b1", "message": "thinking", "loading": true},
                 {"id": "b2", "message": "[{\"title\": \"T\", \"description\": \"D\", \"severity\": \"LOW\"}]", "loading": false}
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
                 {"id": "b1", "message": "[]", "loading": false}
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
                 {"id": "b1", "message": "Sure, here are some recommendations: not json", "loading": false}
               ]}}""" to HttpStatusCode.OK
        )

        assertFailsWith<IllegalStateException> {
            enrichmentWith(client).enrich(sampleRequest(), sampleResponse())
        }
        Unit
    }
}
