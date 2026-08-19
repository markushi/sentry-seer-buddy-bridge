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
    fun `the api encodes the defaults of a recommendation, including an empty actions list`() {
        val encoded = appJson.encodeToString(
            Recommendation.serializer(),
            Recommendation(id = "rec-1", title = "T", description = "D")
        )

        assertTrue(encoded.contains("\"status\":\"OPEN\""), "expected the status in $encoded")
        assertTrue(encoded.contains("\"severity\":\"MEDIUM\""), "expected the severity in $encoded")
        assertTrue(encoded.contains("\"actions\":[]"), "expected an empty actions list in $encoded")
    }

    @Test
    fun `a recommendation encodes its performance characteristics as performance_characteristics`() {
        val recommendation = Recommendation(
            id = "rec-1",
            title = "T",
            description = "D",
            performanceCharacteristics = PerformanceCharacteristics(
                spanOp = "db.sql.query",
                link = "https://sentry.io/explore/traces/?query=db",
                duration = "820ms",
                avg = "120ms",
                p50 = "90ms",
                p75 = "140ms",
                p90 = "210ms",
                p95 = "300ms"
            )
        )

        val encoded = appJson.encodeToString(Recommendation.serializer(), recommendation)

        assertTrue(encoded.contains("\"performance_characteristics\""), "expected the key in $encoded")
        assertTrue(encoded.contains("\"span_op\":\"db.sql.query\""), "expected span_op in $encoded")
        assertTrue(encoded.contains("\"p95\":\"300ms\""), "expected p95 in $encoded")
        assertEquals(
            recommendation,
            appJson.decodeFromString(Recommendation.serializer(), encoded)
        )
    }

    @Test
    fun `the api encodes a null performance_characteristics when the recommendation has none`() {
        val encoded = appJson.encodeToString(
            Recommendation.serializer(),
            Recommendation(id = "rec-1", title = "T", description = "D")
        )

        assertTrue(
            encoded.contains("\"performance_characteristics\":null"),
            "expected a null performance_characteristics in $encoded"
        )
    }

    @Test
    fun `the api encodes the defaults of an action, including a null seer_run_url`() {
        val encoded = appJson.encodeToString(
            RecommendationAction.serializer(),
            RecommendationAction(id = "act-1", actionLabel = "Open a PR", description = "Do it.")
        )

        assertTrue(encoded.contains("\"action_label\":\"Open a PR\""), "expected action_label in $encoded")
        assertTrue(encoded.contains("\"status\":\"OPEN\""), "expected the status in $encoded")
        assertTrue(encoded.contains("\"seer_run_url\":null"), "expected a null seer_run_url in $encoded")
    }

    @Test
    fun `an action encodes and decodes its seer run url as seer_run_url`() {
        val json = appJson
        val url = "https://sentry-sdks.sentry.io/issues/?project=1&statsPeriod=10m&explorerRunId=uuid"

        val encoded = json.encodeToString(
            RecommendationAction.serializer(),
            RecommendationAction(
                id = "act-1",
                actionLabel = "Open a PR",
                description = "Do it.",
                seerRunUrl = url
            )
        )
        val decoded = json.decodeFromString(RecommendationAction.serializer(), encoded)

        assertTrue(encoded.contains("\"seer_run_url\""), "expected seer_run_url in $encoded")
        assertEquals(url, decoded.seerRunUrl)
    }

    @Test
    fun `a recommendation round-trips its actions`() {
        val recommendation = Recommendation(
            id = "rec-1",
            title = "T",
            description = "D",
            actions = listOf(
                RecommendationAction(id = "act-1", actionLabel = "Open a PR", description = "Do it."),
                RecommendationAction(
                    id = "act-2",
                    actionLabel = "Open dashboard",
                    description = "Look at it.",
                    link = "https://sentry.io/dashboard/1",
                    status = ActionStatus.EXECUTED
                )
            )
        )

        val encoded = appJson.encodeToString(Recommendation.serializer(), recommendation)

        assertEquals(recommendation, appJson.decodeFromString(Recommendation.serializer(), encoded))
    }

    @Test
    fun `a recommendation can only be OPEN or DISMISSED`() {
        assertEquals(listOf("OPEN", "DISMISSED"), RecommendationStatus.entries.map { it.name })
        assertEquals(listOf("OPEN", "EXECUTED"), ActionStatus.entries.map { it.name })
    }
}
