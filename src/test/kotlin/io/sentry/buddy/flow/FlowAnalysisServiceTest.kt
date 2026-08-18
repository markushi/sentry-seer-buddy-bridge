package io.sentry.buddy.flow

import io.sentry.buddy.enrichment.Enrichment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FlowAnalysisServiceTest {

    private fun newService(
        store: FlowAnalysisStore = FlowAnalysisStore(createTempDirectory("flow-service-test").toFile()),
        enrichments: List<Enrichment> = listOf(Enrichment { _, response -> response.copy(title = "Test title") })
    ): FlowAnalysisService = FlowAnalysisService(
        store = store,
        enrichments = enrichments,
        scope = CoroutineScope(Dispatchers.Unconfined)
    )

    private fun sampleRequest(flowId: String = "flow-1") = FlowAnalysisRequest(
        flowId = flowId,
        traceIds = listOf("trace-1"),
        startTimeMs = 1000L,
        endTimeMs = 2000L,
        dsn = "https://key@sentry.io/1",
        userAnnotation = "tapped checkout twice",
        sdk = "io.sentry.android@8.40.0",
        events = listOf(FlowAnalysisEvent(type = "click", timestamp = 1500L, data = JsonObject(emptyMap())))
    )

    @Test
    fun `submit accepts as PROCESSING then completes with a title`() {
        val service = newService()

        val accepted = service.submitOrGetExisting(sampleRequest())
        assertEquals(AnalysisStatus.PROCESSING, accepted.status)

        val result = service.get("flow-1")
        assertNotNull(result)
        assertEquals(AnalysisStatus.COMPLETED, result.status)
        assertEquals("Test title", result.title)
    }

    @Test
    fun `resubmitting the same flow_id returns the existing result instead of reprocessing`() {
        val service = newService()
        service.submitOrGetExisting(sampleRequest())
        val first = service.get("flow-1")

        val second = service.submitOrGetExisting(sampleRequest())

        assertEquals(first, second)
    }

    @Test
    fun `pipeline failure marks the flow as FAILED with the error message`() {
        val service = newService(enrichments = listOf(Enrichment { _, _ -> throw IllegalStateException("boom") }))

        service.submitOrGetExisting(sampleRequest())

        val result = service.get("flow-1")
        assertNotNull(result)
        assertEquals(AnalysisStatus.FAILED, result.status)
        assertEquals("boom", result.error)
    }

    @Test
    fun `enrichments run in order, each building on the previous response`() {
        val service = newService(
            enrichments = listOf(
                Enrichment { _, response -> response.copy(title = "First") },
                Enrichment { _, response -> response.copy(title = response.title + " then second") }
            )
        )

        service.submitOrGetExisting(sampleRequest())

        val result = service.get("flow-1")
        assertNotNull(result)
        assertEquals("First then second", result.title)
    }

    @Test
    fun `resolveRecommendation marks a resolvable recommendation as RESOLVED`() {
        val store = FlowAnalysisStore(createTempDirectory("flow-resolve-test").toFile())
        store.saveResult(
            FlowAnalysisResponse(
                flowId = "flow-1",
                status = AnalysisStatus.COMPLETED,
                recommendations = listOf(
                    Recommendation(id = "rec-1", title = "Upgrade SDK", description = "...", resolvable = true)
                )
            )
        )
        val service = newService(store = store)

        val outcome = service.resolveRecommendation("flow-1", "rec-1")

        assertTrue(outcome is ResolveOutcome.Success)
        val resolved = outcome.response.recommendations.single()
        assertEquals(RecommendationStatus.RESOLVED, resolved.status)
    }

    @Test
    fun `resolveRecommendation returns NotResolvable for a non-resolvable recommendation`() {
        val store = FlowAnalysisStore(createTempDirectory("flow-resolve-test-2").toFile())
        store.saveResult(
            FlowAnalysisResponse(
                flowId = "flow-1",
                status = AnalysisStatus.COMPLETED,
                recommendations = listOf(
                    Recommendation(id = "rec-1", title = "x", description = "y", resolvable = false)
                )
            )
        )
        val service = newService(store = store)

        assertEquals(ResolveOutcome.NotResolvable, service.resolveRecommendation("flow-1", "rec-1"))
    }

    @Test
    fun `resolveRecommendation returns FlowAnalysisNotFound for an unknown flow`() {
        val service = newService()

        assertEquals(ResolveOutcome.FlowAnalysisNotFound, service.resolveRecommendation("unknown", "rec-1"))
    }

    @Test
    fun `resolveRecommendation returns RecommendationNotFound for an unknown recommendation id`() {
        val store = FlowAnalysisStore(createTempDirectory("flow-resolve-test-3").toFile())
        store.saveResult(FlowAnalysisResponse(flowId = "flow-1", status = AnalysisStatus.COMPLETED))
        val service = newService(store = store)

        assertEquals(ResolveOutcome.RecommendationNotFound, service.resolveRecommendation("flow-1", "unknown"))
    }
}
