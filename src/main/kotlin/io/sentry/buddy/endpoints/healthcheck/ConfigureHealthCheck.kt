package io.sentry.buddy.endpoints.healthcheck

import io.ktor.server.application.*
import io.sentry.buddy.Recommendation
import io.sentry.buddy.RecommendationAction
import io.sentry.buddy.Severity
import io.sentry.buddy.sdk.SdkUpgradeAdvisor
import java.util.UUID

fun Application.configureHealthCheck(advisor: SdkUpgradeAdvisor = SdkUpgradeAdvisor()) {
    healthCheckRoutes { sdk, config ->
        val recommendations = mutableListOf<Recommendation?>()
        recommendations.add(advisor.upgradeRecommendation(sdk))
        if (config.sampleRate != 1.0) {
            recommendations.add(
                Recommendation(
                    id = UUID.randomUUID().toString(),
                    title = "sample_rate is not 1.0",
                    description = "Set sampleRate to 1.0 in the Sentry SDK options for your debug builds.",
                    severity = Severity.LOW,
                    actions = listOf(
                        RecommendationAction(
                            id = UUID.randomUUID().toString(),
                            actionLabel = "Configure sample_rate",
                            link = "https://docs.sentry.io/platforms/android/configuration/sampling/",
                            description = "Set sampleRate to 1.0 in the Sentry SDK options of the " +
                                "debug build, so that every event of the session is sent."
                        )
                    )
                )
            )
        }
        // TODO add more recommendations
        recommendations.filterNotNull()

    }
}
