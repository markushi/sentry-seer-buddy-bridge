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
import io.sentry.buddy.flow.AnalysisStatus
import io.sentry.buddy.flow.FlowAnalysisEvent
import io.sentry.buddy.flow.FlowAnalysisRequest
import io.sentry.buddy.flow.FlowAnalysisResponse
import io.sentry.buddy.tooling.SdkUpgradeEnrichment
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SdkUpgradeEnrichmentTest {

    private fun sampleRequest(sdk: String) = FlowAnalysisRequest(
        flowId = "flow-1",
        traceIds = listOf("trace-1"),
        startTimeMs = 1000L,
        endTimeMs = 2000L,
        dsn = "https://key@o123.ingest.sentry.io/456",
        userAnnotation = "tapped checkout twice",
        sdk = sdk,
        events = listOf(FlowAnalysisEvent(type = "click", timestamp = 1500L, data = JsonObject(emptyMap())))
    )

    private fun emptyResponse() = FlowAnalysisResponse(flowId = "flow-1", status = AnalysisStatus.PROCESSING)

    private fun mockClient(tagName: String): HttpClient {
        val mockEngine = MockEngine { _ ->
            respond(
                content = """{"tag_name": "$tagName"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        return HttpClient(mockEngine) { install(ContentNegotiation) { json() } }
    }

    @Test
    fun `parseSdkVersion extracts the version after the @`() {
        val enrichment = SdkUpgradeEnrichment()

        assertEquals("8.40.0", enrichment.parseSdkVersion("io.sentry.android@8.40.0"))
    }

    @Test
    fun `parseSdkVersion returns null when there is no @`() {
        val enrichment = SdkUpgradeEnrichment()

        assertEquals(null, enrichment.parseSdkVersion("io.sentry.android"))
    }

    @Test
    fun `isOutdated is true when the latest release has a higher version`() {
        val enrichment = SdkUpgradeEnrichment()

        assertTrue(enrichment.isOutdated(current = "8.40.0", latest = "8.41.0"))
    }

    @Test
    fun `isOutdated is false when current already matches latest`() {
        val enrichment = SdkUpgradeEnrichment()

        assertTrue(!enrichment.isOutdated(current = "8.41.0", latest = "8.41.0"))
    }

    @Test
    fun `isOutdated treats missing trailing components as zero`() {
        val enrichment = SdkUpgradeEnrichment()

        assertTrue(!enrichment.isOutdated(current = "8.41.0", latest = "8.41"))
    }

    @Test
    fun `enrich appends an upgrade recommendation when outdated`() = runBlocking {
        val enrichment = SdkUpgradeEnrichment(httpClient = mockClient("8.41.0"))

        val enriched = enrichment.enrich(sampleRequest("io.sentry.android@8.40.0"), emptyResponse())

        assertEquals(1, enriched.recommendations.size)
        assertTrue(enriched.recommendations.single().title.contains("8.41.0"))
    }

    @Test
    fun `enrich preserves recommendations already on the response`() = runBlocking {
        val enrichment = SdkUpgradeEnrichment(httpClient = mockClient("8.40.0"))

        val enriched = enrichment.enrich(sampleRequest("io.sentry.android@8.40.0"), emptyResponse())

        assertEquals(emptyList(), enriched.recommendations)
    }
}
