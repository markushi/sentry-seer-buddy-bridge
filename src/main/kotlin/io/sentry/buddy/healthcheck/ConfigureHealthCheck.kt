package io.sentry.buddy.healthcheck

import io.ktor.server.application.*

fun Application.configureHealthCheck(
    healthCheckService: HealthCheckService = HealthCheckService()
) {
    healthCheckRoutes(healthCheckService)
}
