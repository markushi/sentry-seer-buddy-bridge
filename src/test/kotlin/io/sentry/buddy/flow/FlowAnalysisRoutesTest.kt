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
import io.sentry.buddy.ActionStatus
import io.sentry.buddy.AnalysisStatus
import io.sentry.buddy.FlowAction
import io.sentry.buddy.FlowAnalysisEvent
import io.sentry.buddy.FlowAnalysisRequest
import io.sentry.buddy.FlowAnalysisResponse
import io.sentry.buddy.Recommendation
import io.sentry.buddy.RecommendationAction
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
import kotlinx.serialization.json.jsonArray
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
        val actions = body.jsonObject["actions"]!!.jsonArray
        assertEquals("\"generate-dashboard\"", actions[0].jsonObject["id"].toString())
        assertEquals("false", actions[0].jsonObject["actionable_for_seer"].toString())
        assertEquals("\"generate-monitors\"", actions[1].jsonObject["id"].toString())
        assertEquals("false", actions[1].jsonObject["actionable_for_seer"].toString())
        assertEquals("\"share-recording-json\"", actions[2].jsonObject["id"].toString())
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

    private fun recommendationWithOneAction() = Recommendation(
        id = "rec-1",
        title = "T",
        description = "D",
        actions = listOf(
            RecommendationAction(id = "act-1", actionLabel = "Open a PR", description = "Do it.")
        )
    )

    private fun flowActions() = listOf(
        FlowAction(
            id = "generate-dashboard",
            actionLabel = "Dashboard",
            actionableForSeer = true,
            description = "Draft a dashboard."
        ),
        FlowAction(
            id = "share-recording-json",
            actionLabel = "Share JSON",
            description = "Share the JSON."
        )
    )

    private fun sampleRequestOf(flowId: String) = FlowAnalysisRequest(
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
    fun `POST execute answers 502 with a short reason when the Seer run cannot start`() = testApplication {
        val store = FlowAnalysisStore(createTempDirectory("flow-routes-execute-fail").toFile())
        store.saveRequest(sampleRequestOf("flow-4"))
        store.saveResult(
            FlowAnalysisResponse(
                flowId = "flow-4",
                status = AnalysisStatus.COMPLETED,
                recommendations = listOf(recommendationWithOneAction())
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

        val response = client.post("/v1/flow-analysis/flow-4/recommendations/rec-1/actions/act-1/execute")

        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertEquals("""{"error":"could not start the Seer run"}""", response.bodyAsText())
        assertEquals(
            ActionStatus.OPEN,
            store.loadResult("flow-4")!!.recommendations.single().actions.single().status
        )
    }

    @Test
    fun `POST execute answers with the updated action only`() = testApplication {
        val store = FlowAnalysisStore(createTempDirectory("flow-routes-execute").toFile())
        store.saveResult(
            FlowAnalysisResponse(
                flowId = "flow-3",
                status = AnalysisStatus.COMPLETED,
                recommendations = listOf(recommendationWithOneAction())
            )
        )
        application {
            install(ContentNegotiation) { json() }
            flowAnalysisRoutes(
                FlowAnalysisService(store = store, scope = CoroutineScope(Dispatchers.Unconfined))
            )
        }

        val response = client.post("/v1/flow-analysis/flow-3/recommendations/rec-1/actions/act-1/execute")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("\"act-1\"", body["id"].toString())
        assertEquals("\"EXECUTED\"", body["status"].toString())
        assertEquals(null, body["flow_id"], "the answer is the action, not the whole analysis")
    }

    @Test
    fun `POST execute flow action answers with the updated action only`() = testApplication {
        val store = FlowAnalysisStore(createTempDirectory("flow-routes-flow-execute").toFile())
        store.saveRequest(sampleRequestOf("flow-8"))
        store.saveResult(
            FlowAnalysisResponse(
                flowId = "flow-8",
                status = AnalysisStatus.COMPLETED,
                actions = flowActions()
            )
        )
        val seerClient = SeerClient(
            authToken = "token",
            org = "sentry-sdks",
            httpClient = HttpClient(
                MockEngine { _ ->
                    respond(
                        content = """{"run_id": 77, "sentry_run_id": "uuid"}""",
                        status = HttpStatusCode.OK,
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

        val response = client.post("/v1/flow-analysis/flow-8/actions/generate-dashboard/execute")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("\"generate-dashboard\"", body["id"].toString())
        assertEquals("\"EXECUTED\"", body["status"].toString())
        assertEquals(
            "\"https://sentry-sdks.sentry.io/issues/?statsPeriod=10m&explorerRunId=uuid\"",
            body["seer_run_url"].toString()
        )
        assertEquals(null, body["flow_id"], "the answer is the action, not the whole analysis")
        assertEquals(ActionStatus.EXECUTED, store.loadResult("flow-8")!!.actions.first().status)
    }

    @Test
    fun `POST execute disabled flow action answers 409`() = testApplication {
        val store = FlowAnalysisStore(createTempDirectory("flow-routes-flow-execute-disabled").toFile())
        store.saveResult(
            FlowAnalysisResponse(
                flowId = "flow-11",
                status = AnalysisStatus.COMPLETED,
                actions = flowActions()
            )
        )
        application {
            install(ContentNegotiation) { json() }
            flowAnalysisRoutes(
                FlowAnalysisService(store = store, scope = CoroutineScope(Dispatchers.Unconfined))
            )
        }

        val response = client.post("/v1/flow-analysis/flow-11/actions/generate-dashboard/execute")

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("""{"error":"the action is not executable by the bridge"}""", response.bodyAsText())
    }

    @Test
    fun `POST execute flow action for an unknown action answers 404`() = testApplication {
        val store = FlowAnalysisStore(createTempDirectory("flow-routes-flow-execute-404").toFile())
        store.saveResult(
            FlowAnalysisResponse(
                flowId = "flow-9",
                status = AnalysisStatus.COMPLETED,
                actions = flowActions()
            )
        )
        application {
            install(ContentNegotiation) { json() }
            flowAnalysisRoutes(
                FlowAnalysisService(store = store, scope = CoroutineScope(Dispatchers.Unconfined))
            )
        }

        val response = client.post("/v1/flow-analysis/flow-9/actions/nope/execute")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("""{"error":"action not found"}""", response.bodyAsText())
    }

    @Test
    fun `POST execute client flow action answers 409`() = testApplication {
        val store = FlowAnalysisStore(createTempDirectory("flow-routes-flow-execute-client").toFile())
        store.saveResult(
            FlowAnalysisResponse(
                flowId = "flow-10",
                status = AnalysisStatus.COMPLETED,
                actions = flowActions()
            )
        )
        application {
            install(ContentNegotiation) { json() }
            flowAnalysisRoutes(
                FlowAnalysisService(store = store, scope = CoroutineScope(Dispatchers.Unconfined))
            )
        }

        val response = client.post("/v1/flow-analysis/flow-10/actions/share-recording-json/execute")

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("""{"error":"the action is not executable by the bridge"}""", response.bodyAsText())
    }

  @Test
    fun `POST execute for an unknown action answers 404`() = testApplication {
        val store = FlowAnalysisStore(createTempDirectory("flow-routes-execute-404").toFile())
        store.saveResult(
            FlowAnalysisResponse(
                flowId = "flow-5",
                status = AnalysisStatus.COMPLETED,
                recommendations = listOf(recommendationWithOneAction())
            )
        )
        application {
            install(ContentNegotiation) { json() }
            flowAnalysisRoutes(
                FlowAnalysisService(store = store, scope = CoroutineScope(Dispatchers.Unconfined))
            )
        }

        val response = client.post("/v1/flow-analysis/flow-5/recommendations/rec-1/actions/nope/execute")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("""{"error":"action not found"}""", response.bodyAsText())
    }

    @Test
    fun `POST dismiss answers with the dismissed recommendation`() = testApplication {
        val store = FlowAnalysisStore(createTempDirectory("flow-routes-dismiss").toFile())
        store.saveResult(
            FlowAnalysisResponse(
                flowId = "flow-6",
                status = AnalysisStatus.COMPLETED,
                recommendations = listOf(recommendationWithOneAction())
            )
        )
        application {
            install(ContentNegotiation) { json() }
            flowAnalysisRoutes(
                FlowAnalysisService(store = store, scope = CoroutineScope(Dispatchers.Unconfined))
            )
        }

        val response = client.post("/v1/flow-analysis/flow-6/recommendations/rec-1/dismiss")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("\"rec-1\"", body["id"].toString())
        assertEquals("\"DISMISSED\"", body["status"].toString())
        assertEquals(
            RecommendationStatus.DISMISSED,
            store.loadResult("flow-6")!!.recommendations.single().status
        )
    }

    @Test
    fun `POST execute on a dismissed recommendation answers 409`() = testApplication {
        val store = FlowAnalysisStore(createTempDirectory("flow-routes-execute-409").toFile())
        store.saveResult(
            FlowAnalysisResponse(
                flowId = "flow-7",
                status = AnalysisStatus.COMPLETED,
                recommendations = listOf(recommendationWithOneAction().copy(status = RecommendationStatus.DISMISSED))
            )
        )
        application {
            install(ContentNegotiation) { json() }
            flowAnalysisRoutes(
                FlowAnalysisService(store = store, scope = CoroutineScope(Dispatchers.Unconfined))
            )
        }

        val response = client.post("/v1/flow-analysis/flow-7/recommendations/rec-1/actions/act-1/execute")

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("""{"error":"the recommendation is dismissed"}""", response.bodyAsText())
    }
}
