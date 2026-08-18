package io.sentry.buddy.enrichment

import io.sentry.buddy.flow.AnalysisStatus
import io.sentry.buddy.flow.FlowAnalysisEvent
import io.sentry.buddy.flow.FlowAnalysisRequest
import io.sentry.buddy.flow.FlowAnalysisResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class TitleEnrichmentTest {

    private fun sampleRequest(userAnnotation: String = "Flow: Checkout") = FlowAnalysisRequest(
        flowId = "flow-1",
        traceIds = listOf("trace-1"),
        startTimeMs = 1000L,
        endTimeMs = 2000L,
        dsn = "https://key@sentry.io/1",
        userAnnotation = userAnnotation,
        sdk = "io.sentry.android@8.40.0",
        events = listOf(FlowAnalysisEvent(type = "click", timestamp = 1500L, data = JsonObject(emptyMap())))
    )

    private fun emptyResponse() = FlowAnalysisResponse(flowId = "flow-1", status = AnalysisStatus.PROCESSING)

    @Test
    fun `enrich uses generated title`() = runBlocking {
        val enrichment = TitleEnrichment { "Generated title" }

        val enriched = enrichment.enrich(sampleRequest(), emptyResponse())

        assertEquals("Generated title", enriched.title)
    }

    @Test
    fun `enrich falls back when title generation fails`() = runBlocking {
        val enrichment = TitleEnrichment { throw IllegalStateException("claude auth failed") }

        val enriched = enrichment.enrich(sampleRequest("Flow: Checkout availability"), emptyResponse())

        assertEquals("Checkout availability", enriched.title)
        assertEquals(AnalysisStatus.PROCESSING, enriched.status)
    }

    @Test
    fun `enrich falls back to untitled flow when annotation has no title`() = runBlocking {
        val enrichment = TitleEnrichment { "" }

        val enriched = enrichment.enrich(sampleRequest("Flow: \nFocus areas: Network timing"), emptyResponse())

        assertEquals("Untitled flow", enriched.title)
    }
}
