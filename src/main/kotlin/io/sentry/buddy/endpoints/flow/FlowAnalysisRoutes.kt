package io.sentry.buddy.endpoints.flow

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.sentry.buddy.FlowAnalysisRequest

fun Application.flowAnalysisRoutes(flowAnalysisService: FlowAnalysisService) {
    routing {
        route("/v1/flow-analysis") {

            post {
                val request = call.receive<FlowAnalysisRequest>()
                val validationError = validateFlowRequest(request)
                if (validationError != null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to validationError))
                    return@post
                }

                val accepted = flowAnalysisService.submitOrGetExisting(request)
                call.respond(HttpStatusCode.Accepted, accepted)
            }

            get("/{flowId}") {
                val flowId = call.parameters["flowId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest)

                val analysis = flowAnalysisService.get(flowId)
                    ?: return@get call.respond(HttpStatusCode.NotFound)

                call.respond(analysis)
            }

            post("/{flowId}/recommendations/{recommendationId}/resolve") {
                val flowId = call.parameters["flowId"]!!
                val recommendationId = call.parameters["recommendationId"]!!

                when (val outcome = flowAnalysisService.resolveRecommendation(flowId, recommendationId)) {
                    is ResolveOutcome.Success -> call.respond(outcome.recommendation)
                    ResolveOutcome.FlowAnalysisNotFound ->
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "flow not found"))

                    ResolveOutcome.RecommendationNotFound ->
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "recommendation not found"))

                    ResolveOutcome.NotResolvable ->
                        call.respond(HttpStatusCode.Conflict, mapOf("error" to "recommendation is not resolvable"))

                    is ResolveOutcome.SeerStartFailed ->
                        call.respond(
                            HttpStatusCode.BadGateway,
                            mapOf("error" to "could not start the Seer run: ${outcome.message}")
                        )
                }
            }
        }
    }
}

internal fun validateFlowRequest(request: FlowAnalysisRequest): String? = when {
    request.flowId.isBlank() -> "flow_id must not be blank"
    !request.flowId.matches(Regex("[A-Za-z0-9._-]{1,128}")) -> "flow_id must be alphanumeric with . _ -"
    request.dsn.isBlank() -> "dsn must not be blank"
    request.events.isEmpty() -> "events must not be empty"
    request.startTimeMs > request.endTimeMs -> "start_time_ms must be <= end_time_ms"
    else -> null
}
