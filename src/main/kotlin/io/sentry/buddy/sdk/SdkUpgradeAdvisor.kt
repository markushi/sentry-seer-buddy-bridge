package io.sentry.buddy.sdk

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import io.sentry.buddy.Recommendation
import io.sentry.buddy.RecommendationAction
import io.sentry.buddy.Severity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.*

@Serializable
private data class GithubReleaseDto(val tag_name: String)

/**
 * Tells whether an SDK identifier such as `io.sentry.android@8.40.0` is behind the latest
 * sentry-java release, and if it is, gives the `Recommendation` that says so. Both the flow
 * analysis enrichment and the health check endpoint use it, so the rule lives in one place only.
 */
class SdkUpgradeAdvisor(
    private val httpClient: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
            })
        }
    },
    private val releasesUrl: String = "https://api.github.com/repos/getsentry/sentry-java/releases/latest"
) {

    private val logger = LoggerFactory.getLogger(SdkUpgradeAdvisor::class.java)

    /** Gives `null` when the version does not parse, GitHub does not answer, or the SDK is current. */
    suspend fun upgradeRecommendation(sdk: String): Recommendation? {
        val currentVersion = parseSdkVersion(sdk) ?: return null
        val latestVersion = fetchLatestReleaseVersion() ?: return null
        if (!isOutdated(current = currentVersion, latest = latestVersion)) return null

        return Recommendation(
            id = UUID.randomUUID().toString(),
            title = "Upgrade Sentry SDK to $latestVersion",
            description = "Version $sdk detected, but sentry-java $latestVersion is available.",
            link = "https://github.com/getsentry/sentry-java/releases/tag/$latestVersion",
            severity = Severity.LOW,
            actions = listOf(
                RecommendationAction(
                    id = UUID.randomUUID().toString(),
                    actionLabel = "Open a PR",
                    description = "Raise the Sentry SDK dependency of this project from " +
                            "$currentVersion to $latestVersion, and adapt the code to the changes of " +
                            "the release notes if there are any."
                ),
                RecommendationAction(
                    id = UUID.randomUUID().toString(),
                    actionLabel = "Show Changelog",
                    description = "Show the release notes of sentry-java $latestVersion.",
                    link = "https://github.com/getsentry/sentry-java/releases/tag/$latestVersion"
                )
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
