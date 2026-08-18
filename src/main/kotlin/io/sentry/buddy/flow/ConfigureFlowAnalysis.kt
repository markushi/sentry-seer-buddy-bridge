package io.sentry.buddy.flow

import io.ktor.server.application.*
import io.sentry.buddy.enrichment.IssueEnrichment
import io.sentry.buddy.enrichment.SdkUpgradeEnrichment
import io.sentry.buddy.enrichment.SeerRecommendationEnrichment
import io.sentry.buddy.enrichment.TitleEnrichment
import java.io.File

fun Application.configureFlowAnalysis(
    flowAnalysisService: FlowAnalysisService = FlowAnalysisService(
        store = FlowAnalysisStore(
            File(environment.config.propertyOrNull("flowAnalysis.dataDir")?.getString() ?: "data/flow-analysis")
        ),
        enrichments = buildList {
            val token = System.getenv("SENTRY_AUTH_TOKEN")?.takeIf { it.isNotBlank() }
            if (token != null) {
                add(IssueEnrichment(authToken = token))
                add(SeerRecommendationEnrichment(authToken = token))
            }
            add(SdkUpgradeEnrichment())
            add(TitleEnrichment())
        }
    )
) {
    flowAnalysisRoutes(flowAnalysisService)
}
