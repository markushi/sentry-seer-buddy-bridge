package io.sentry.buddy

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelsTest {

    @Test
    fun `FlowAnalysisRequest round-trips through snake_case JSON keys`() {
        val json = """
            {
              "flow_id": "flow-1",
              "trace_ids": ["trace-1", "trace-2"],
              "start_time_ms": 1000,
              "end_time_ms": 2000,
              "dsn": "https://key@sentry.io/1",
              "user_annotation": "tapped checkout twice",
              "sdk": "io.sentry.android@8.40.0",
              "events": [
                {"type": "click", "timestamp": 1500, "data": {}}
              ]
            }
        """.trimIndent()

        val request = Json.decodeFromString(FlowAnalysisRequest.serializer(), json)

        assertEquals("flow-1", request.flowId)
        assertEquals(listOf("trace-1", "trace-2"), request.traceIds)
        assertEquals(1000L, request.startTimeMs)
        assertEquals("tapped checkout twice", request.userAnnotation)
        assertEquals("click", request.events.single().type)

        val reencoded = Json.Default.encodeToString(FlowAnalysisRequest.serializer(), request)
        val roundTripped = Json.Default.decodeFromString(FlowAnalysisRequest.serializer(), reencoded)
        assertEquals(request, roundTripped)
    }

    @Test
    fun `FlowAnalysisResponse defaults recommendations and issues to empty lists`() {
        val response = FlowAnalysisResponse(flowId = "flow-1", status = AnalysisStatus.PROCESSING)

        assertEquals(emptyList(), response.recommendations)
        assertEquals(emptyList(), response.issues)
        assertEquals(emptyList(), response.enrichmentErrors)
    }
}