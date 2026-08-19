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
import io.sentry.buddy.Recommendation
import io.sentry.buddy.Severity
import io.sentry.buddy.sdk.SdkUpgradeAdvisor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
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

    private fun advisorWithLatestRelease(tagName: String): SdkUpgradeAdvisor {
        val mockEngine = MockEngine { _ ->
            respond(
                content = """{"url": "https://api.github.com/releases/1", "tag_name": "$tagName"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = HttpClient(mockEngine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        return SdkUpgradeAdvisor(httpClient = client)
    }

    @Test
    fun `enrich appends an upgrade recommendation when outdated`() = runBlocking {
        val enrichment = SdkUpgradeEnrichment(advisorWithLatestRelease("8.41.0"))

        val enriched = enrichment.enrich(sampleRequest("io.sentry.android@8.40.0"), emptyResponse())

        assertEquals(1, enriched.recommendations.size)
        assertTrue(enriched.recommendations.single().title.contains("8.41.0"))
    }

    @Test
    fun `enrich preserves recommendations already on the response`() = runBlocking {
        val enrichment = SdkUpgradeEnrichment(advisorWithLatestRelease("8.40.0"))

        val existingRecommendation = Recommendation(
            id = "existing-rec",
            title = "Existing recommendation",
            description = "This was added by a previous enrichment",
            link = "https://example.com",
            severity = Severity.LOW
        )
        val responseWithRecommendation = emptyResponse().copy(recommendations = listOf(existingRecommendation))

        val enriched = enrichment.enrich(sampleRequest("io.sentry.android@8.40.0"), responseWithRecommendation)

        assertEquals(1, enriched.recommendations.size)
        assertEquals("Existing recommendation", enriched.recommendations.single().title)
    }
}
