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
                val flowId = call.parameters["flowId"] ?: ""
                validateFlowId(flowId)?.let {
                    return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to it))
                }

                val analysis = flowAnalysisService.get(flowId)
                    ?: return@get call.respond(HttpStatusCode.NotFound)

                call.respond(analysis)
            }

            post("/{flowId}/recommendations/{recommendationId}/resolve") {
                val flowId = call.parameters["flowId"] ?: ""
                validateFlowId(flowId)?.let {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to it))
                }
                val recommendationId = call.parameters["recommendationId"]!!

                when (val outcome = flowAnalysisService.resolveRecommendation(flowId, recommendationId)) {
                    is ResolveOutcome.Success -> call.respond(outcome.recommendation)
                    ResolveOutcome.FlowAnalysisNotFound ->
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "flow not found"))

                    ResolveOutcome.RecommendationNotFound ->
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "recommendation not found"))

                    ResolveOutcome.NotResolvable ->
                        call.respond(HttpStatusCode.Conflict, mapOf("error" to "recommendation is not resolvable"))

                    // The detail of the failure names organization flags and access-gate state, so it
                    // belongs in the log (the service writes it) and not in the answer.
                    is ResolveOutcome.SeerStartFailed ->
                        call.respond(HttpStatusCode.BadGateway, mapOf("error" to "could not start the Seer run"))
                }
            }
        }
    }
}

/**
 * A flow id becomes a directory name, thus it must be a plain name. `.` and `..` match the
 * character rule but are not names.
 */
internal fun validateFlowId(flowId: String): String? = when {
    flowId.isBlank() -> "flow_id must not be blank"
    flowId == "." || flowId == ".." -> "flow_id must not be . or .."
    !flowId.matches(Regex("[A-Za-z0-9._-]{1,128}")) -> "flow_id must be alphanumeric with . _ -"
    else -> null
}

internal fun validateFlowRequest(request: FlowAnalysisRequest): String? = validateFlowId(request.flowId) ?: when {
    request.dsn.isBlank() -> "dsn must not be blank"
    request.events.isEmpty() -> "events must not be empty"
    request.startTimeMs > request.endTimeMs -> "start_time_ms must be <= end_time_ms"
    else -> null
}
