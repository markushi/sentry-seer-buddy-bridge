package io.sentry.buddy.endpoints.flow

import io.ktor.server.application.*
import io.sentry.buddy.enrichment.IssueEnrichment
import io.sentry.buddy.enrichment.SdkUpgradeEnrichment
import io.sentry.buddy.enrichment.SeerRecommendationEnrichment
import io.sentry.buddy.enrichment.TitleEnrichment
import io.sentry.buddy.seer.SeerClient
import java.io.File

fun Application.configureFlowAnalysis(
    flowAnalysisService: FlowAnalysisService = FlowAnalysisService(
        store = FlowAnalysisStore(
            File(environment.config.propertyOrNull("flowAnalysis.dataDir")?.getString() ?: "data/flow-analysis")
        ),
        enrichments = buildList {
            val token = System.getenv("SENTRY_AUTH_TOKEN")?.takeIf { it.isNotBlank() }
            val org = System.getenv("SENTRY_ORG")?.takeIf { it.isNotBlank() }
            if (token != null) add(IssueEnrichment(authToken = token))
            if (token != null && org != null) {
                add(
                    SeerRecommendationEnrichment(
                        SeerClient(
                            authToken = token,
                            org = org,
                            projectId = System.getenv("SENTRY_PROJECT_ID")?.takeIf { it.isNotBlank() }
                        )
                    )
                )
            }
            add(SdkUpgradeEnrichment())
            add(TitleEnrichment())
        }
    )
) {
    flowAnalysisRoutes(flowAnalysisService)
}
