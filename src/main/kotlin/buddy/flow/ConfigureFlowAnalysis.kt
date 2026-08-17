package io.sentry.buddy.flow

import io.ktor.server.application.Application
import io.sentry.buddy.tooling.ClaudeCliTitleGenerator
import io.sentry.buddy.tooling.SentryApiClient
import java.io.File

fun Application.configureFlowAnalysis(
    flowAnalysisService: FlowAnalysisService = FlowAnalysisService(
        store = FlowAnalysisStore(
            File(environment.config.propertyOrNull("flowAnalysis.dataDir")?.getString() ?: "data/flow-analysis")
        ),
        issueFetcher = System.getenv("SENTRY_AUTH_TOKEN")
            ?.takeIf { it.isNotBlank() }
            ?.let { token -> SentryApiClient(authToken = token) }
            ?: NoOpIssueFetcher,
        titleGenerator = ClaudeCliTitleGenerator()
    )
) {
    flowAnalysisRoutes(flowAnalysisService)
}
