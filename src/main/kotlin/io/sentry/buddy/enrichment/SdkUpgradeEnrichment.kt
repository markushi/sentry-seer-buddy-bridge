package io.sentry.buddy.enrichment

import io.sentry.buddy.FlowAnalysisRequest
import io.sentry.buddy.FlowAnalysisResponse
import io.sentry.buddy.sdk.SdkUpgradeAdvisor

class SdkUpgradeEnrichment(
    private val advisor: SdkUpgradeAdvisor = SdkUpgradeAdvisor()
) : Enrichment {

    override suspend fun enrich(request: FlowAnalysisRequest, response: FlowAnalysisResponse): FlowAnalysisResponse {
        val recommendation = advisor.upgradeRecommendation(request.sdk) ?: return response
        return response.copy(recommendations = response.recommendations + recommendation)
    }
}
