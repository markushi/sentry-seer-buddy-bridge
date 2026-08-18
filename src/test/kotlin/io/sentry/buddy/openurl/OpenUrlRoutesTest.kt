package io.sentry.buddy.openurl

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenUrlRoutesTest {

    private class FakeBrowserLauncher : BrowserLauncher {
        var openedUri: URI? = null
        var shouldFail = false

        override fun open(uri: URI) {
            if (shouldFail) error("boom")
            openedUri = uri
        }
    }

    @Test
    fun `POST v1 open-url with an allowed url returns 200 and opens it`() = testApplication {
        val launcher = FakeBrowserLauncher()
        application {
            install(ContentNegotiation) { json() }
            openUrlRoutes(launcher)
        }

        val response = client.post("/v1/open-url") {
            contentType(ContentType.Application.Json)
            setBody("""{"url": "https://sentry.io/organizations/acme/issues/123/"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(URI("https://sentry.io/organizations/acme/issues/123/"), launcher.openedUri)
    }

    @Test
    fun `POST v1 open-url with a disallowed host returns 400 and does not open it`() = testApplication {
        val launcher = FakeBrowserLauncher()
        application {
            install(ContentNegotiation) { json() }
            openUrlRoutes(launcher)
        }

        val response = client.post("/v1/open-url") {
            contentType(ContentType.Application.Json)
            setBody("""{"url": "https://evil.com/whatever"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText())
        assertEquals("\"url host must be sentry.io\"", body.jsonObject["error"].toString())
        assertNull(launcher.openedUri)
    }

    @Test
    fun `POST v1 open-url when the launcher fails returns 500`() = testApplication {
        val launcher = FakeBrowserLauncher().apply { shouldFail = true }
        application {
            install(ContentNegotiation) { json() }
            openUrlRoutes(launcher)
        }

        val response = client.post("/v1/open-url") {
            contentType(ContentType.Application.Json)
            setBody("""{"url": "https://sentry.io/organizations/acme/issues/123/"}""")
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
    }
}
