package io.sentry.buddy.healthcheck

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.sentry.buddy.HealthCheckRequest

fun Application.healthCheckRoutes(healthCheckService: HealthCheckService) {
    routing {
        post("/v1/health-check") {
            val request = call.receive<HealthCheckRequest>()
            val validationError = validateHealthCheckRequest(request)
            if (validationError != null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to validationError))
                return@post
            }

            val response = healthCheckService.check(request)
            call.respond(HttpStatusCode.OK, response)
        }
    }
}

internal fun validateHealthCheckRequest(request: HealthCheckRequest): String? = when {
    request.sdk.isBlank() -> "sdk must not be blank"
    request.sdk.length > 200 -> "sdk must be 200 characters or fewer"
    else -> null
}
