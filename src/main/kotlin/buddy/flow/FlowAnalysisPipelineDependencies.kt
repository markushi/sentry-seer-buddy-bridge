package io.sentry.buddy.flow

fun interface IssueFetcher {
    suspend fun fetchIssues(request: FlowAnalysisRequest): List<SentryIssue>
}

object NoOpIssueFetcher : IssueFetcher {
    override suspend fun fetchIssues(request: FlowAnalysisRequest): List<SentryIssue> = emptyList()
}

fun interface RecommendationEngine {
    suspend fun generateRecommendations(request: FlowAnalysisRequest, issues: List<SentryIssue>): List<Recommendation>
}

object NoOpRecommendationEngine : RecommendationEngine {
    override suspend fun generateRecommendations(
        request: FlowAnalysisRequest,
        issues: List<SentryIssue>
    ): List<Recommendation> = emptyList()
}
