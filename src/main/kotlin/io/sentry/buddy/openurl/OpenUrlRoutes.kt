package io.sentry.buddy.openurl

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

            try {
                browserLauncher.open(URI(request.url))
                call.respond(HttpStatusCode.OK)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "failed to open url"))
            }
        }
    }
}
