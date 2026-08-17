package io.sentry.buddy.flow

class CompositeRecommendationEngine(
    private val sources: List<RecommendationEngine>
) : RecommendationEngine {

    override suspend fun generateRecommendations(
        request: FlowAnalysisRequest,
        issues: List<SentryIssue>
    ): List<Recommendation> = sources.flatMap { it.generateRecommendations(request, issues) }
}
