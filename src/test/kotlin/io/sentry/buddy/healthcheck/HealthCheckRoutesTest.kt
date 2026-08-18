package io.sentry.buddy.healthcheck

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthCheckRoutesTest {

    private fun mockClient(tagName: String): HttpClient {
        val mockEngine = MockEngine { _ ->
            respond(
                content = """{"tag_name": "$tagName"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        return HttpClient(mockEngine) {
            install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    }

    @Test
    fun `POST v1 health-check returns findings`() = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            healthCheckRoutes(
                HealthCheckService(httpClient = mockClient("8.40.0"))
            )
        }

        val response = client.post("/v1/health-check") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "sdk": "io.sentry.android@8.40.0",
                  "config": {
                    "dsnConfigured": true,
                    "tracesSampleRate": 1.0,
                    "sessionReplayEnabled": false
                  }
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(
            "\"Buddy found 1 finding worth checking.\"",
            body["summary"].toString()
        )
        assertEquals(1, body["findings"]!!.jsonArray.size)
    }

    @Test
    fun `POST v1 health-check with blank sdk returns 400`() = testApplication {
        application {
            install(ServerContentNegotiation) { json() }
            healthCheckRoutes(HealthCheckService())
        }

        val response = client.post("/v1/health-check") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "sdk": "",
                  "config": {}
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("\"sdk must not be blank\"", body["error"].toString())
    }
}
