package io.sentry.buddy.flow

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import io.sentry.buddy.AnalysisStatus
import io.sentry.buddy.FlowAnalysisEvent
import io.sentry.buddy.FlowAnalysisRequest
import io.sentry.buddy.FlowAnalysisResponse
import io.sentry.buddy.Recommendation
import io.sentry.buddy.RecommendationStatus
import io.sentry.buddy.endpoints.flow.FlowAnalysisService
import io.sentry.buddy.endpoints.flow.FlowAnalysisStore
import io.sentry.buddy.endpoints.flow.flowAnalysisRoutes
import io.sentry.buddy.enrichment.Enrichment
import io.sentry.buddy.seer.SeerClient
import io.sentry.buddy.seer.seerJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class FlowAnalysisRoutesTest {

    private fun requestJson(flowId: String) = """
        {
          "flow_id": "$flowId",
          "trace_ids": ["trace-1"],
          "start_time_ms": 1000,
          "end_time_ms": 2000,
          "dsn": "https://key@sentry.io/1",
          "user_annotation": "tapped checkout twice",
          "sdk": "io.sentry.android@8.40.0",
          "events": [{"type": "click", "timestamp": 1500, "data": {}}]
        }
    """.trimIndent()

    private fun newTestService(
        store: FlowAnalysisStore = FlowAnalysisStore(createTempDirectory("flow-routes-test").toFile())
    ) = FlowAnalysisService(
        store = store,
        enrichments = listOf(Enrichment { _, response -> response.copy(title = "Test title") }),
        scope = CoroutineScope(Dispatchers.Unconfined)
    )

    @Test
    fun `POST v1 flow-analysis returns 202 with PROCESSING then COMPLETED status`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            flowAnalysisRoutes(newTestService())
        }

        val response = client.post("/v1/flow-analysis") {
            contentType(ContentType.Application.Json)
            setBody(requestJson("flow-1"))
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText())
        assertEquals("\"PROCESSING\"", body.jsonObject["status"].toString())
    }

    @Test
    fun `GET v1 flow-analysis id returns COMPLETED after the synchronous pipeline finishes`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            flowAnalysisRoutes(newTestService())
        }

        client.post("/v1/flow-analysis") {
            contentType(ContentType.Application.Json)
            setBody(requestJson("flow-2"))
        }

        val response = client.get("/v1/flow-analysis/flow-2")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText())
        assertEquals("\"COMPLETED\"", body.jsonObject["status"].toString())
        assertEquals("\"Test title\"", body.jsonObject["title"].toString())
    }

    @Test
    fun `GET v1 flow-analysis unknown id returns 404`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            flowAnalysisRoutes(newTestService())
        }

        val response = client.get("/v1/flow-analysis/does-not-exist")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `POST v1 flow-analysis with blank flow_id returns 400`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            flowAnalysisRoutes(newTestService())
        }

        val response = client.post("/v1/flow-analysis") {
            contentType(ContentType.Application.Json)
            setBody(requestJson(""))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST v1 flow-analysis with path traversal flow_id returns 400`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            flowAnalysisRoutes(newTestService())
        }

        val response = client.post("/v1/flow-analysis") {
            contentType(ContentType.Application.Json)
            setBody(requestJson("../../../etc/passwd"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `GET v1 flow-analysis with a path traversal id returns 400`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            flowAnalysisRoutes(newTestService())
        }

        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/flow-analysis/..").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/flow-analysis/a%2Fb").status)
    }

    @Test
    fun `GET v1 flow-analysis for an unknown id creates no directory`() = testApplication {
        val dataDir = createTempDirectory("flow-routes-no-dir").toFile()
        application {
            install(ContentNegotiation) { json() }
            flowAnalysisRoutes(newTestService(FlowAnalysisStore(dataDir)))
        }

        assertEquals(HttpStatusCode.NotFound, client.get("/v1/flow-analysis/does-not-exist").status)

        assertEquals(emptyList(), dataDir.list()!!.toList(), "a read must not create a directory")
    }

    @Test
    fun `POST resolve answers 502 with a short reason when the Seer run cannot start`() = testApplication {
        val store = FlowAnalysisStore(createTempDirectory("flow-routes-resolve-fail").toFile())
        store.saveRequest(
            FlowAnalysisRequest(
                flowId = "flow-4",
                traceIds = listOf("trace-1"),
                startTimeMs = 1000L,
                endTimeMs = 2000L,
                dsn = "https://key@sentry.io/1",
                userAnnotation = "tapped checkout twice",
                sdk = "io.sentry.android@8.40.0",
                events = listOf(FlowAnalysisEvent(type = "click", timestamp = 1500L, data = JsonObject(emptyMap())))
            )
        )
        store.saveResult(
            FlowAnalysisResponse(
                flowId = "flow-4",
                status = AnalysisStatus.COMPLETED,
                recommendations = listOf(Recommendation(id = "rec-1", title = "T", description = "D"))
            )
        )
        val seerClient = SeerClient(
            authToken = "token",
            org = "sentry-sdks",
            httpClient = HttpClient(
                MockEngine { _ ->
                    respond(
                        content = """{"detail": "Organization does not have the seer-explorer feature"}""",
                        status = HttpStatusCode.Forbidden,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            ) { install(ClientContentNegotiation) { json(seerJson) } }
        )
        application {
            install(ContentNegotiation) { json() }
            flowAnalysisRoutes(
                FlowAnalysisService(
                    store = store,
                    scope = CoroutineScope(Dispatchers.Unconfined),
                    seerClient = seerClient
                )
            )
        }

        val response = client.post("/v1/flow-analysis/flow-4/recommendations/rec-1/resolve")

        assertEquals(HttpStatusCode.BadGateway, response.status)
        val body = response.bodyAsText()
        assertEquals("""{"error":"could not start the Seer run"}""", body)
        assertEquals(RecommendationStatus.OPEN, store.loadResult("flow-4")!!.recommendations.single().status)
    }

    @Test
    fun `POST resolve answers with the updated recommendation only`() = testApplication {
        val store = FlowAnalysisStore(createTempDirectory("flow-routes-resolve").toFile())
        store.saveResult(
            FlowAnalysisResponse(
                flowId = "flow-3",
                status = AnalysisStatus.COMPLETED,
                recommendations = listOf(Recommendation(id = "rec-1", title = "T", description = "D"))
            )
        )
        application {
            install(ContentNegotiation) { json() }
            flowAnalysisRoutes(
                FlowAnalysisService(store = store, scope = CoroutineScope(Dispatchers.Unconfined))
            )
        }

        val response = client.post("/v1/flow-analysis/flow-3/recommendations/rec-1/resolve")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("\"rec-1\"", body["id"].toString())
        assertEquals("\"RESOLVED\"", body["status"].toString())
        assertEquals(null, body["flow_id"], "the answer is the recommendation, not the whole analysis")
    }
}
