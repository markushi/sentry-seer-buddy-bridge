package io.sentry.buddy

import io.sentry.buddy.flow.CompositeRecommendationEngine
import io.sentry.buddy.flow.FlowAnalysisEvent
import io.sentry.buddy.flow.FlowAnalysisRequest
import io.sentry.buddy.flow.Recommendation
import io.sentry.buddy.flow.RecommendationEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class CompositeRecommendationEngineTest {

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

    @Test
    fun `concatenates recommendations from every source`() = runBlocking {
        val sourceA = RecommendationEngine { _, _ -> listOf(Recommendation(id = "a", title = "A", description = "a")) }
        val sourceB = RecommendationEngine { _, _ -> listOf(Recommendation(id = "b", title = "B", description = "b")) }
        val composite = CompositeRecommendationEngine(listOf(sourceA, sourceB))

        val recommendations = composite.generateRecommendations(sampleRequest(), emptyList())

        assertEquals(listOf("a", "b"), recommendations.map { it.id })
    }

    @Test
    fun `returns an empty list when there are no sources`() = runBlocking {
        val composite = CompositeRecommendationEngine(emptyList())

        assertEquals(emptyList(), composite.generateRecommendations(sampleRequest(), emptyList()))
    }
}
