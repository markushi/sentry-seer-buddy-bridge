package io.sentry.buddy.endpoints.flow

import io.sentry.buddy.AnalysisStatus
import io.sentry.buddy.FlowAnalysisRequest
import io.sentry.buddy.FlowAnalysisResponse
import io.sentry.buddy.Recommendation
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

sealed class ResolveOutcome {
    data class Success(val recommendation: Recommendation) : ResolveOutcome()
    object FlowAnalysisNotFound : ResolveOutcome()
    object RecommendationNotFound : ResolveOutcome()
    object NotResolvable : ResolveOutcome()
    data class SeerStartFailed(val message: String) : ResolveOutcome()
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

    suspend fun resolveRecommendation(flowId: String, recommendationId: String): ResolveOutcome =
        store.withFlowLock(flowId) {
            val current = store.loadResult(flowId) ?: return@withFlowLock ResolveOutcome.FlowAnalysisNotFound
            val target = current.recommendations.find { it.id == recommendationId }
                ?: return@withFlowLock ResolveOutcome.RecommendationNotFound
            if (!target.resolvable) return@withFlowLock ResolveOutcome.NotResolvable

            // The app retries a resolve after a 502, so a recommendation that already has its run
            // must not start a second one and orphan the first.
            if (target.status == RecommendationStatus.RESOLVED && target.seerRunUrl != null) {
                return@withFlowLock ResolveOutcome.Success(target)
            }

            val seerRunUrl = if (seerClient == null) {
                null
            } else {
                val request = store.loadRequest(flowId)
                    ?: return@withFlowLock ResolveOutcome.SeerStartFailed("no stored request for flow $flowId")
                try {
                    val run = seerClient.startRun(
                        query = SeerPrompts.implement(request, current.issues, target),
                        pageName = PAGE_NAME_FLOW_IMPLEMENT
                    )
                    logger.info("Started the Seer implement run ${run.runId} for $flowId/$recommendationId")
                    seerClient.runUrl(run.sentryRunId)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    logger.warn("Could not start the Seer implement run for $flowId/$recommendationId", e)
                    return@withFlowLock ResolveOutcome.SeerStartFailed(e.message ?: "unknown error")
                }
            }

            val resolved = target.copy(status = RecommendationStatus.RESOLVED, seerRunUrl = seerRunUrl)
            store.saveResult(
                current.copy(
                    recommendations = current.recommendations.map { if (it.id == recommendationId) resolved else it }
                )
            )
            ResolveOutcome.Success(resolved)
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
