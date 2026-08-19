package io.sentry.buddy.endpoints.healthcheck

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.sentry.buddy.HealthCheckRequest
import io.sentry.buddy.HealthCheckResponse
import io.sentry.buddy.Recommendation
import io.sentry.buddy.SdkConfigSnapshot

/** Gives the upgrade recommendation for an SDK identifier, or `null` when the SDK is current. */
fun interface HealthCheck {
    suspend fun run(sdk: String, config: SdkConfigSnapshot): List<Recommendation>
}

fun Application.healthCheckRoutes(healthCheck: HealthCheck) {
    routing {
        post("/v1/health-check") {
            val request = call.receive<HealthCheckRequest>()
            val recommendations = healthCheck.run(request.sdk, request.config)
            call.respond(HealthCheckResponse(recommendations))
        }
    }
}
