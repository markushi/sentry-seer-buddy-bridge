package io.sentry.buddy.flow

import io.sentry.buddy.AnalysisStatus
import io.sentry.buddy.FlowAnalysisRequest
import io.sentry.buddy.FlowAnalysisResponse
import io.sentry.buddy.RecommendationStatus
import io.sentry.buddy.enrichment.Enrichment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

sealed class ResolveOutcome {
    data class Success(val response: FlowAnalysisResponse) : ResolveOutcome()
    object FlowAnalysisNotFound : ResolveOutcome()
    object RecommendationNotFound : ResolveOutcome()
    object NotResolvable : ResolveOutcome()
}

class FlowAnalysisService(
    private val store: FlowAnalysisStore,
    private val enrichments: List<Enrichment> = emptyList(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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

    fun resolveRecommendation(flowId: String, recommendationId: String): ResolveOutcome {
        val current = store.loadResult(flowId) ?: return ResolveOutcome.FlowAnalysisNotFound
        val target = current.recommendations.find { it.id == recommendationId }
            ?: return ResolveOutcome.RecommendationNotFound
        if (!target.resolvable) return ResolveOutcome.NotResolvable

        val updated = current.copy(
            recommendations = current.recommendations.map {
                if (it.id == recommendationId) it.copy(status = RecommendationStatus.RESOLVED) else it
            }
        )
        store.saveResult(updated)
        return ResolveOutcome.Success(updated)
    }

    private suspend fun runPipeline(request: FlowAnalysisRequest) {
        val result = try {
            val errors = mutableListOf<String>()
            var response = FlowAnalysisResponse(flowId = request.flowId, status = AnalysisStatus.PROCESSING)
            for (enrichment in enrichments) {
                response = try {
                    enrichment.enrich(request, response)
                } catch (e: Exception) {
                    logger.warn("Enrichment ${enrichment::class.simpleName} failed for flow ${request.flowId}", e)
                    errors += "${enrichment::class.simpleName}: ${e.message ?: "unknown error"}"
                    response
                }
            }
            response.copy(status = AnalysisStatus.COMPLETED, enrichmentErrors = errors)
        } catch (e: Exception) {
            FlowAnalysisResponse(
                flowId = request.flowId,
                status = AnalysisStatus.FAILED,
                error = e.message ?: "unknown error"
            )
        }
        store.saveResult(result)
    }
}
