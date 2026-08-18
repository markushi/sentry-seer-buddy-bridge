package io.sentry.buddy.healthcheck

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.sentry.buddy.HealthCheckConfigSnapshot
import io.sentry.buddy.HealthCheckRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthCheckServiceTest {

    private fun sampleRequest(
        sdk: String = "io.sentry.android@8.40.0",
        config: HealthCheckConfigSnapshot = HealthCheckConfigSnapshot(dsnConfigured = true)
    ) = HealthCheckRequest(sdk = sdk, config = config)

    private fun mockClient(tagName: String): HttpClient {
        val mockEngine = MockEngine { _ ->
            respond(
                content = """{"tag_name": "$tagName"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        return HttpClient(mockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    }

    @Test
    fun `check returns an sdk upgrade finding when the app is outdated`() = runBlocking {
        val service = HealthCheckService(httpClient = mockClient("8.41.0"))

        val response = service.check(
            sampleRequest(
                config = HealthCheckConfigSnapshot(
                    dsnConfigured = true,
                    tracesSampleRate = 1.0,
                    sessionReplayEnabled = true,
                    anrEnabled = true,
                )
            )
        )

        assertEquals(1, response.findings.size)
        assertTrue(response.findings.single().title.contains("Upgrade the Sentry SDK"))
    }

    @Test
    fun `check returns config findings in priority order`() = runBlocking {
        val service = HealthCheckService(httpClient = mockClient("8.40.0"))

        val response = service.check(
            sampleRequest(
                config = HealthCheckConfigSnapshot(
                    dsnConfigured = false,
                    hasTracesSampler = false,
                    tracesSampleRate = null,
                    sessionReplayEnabled = false,
                    sessionReplayOnErrorEnabled = false,
                    anrEnabled = false,
                )
            )
        )

        assertEquals(listOf(
            "dsn-missing",
            "tracing-disabled",
            "replay-disabled",
            "anr-disabled",
        ), response.findings.map { it.id })
    }

    @Test
    fun `check returns a healthy summary when no findings apply`() = runBlocking {
        val service = HealthCheckService(httpClient = mockClient("8.40.0"))

        val response = service.check(
            sampleRequest(
                config = HealthCheckConfigSnapshot(
                    dsnConfigured = true,
                    tracesSampleRate = 1.0,
                    sessionReplayEnabled = true,
                    anrEnabled = true,
                )
            )
        )

        assertEquals(emptyList(), response.findings)
        assertEquals("Buddy did not find any obvious Sentry config changes to recommend.", response.summary)
    }
}
