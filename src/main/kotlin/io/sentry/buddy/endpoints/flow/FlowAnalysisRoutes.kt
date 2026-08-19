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

            post("/{flowId}/recommendations/{recommendationId}/dismiss") {
                val flowId = call.parameters["flowId"] ?: ""
                validateFlowId(flowId)?.let {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to it))
                }
                val recommendationId = call.parameters["recommendationId"]!!

                when (val outcome = flowAnalysisService.dismissRecommendation(flowId, recommendationId)) {
                    is DismissOutcome.Success -> call.respond(outcome.recommendation)
                    DismissOutcome.FlowAnalysisNotFound ->
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "flow not found"))

                    DismissOutcome.RecommendationNotFound ->
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "recommendation not found"))
                }
            }

            post("/{flowId}/recommendations/{recommendationId}/actions/{actionId}/execute") {
                val flowId = call.parameters["flowId"] ?: ""
                validateFlowId(flowId)?.let {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to it))
                }
                val recommendationId = call.parameters["recommendationId"]!!
                val actionId = call.parameters["actionId"]!!

                when (val outcome = flowAnalysisService.executeAction(flowId, recommendationId, actionId)) {
                    is ExecuteActionOutcome.Success -> call.respond(outcome.action)
                    ExecuteActionOutcome.FlowAnalysisNotFound ->
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "flow not found"))

                    ExecuteActionOutcome.RecommendationNotFound ->
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "recommendation not found"))

                    ExecuteActionOutcome.ActionNotFound ->
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "action not found"))

                    ExecuteActionOutcome.RecommendationDismissed ->
                        call.respond(
                            HttpStatusCode.Conflict,
                            mapOf("error" to "the recommendation is dismissed")
                        )

                    // The detail of the failure names organization flags and access-gate state, so it
                    // belongs in the log (the service writes it) and not in the answer.
                    is ExecuteActionOutcome.SeerStartFailed ->
                        call.respond(HttpStatusCode.BadGateway, mapOf("error" to "could not start the Seer run"))
                }
            }

            post("/{flowId}/actions/{actionId}/execute") {
                val flowId = call.parameters["flowId"] ?: ""
                validateFlowId(flowId)?.let {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to it))
                }
                val actionId = call.parameters["actionId"]!!

                when (val outcome = flowAnalysisService.executeFlowAction(flowId, actionId)) {
                    is ExecuteFlowActionOutcome.Success -> call.respond(outcome.action)
                    ExecuteFlowActionOutcome.FlowAnalysisNotFound ->
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "flow not found"))

                    ExecuteFlowActionOutcome.ActionNotFound ->
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "action not found"))

                    ExecuteFlowActionOutcome.ActionNotExecutable ->
                        call.respond(HttpStatusCode.Conflict, mapOf("error" to "the action is not executable by the bridge"))

                    // The detail of the failure names organization flags and access-gate state, so it
                    // belongs in the log (the service writes it) and not in the answer.
                    is ExecuteFlowActionOutcome.SeerStartFailed ->
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
