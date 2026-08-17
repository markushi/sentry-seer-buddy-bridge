package io.sentry.buddy

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.sentry.buddy.flow.FlowAnalysisEvent
import io.sentry.buddy.flow.FlowAnalysisRequest
import io.sentry.buddy.tooling.SentryApiClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class SentryApiClientTest {

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

    @Test
    fun `organizationSlugFrom extracts the org from a standard ingest DSN`() {
        val client = SentryApiClient(authToken = "token")

        assertEquals("123", client.organizationSlugFrom("https://examplekey@o123.ingest.sentry.io/456"))
    }

    @Test
    fun `organizationSlugFrom strips the leading o from a numeric ingest-host org id`() {
        val client = SentryApiClient(authToken = "token")

        assertEquals("447951", client.organizationSlugFrom("https://examplekey@o447951.ingest.sentry.io/456"))
    }

    @Test
    fun `organizationSlugFrom returns null for an unparseable dsn`() {
        val client = SentryApiClient(authToken = "token")

        assertEquals(null, client.organizationSlugFrom("not a uri"))
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
        val client = SentryApiClient(authToken = "token", httpClient = httpClient)

        val issues = client.fetchIssues(sampleRequest())

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
        val client = SentryApiClient(authToken = "token", httpClient = httpClient)

        val issues = client.fetchIssues(sampleRequest(dsn = "not a uri"))

        assertEquals(emptyList(), issues)
    }
}
