package io.sentry.buddy.tooling

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import io.sentry.buddy.flow.FlowAnalysisRequest
import io.sentry.buddy.flow.Recommendation
import io.sentry.buddy.flow.RecommendationEngine
import io.sentry.buddy.flow.SentryIssue
import io.sentry.buddy.flow.Severity
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.UUID

@Serializable
private data class GithubReleaseDto(val tag_name: String)

class SdkUpgradeRecommendationSource(
    private val httpClient: HttpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } },
    private val releasesUrl: String = "https://api.github.com/repos/getsentry/sentry-java/releases/latest"
) : RecommendationEngine {

    private val logger = LoggerFactory.getLogger(SdkUpgradeRecommendationSource::class.java)

    override suspend fun generateRecommendations(
        request: FlowAnalysisRequest,
        issues: List<SentryIssue>
    ): List<Recommendation> {
        val currentVersion = parseSdkVersion(request.sdk) ?: return emptyList()
        val latestVersion = fetchLatestReleaseVersion() ?: return emptyList()

        if (!isOutdated(current = currentVersion, latest = latestVersion)) return emptyList()

        return listOf(
            Recommendation(
                id = UUID.randomUUID().toString(),
                title = "Upgrade Sentry SDK to $latestVersion",
                description = "This flow used ${request.sdk}, but sentry-java $latestVersion is available. " +
                    "Newer SDK versions include bug fixes and performance improvements.",
                link = "https://github.com/getsentry/sentry-java/releases/tag/$latestVersion",
                severity = Severity.LOW
            )
        )
    }

    private suspend fun fetchLatestReleaseVersion(): String? = try {
        httpClient.get(releasesUrl) { header("Accept", "application/vnd.github+json") }
            .body<GithubReleaseDto>()
            .tag_name
            .removePrefix("v")
    } catch (e: Exception) {
        logger.warn("Failed to fetch the latest sentry-java release", e)
        null
    }

    internal fun parseSdkVersion(sdk: String): String? =
        sdk.substringAfter("@", missingDelimiterValue = "").ifBlank { null }

    internal fun isOutdated(current: String, latest: String): Boolean {
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
        val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val length = maxOf(currentParts.size, latestParts.size)
        for (i in 0 until length) {
            val currentPart = currentParts.getOrElse(i) { 0 }
            val latestPart = latestParts.getOrElse(i) { 0 }
            if (latestPart > currentPart) return true
            if (latestPart < currentPart) return false
        }
        return false
    }
}
