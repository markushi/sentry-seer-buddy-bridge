package io.sentry.buddy.enrichment

import io.sentry.buddy.flow.FlowAnalysisRequest
import io.sentry.buddy.flow.FlowAnalysisResponse

fun interface Enrichment {
    suspend fun enrich(request: FlowAnalysisRequest, response: FlowAnalysisResponse): FlowAnalysisResponse
}
