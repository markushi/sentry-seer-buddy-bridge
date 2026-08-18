package io.sentry.buddy.flow

fun interface Enrichment {
    suspend fun enrich(request: FlowAnalysisRequest, response: FlowAnalysisResponse): FlowAnalysisResponse
}
