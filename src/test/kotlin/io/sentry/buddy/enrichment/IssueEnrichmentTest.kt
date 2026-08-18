package io.sentry.buddy.enrichment

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.sentry.buddy.AnalysisStatus
import io.sentry.buddy.FlowAnalysisEvent
import io.sentry.buddy.FlowAnalysisRequest
import io.sentry.buddy.FlowAnalysisResponse
import io.sentry.buddy.seer.seerJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class IssueEnrichmentTest {

    private fun sampleRequest(dsn: String = "https://examplekey@o123.ingest.sentry.io/456") = FlowAnalysisRequest(
        flowId = "flow-1",
        traceIds = listOf("trace-1"),
        startTimeMs = 1000L,
        endTimeMs = 2000L,
        dsn = dsn,
        userAnnotation = "tapped checkout twice",
        sdk = "io.sentry.android@8.40.0",
        events = listOf(FlowAnalysisEvent(type = "click", timestamp = 1500L, data = JsonObject(emptyMap())))
    )

    private fun emptyResponse() = FlowAnalysisResponse(flowId = "flow-1", status = AnalysisStatus.PROCESSING)

    @Test
    fun `organizationSlugFrom extracts the org from a standard ingest DSN`() {
        assertEquals("123", organizationSlugFrom("https://examplekey@o123.ingest.sentry.io/456"))
    }

    @Test
    fun `organizationSlugFrom strips the leading o from a numeric ingest-host org id`() {
        assertEquals("447951", organizationSlugFrom("https://examplekey@o447951.ingest.sentry.io/456"))
    }

    @Test
    fun `organizationSlugFrom returns null for an unparseable dsn`() {
        assertEquals(null, organizationSlugFrom("not a uri"))
    }

    @Test
    fun `fetchIssues parses events, dedups by group, ranks by level then count, caps at 10`() = runBlocking {
        val responseJson = """
            [
              {"id": "e1", "groupID": "g1", "title": "NPE in checkout", "culprit": "Checkout.submit", "level": "error", "permalink": "https://sentry.io/g1"},
              {"id": "e2", "groupID": "g1", "title": "NPE in checkout", "culprit": "Checkout.submit", "level": "error", "permalink": "https://sentry.io/g1"},
              {"id": "e3", "groupID": "g2", "title": "Network timeout", "culprit": "Api.fetch", "level": "warning", "permalink": "https://sentry.io/g2"}
            ]
        """.trimIndent()
        val mockEngine = MockEngine { _ ->
            respond(
                content = responseJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val httpClient = HttpClient(mockEngine) { install(ContentNegotiation) { json() } }
        val enrichment = IssueEnrichment(authToken = "token", httpClient = httpClient)

        val issues = enrichment.fetchIssues(sampleRequest())

        assertEquals(2, issues.size)
        assertEquals("g1", issues[0].id)
        assertEquals(2, issues[0].count)
        assertEquals("g2", issues[1].id)
        assertEquals(1, issues[1].count)
    }

    @Test
    fun `fetchIssues returns an empty list when the dsn cannot be parsed`() = runBlocking {
        val mockEngine = MockEngine { _ -> respond(content = "[]", status = HttpStatusCode.OK) }
        val httpClient = HttpClient(mockEngine) { install(ContentNegotiation) { json() } }
        val enrichment = IssueEnrichment(authToken = "token", httpClient = httpClient)

        val issues = enrichment.fetchIssues(sampleRequest(dsn = "not a uri"))

        assertEquals(emptyList(), issues)
    }

    @Test
    fun `enrich throws when the events request fails, so the service records an enrichment error`() = runBlocking {
        val mockEngine = MockEngine { _ ->
            respond(
                content = """{"detail": "You do not have permission to perform this action."}""",
                status = HttpStatusCode.Forbidden,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val httpClient = HttpClient(mockEngine) { install(ContentNegotiation) { json(seerJson) } }
        val enrichment = IssueEnrichment(authToken = "token", httpClient = httpClient)

        assertFails { enrichment.enrich(sampleRequest(), emptyResponse()) }
        Unit
    }

    @Test
    fun `enrich sets the response issues from fetchIssues`() = runBlocking {
        val responseJson = """
            [{"id": "e1", "groupID": "g1", "title": "NPE in checkout", "level": "error", "permalink": "https://sentry.io/g1"}]
        """.trimIndent()
        val mockEngine = MockEngine { _ ->
            respond(
                content = responseJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val httpClient = HttpClient(mockEngine) { install(ContentNegotiation) { json() } }
        val enrichment = IssueEnrichment(authToken = "token", httpClient = httpClient)

        val enriched = enrichment.enrich(sampleRequest(), emptyResponse())

        assertEquals(1, enriched.issues.size)
        assertEquals("g1", enriched.issues[0].id)
    }
}
