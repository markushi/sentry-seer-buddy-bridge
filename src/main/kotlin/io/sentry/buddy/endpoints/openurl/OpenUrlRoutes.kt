package io.sentry.buddy.endpoints.openurl

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.net.URI

fun Application.openUrlRoutes(browserLauncher: BrowserLauncher) {
    routing {
        post("/v1/open-url") {
            val request = call.receive<OpenUrlRequest>()

            val validationError = validateOpenUrl(request.url)
            if (validationError != null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to validationError))
                return@post
            }

            var opened = false
            try {
                browserLauncher.open(URI(request.url))
                opened = true
            } catch (e: Exception) {
                call.application.log.warn("failed to open url", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "failed to open url"))
            }

            if (opened) {
                call.respond(HttpStatusCode.OK)
            }
        }
    }
}
