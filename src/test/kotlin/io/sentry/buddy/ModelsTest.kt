package io.sentry.buddy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

        val request = appJson.decodeFromString(FlowAnalysisRequest.serializer(), json)

        assertEquals("flow-1", request.flowId)
        assertEquals(listOf("trace-1", "trace-2"), request.traceIds)
        assertEquals(1000L, request.startTimeMs)
        assertEquals("tapped checkout twice", request.userAnnotation)
        assertEquals("click", request.events.single().type)

        val reencoded = appJson.encodeToString(FlowAnalysisRequest.serializer(), request)
        val roundTripped = appJson.decodeFromString(FlowAnalysisRequest.serializer(), reencoded)
        assertEquals(request, roundTripped)
    }

    @Test
    fun `FlowAnalysisResponse defaults recommendations and issues to empty lists`() {
        val response = FlowAnalysisResponse(flowId = "flow-1", status = AnalysisStatus.PROCESSING)

        assertEquals(emptyList(), response.recommendations)
        assertEquals(emptyList(), response.issues)
        assertEquals(emptyList(), response.enrichmentErrors)
    }

    @Test
    fun `the api encodes the defaults of a recommendation, including a null seer_run_url`() {
        val encoded = appJson.encodeToString(
            Recommendation.serializer(),
            Recommendation(id = "rec-1", title = "T", description = "D")
        )

        assertTrue(encoded.contains("\"status\":\"OPEN\""), "expected the status in $encoded")
        assertTrue(encoded.contains("\"severity\":\"MEDIUM\""), "expected the severity in $encoded")
        assertTrue(encoded.contains("\"resolvable\":true"), "expected resolvable in $encoded")
        assertTrue(encoded.contains("\"seer_run_url\":null"), "expected a null seer_run_url in $encoded")
    }

    @Test
    fun `a recommendation encodes and decodes its seer run url as seer_run_url`() {
        val json = appJson
        val url = "https://sentry-sdks.sentry.io/issues/?project=1&statsPeriod=10m&explorerRunId=uuid"

        val encoded = json.encodeToString(
            Recommendation.serializer(),
            Recommendation(id = "rec-1", title = "T", description = "D", seerRunUrl = url)
        )
        val decoded = json.decodeFromString(Recommendation.serializer(), encoded)

        assertTrue(encoded.contains("\"seer_run_url\""), "expected seer_run_url in $encoded")
        assertEquals(url, decoded.seerRunUrl)
    }
}