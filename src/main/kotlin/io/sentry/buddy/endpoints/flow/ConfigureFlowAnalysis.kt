package io.sentry.buddy.endpoints.flow

import io.ktor.server.application.*
import io.sentry.buddy.enrichment.IssueEnrichment
import io.sentry.buddy.enrichment.SdkUpgradeEnrichment
import io.sentry.buddy.enrichment.SeerRecommendationEnrichment
import io.sentry.buddy.enrichment.TitleEnrichment
import io.sentry.buddy.seer.SeerClient
import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger("io.sentry.buddy.endpoints.flow.ConfigureFlowAnalysis")

private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

fun Application.configureFlowAnalysis(
    flowAnalysisService: FlowAnalysisService = defaultFlowAnalysisService(
        File(environment.config.propertyOrNull("flowAnalysis.dataDir")?.getString() ?: "data/flow-analysis")
    )
) {
    flowAnalysisRoutes(flowAnalysisService)
}

private fun defaultFlowAnalysisService(dataDir: File): FlowAnalysisService {
    val authToken = env("SENTRY_AUTH_TOKEN")
    val org = env("SENTRY_ORG")
    if (org != null && org.all { it.isDigit() }) {
        logger.warn(
            "SENTRY_ORG is the numeric organization id $org, but the organization slug is needed. " +
                "The Seer run links will point at https://$org.sentry.io/... and be dead."
        )
    }
    val seerClient = if (authToken != null && org != null) {
        SeerClient(authToken = authToken, org = org, projectId = env("SENTRY_PROJECT_ID"))
    } else {
        null
    }

    return FlowAnalysisService(
        store = FlowAnalysisStore(dataDir),
        enrichments = buildList {
            if (authToken != null) add(IssueEnrichment(authToken = authToken))
            if (seerClient != null) add(SeerRecommendationEnrichment(seerClient))
            add(SdkUpgradeEnrichment())
            add(TitleEnrichment())
        },
        seerClient = seerClient
    )
}
