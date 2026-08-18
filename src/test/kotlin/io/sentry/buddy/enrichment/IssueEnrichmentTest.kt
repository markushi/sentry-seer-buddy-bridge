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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IssueEnrichmentTest {

    private val requestedUrls = mutableListOf<Url>()

    private fun sampleRequest(dsn: String = "https://examplekey@o123.ingest.sentry.io/456") = FlowAnalysisRequest(
        flowId = "flow-1",
        traceIds = listOf("trace-1"),
        startTimeMs = 1787069370664L,
        endTimeMs = 1787069377971L,
        dsn = dsn,
        userAnnotation = "tapped checkout twice",
        sdk = "io.sentry.android@8.40.0",
        events = listOf(FlowAnalysisEvent(type = "click", timestamp = 1500L, data = JsonObject(emptyMap())))
    )

    private fun emptyResponse() = FlowAnalysisResponse(flowId = "flow-1", status = AnalysisStatus.PROCESSING)

    /** The shape the real `organizations/{org}/events/` endpoint answers with. */
    private fun eventsBody(vararg rows: String) = """{"data": [${rows.joinToString(",")}], "meta": {"dataset": "errors"}}"""

    private fun enrichmentOf(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        org: String? = null
    ): IssueEnrichment {
        val engine = MockEngine { request ->
            requestedUrls += request.url
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        return IssueEnrichment(
            authToken = "token",
            httpClient = HttpClient(engine) { install(ContentNegotiation) { json(seerJson) } },
            org = org
        )
    }

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
    fun `fetchIssues asks for the columns the events endpoint needs`() = runBlocking {
        enrichmentOf(eventsBody()).fetchIssues(sampleRequest())

        val fields = requestedUrls.single().parameters.getAll("field")
        assertEquals(listOf("id", "issue.id", "title", "culprit", "level"), fields)
    }

    @Test
    fun `fetchIssues limits the query to the trace and the time window of the flow`() = runBlocking {
        enrichmentOf(eventsBody()).fetchIssues(sampleRequest())

        val parameters = requestedUrls.single().parameters
        assertEquals("trace:trace-1", parameters["query"])
        assertEquals("2026-08-18T16:09:30.664Z", parameters["start"])
        assertEquals("2026-08-18T16:09:37.971Z", parameters["end"])
        assertEquals("-1", parameters["project"])
    }

    @Test
    fun `fetchIssues reads the data array, dedups by issue, and ranks by level then count`() = runBlocking {
        val issues = enrichmentOf(
            eventsBody(
                """{"id": "e1", "issue.id": 111, "title": "NPE in checkout", "culprit": "Checkout.submit", "level": "error"}""",
                """{"id": "e2", "issue.id": 111, "title": "NPE in checkout", "culprit": "Checkout.submit", "level": "error"}""",
                """{"id": "e3", "issue.id": 222, "title": "Network timeout", "culprit": "Api.fetch", "level": "warning"}"""
            )
        ).fetchIssues(sampleRequest())

        assertEquals(2, issues.size)
        assertEquals("111", issues[0].id)
        assertEquals(2, issues[0].count)
        assertEquals("Checkout.submit", issues[0].culprit)
        assertEquals("222", issues[1].id)
        assertEquals(1, issues[1].count)
    }

    @Test
    fun `fetchIssues builds the issue permalink the events endpoint does not give`() = runBlocking {
        val issues = enrichmentOf(
            eventsBody("""{"id": "e1", "issue.id": 4444860701, "title": "Exception", "level": "error"}"""),
            org = "sentry-sdks"
        ).fetchIssues(sampleRequest())

        assertEquals("https://sentry-sdks.sentry.io/issues/4444860701/", issues.single().permalink)
    }

    @Test
    fun `fetchIssues prefers the configured org over the numeric id in the dsn`() = runBlocking {
        enrichmentOf(eventsBody(), org = "sentry-sdks").fetchIssues(sampleRequest())

        assertTrue(
            requestedUrls.single().encodedPath.startsWith("/api/0/organizations/sentry-sdks/events/"),
            requestedUrls.single().toString()
        )
    }

    @Test
    fun `fetchIssues reports the status and the detail when the endpoint rejects the query`() = runBlocking {
        val enrichment = enrichmentOf("""{"detail": "No columns selected"}""", status = HttpStatusCode.BadRequest)

        val error = assertFailsWith<IllegalStateException> { enrichment.fetchIssues(sampleRequest()) }

        assertTrue(error.message!!.contains("400"), error.message!!)
        assertTrue(error.message!!.contains("No columns selected"), error.message!!)
    }

    @Test
    fun `fetchIssues returns an empty list when the dsn cannot be parsed`() = runBlocking {
        val issues = enrichmentOf(eventsBody()).fetchIssues(sampleRequest(dsn = "not a uri"))

        assertEquals(emptyList(), issues)
    }

    @Test
    fun `enrich sets the response issues from fetchIssues`() = runBlocking {
        val enriched = enrichmentOf(
            eventsBody("""{"id": "e1", "issue.id": 111, "title": "NPE in checkout", "level": "error"}""")
        ).enrich(sampleRequest(), emptyResponse())

        assertEquals(1, enriched.issues.size)
        assertEquals("111", enriched.issues[0].id)
    }
}
