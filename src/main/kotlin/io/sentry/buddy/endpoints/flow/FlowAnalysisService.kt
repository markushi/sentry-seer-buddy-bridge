package io.sentry.buddy.endpoints.flow

import io.sentry.buddy.ActionStatus
import io.sentry.buddy.AnalysisStatus
import io.sentry.buddy.FlowAnalysisRequest
import io.sentry.buddy.FlowAnalysisResponse
import io.sentry.buddy.Recommendation
import io.sentry.buddy.RecommendationAction
import io.sentry.buddy.RecommendationStatus
import io.sentry.buddy.enrichment.Enrichment
import io.sentry.buddy.seer.PAGE_NAME_FLOW_IMPLEMENT
import io.sentry.buddy.seer.SeerClient
import io.sentry.buddy.seer.SeerPrompts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

sealed class DismissOutcome {
    data class Success(val recommendation: Recommendation) : DismissOutcome()
    object FlowAnalysisNotFound : DismissOutcome()
    object RecommendationNotFound : DismissOutcome()
}

sealed class ExecuteActionOutcome {
    data class Success(val action: RecommendationAction) : ExecuteActionOutcome()
    object FlowAnalysisNotFound : ExecuteActionOutcome()
    object RecommendationNotFound : ExecuteActionOutcome()
    object ActionNotFound : ExecuteActionOutcome()
    object RecommendationDismissed : ExecuteActionOutcome()
    data class SeerStartFailed(val message: String) : ExecuteActionOutcome()
}

class FlowAnalysisService(
    private val store: FlowAnalysisStore,
    private val enrichments: List<Enrichment> = emptyList(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val seerClient: SeerClient? = null
) {

    private val logger = LoggerFactory.getLogger(FlowAnalysisService::class.java)

    fun submitOrGetExisting(request: FlowAnalysisRequest): FlowAnalysisResponse {
        store.loadResult(request.flowId)?.let { return it }

        store.saveRequest(request)
        val initial = FlowAnalysisResponse(flowId = request.flowId, status = AnalysisStatus.PROCESSING)
        store.saveResult(initial)

        scope.launch { runPipeline(request) }

        return initial
    }

    fun get(flowId: String): FlowAnalysisResponse? = store.loadResult(flowId)

    suspend fun dismissRecommendation(flowId: String, recommendationId: String): DismissOutcome =
        store.withFlowLock(flowId) {
            val current = store.loadResult(flowId) ?: return@withFlowLock DismissOutcome.FlowAnalysisNotFound
            val target = current.recommendations.find { it.id == recommendationId }
                ?: return@withFlowLock DismissOutcome.RecommendationNotFound

            val dismissed = target.copy(status = RecommendationStatus.DISMISSED)
            store.saveResult(current.copy(recommendations = current.recommendations.replacing(dismissed)))
            DismissOutcome.Success(dismissed)
        }

    suspend fun executeAction(flowId: String, recommendationId: String, actionId: String): ExecuteActionOutcome =
        store.withFlowLock(flowId) {
            val current = store.loadResult(flowId) ?: return@withFlowLock ExecuteActionOutcome.FlowAnalysisNotFound
            val recommendation = current.recommendations.find { it.id == recommendationId }
                ?: return@withFlowLock ExecuteActionOutcome.RecommendationNotFound
            if (recommendation.status == RecommendationStatus.DISMISSED) {
                return@withFlowLock ExecuteActionOutcome.RecommendationDismissed
            }
            val target = recommendation.actions.find { it.id == actionId }
                ?: return@withFlowLock ExecuteActionOutcome.ActionNotFound

            // The app retries an execute after a 502, so an action that already has its run must not
            // start a second one and orphan the first.
            if (target.status == ActionStatus.EXECUTED && target.seerRunUrl != null) {
                return@withFlowLock ExecuteActionOutcome.Success(target)
            }

            val seerRunUrl = if (seerClient == null) {
                null
            } else {
                val request = store.loadRequest(flowId)
                    ?: return@withFlowLock ExecuteActionOutcome.SeerStartFailed("no stored request for flow $flowId")
                try {
                    val run = seerClient.startRun(
                        query = SeerPrompts.implement(request, current.issues, recommendation, target),
                        pageName = PAGE_NAME_FLOW_IMPLEMENT
                    )
                    logger.info("Started the Seer implement run ${run.runId} for $flowId/$recommendationId/$actionId")
                    seerClient.runUrl(run.sentryRunId)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    logger.warn(
                        "Could not start the Seer implement run for $flowId/$recommendationId/$actionId",
                        e
                    )
                    return@withFlowLock ExecuteActionOutcome.SeerStartFailed(e.message ?: "unknown error")
                }
            }

            val executed = target.copy(status = ActionStatus.EXECUTED, seerRunUrl = seerRunUrl)
            val updated = recommendation.copy(
                actions = recommendation.actions.map { if (it.id == actionId) executed else it }
            )
            store.saveResult(current.copy(recommendations = current.recommendations.replacing(updated)))
            ExecuteActionOutcome.Success(executed)
        }

    private suspend fun runPipeline(request: FlowAnalysisRequest) {
        val result = try {
            val errors = mutableListOf<String>()
            var response = FlowAnalysisResponse(flowId = request.flowId, status = AnalysisStatus.PROCESSING)
            for (enrichment in enrichments) {
                response = try {
                    enrichment.enrich(request, response)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    logger.warn("Enrichment ${enrichment::class.simpleName} failed for flow ${request.flowId}", e)
                    errors += "${enrichment::class.simpleName}: ${e.message ?: "unknown error"}"
                    response
                }
            }
            response.copy(status = AnalysisStatus.COMPLETED, enrichmentErrors = errors)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            FlowAnalysisResponse(
                flowId = request.flowId,
                status = AnalysisStatus.FAILED,
                error = e.message ?: "unknown error"
            )
        }
        store.withFlowLock(request.flowId) { store.saveResult(result) }
    }
}

private fun List<Recommendation>.replacing(recommendation: Recommendation): List<Recommendation> =
    map { if (it.id == recommendation.id) recommendation else it }
