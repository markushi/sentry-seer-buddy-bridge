package io.sentry.buddy

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.sentry.buddy.flow.FlowAnalysisService
import io.sentry.buddy.flow.FlowAnalysisStore
import io.sentry.buddy.flow.flowAnalysisRoutes
import io.sentry.buddy.tooling.TitleGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
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

    private fun newTestService() = FlowAnalysisService(
        store = FlowAnalysisStore(createTempDirectory("flow-routes-test").toFile()),
        titleGenerator = TitleGenerator { "Test title" },
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
}
