package io.sentry.buddy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

sealed class ResolveOutcome {
    data class Success(val response: FlowAnalysisResponse) : ResolveOutcome()
    object FlowAnalysisNotFound : ResolveOutcome()
    object RecommendationNotFound : ResolveOutcome()
    object NotResolvable : ResolveOutcome()
}

class FlowAnalysisService(
    private val store: FlowAnalysisStore,
    private val issueFetcher: IssueFetcher = NoOpIssueFetcher,
    private val recommendationEngine: RecommendationEngine = NoOpRecommendationEngine,
    private val titleGenerator: TitleGenerator,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

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
            val issues = issueFetcher.fetchIssues(request)
            val recommendations = recommendationEngine.generateRecommendations(request, issues)
            val title = titleGenerator.generateTitle(request)
            FlowAnalysisResponse(
                flowId = request.flowId,
                status = AnalysisStatus.COMPLETED,
                title = title,
                recommendations = recommendations,
                issues = issues
            )
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
