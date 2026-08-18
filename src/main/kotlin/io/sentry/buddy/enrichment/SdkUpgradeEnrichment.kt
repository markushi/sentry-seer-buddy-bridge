package io.sentry.buddy.enrichment

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import io.sentry.buddy.FlowAnalysisRequest
import io.sentry.buddy.FlowAnalysisResponse
import io.sentry.buddy.Recommendation
import io.sentry.buddy.Severity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.*

@Serializable
private data class GithubReleaseDto(val tag_name: String)

class SdkUpgradeEnrichment(
    private val httpClient: HttpClient = HttpClient(CIO) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } },
    private val releasesUrl: String = "https://api.github.com/repos/getsentry/sentry-java/releases/latest"
) : Enrichment {

    private val logger = LoggerFactory.getLogger(SdkUpgradeEnrichment::class.java)

    override suspend fun enrich(request: FlowAnalysisRequest, response: FlowAnalysisResponse): FlowAnalysisResponse {
        val currentVersion = parseSdkVersion(request.sdk) ?: return response
        val latestVersion = fetchLatestReleaseVersion() ?: return response

        // commented out for local testing
        // if (!isOutdated(current = currentVersion, latest = latestVersion)) return response

        val recommendation = Recommendation(
            id = UUID.randomUUID().toString(),
            title = "Upgrade Sentry SDK to $latestVersion",
            description = "This flow used ${request.sdk}, but sentry-java $latestVersion is available. " +
                    "Newer SDK versions include bug fixes and performance improvements.",
            link = "https://github.com/getsentry/sentry-java/releases/tag/$latestVersion",
            severity = Severity.LOW
        )
        return response.copy(recommendations = response.recommendations + recommendation)
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
