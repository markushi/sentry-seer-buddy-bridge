package io.sentry.buddy.healthcheck

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import io.sentry.buddy.HealthCheckConfigSnapshot
import io.sentry.buddy.HealthCheckFinding
import io.sentry.buddy.HealthCheckRequest
import io.sentry.buddy.HealthCheckResponse
import io.sentry.buddy.Severity
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class HealthCheckService(
    private val httpClient: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    },
    private val releasesUrl: String = "https://api.github.com/repos/getsentry/sentry-java/releases/latest",
    private val maxFindings: Int = 5,
) {

    private val logger = LoggerFactory.getLogger(HealthCheckService::class.java)

    suspend fun check(request: HealthCheckRequest): HealthCheckResponse {
        val findings = buildFindings(request).take(maxFindings)
        val summary =
            if (findings.isEmpty()) {
                "Buddy did not find any obvious Sentry config changes to recommend."
            } else {
                "Buddy found ${findings.size} ${"finding".pluralize(findings.size)} worth checking."
            }
        return HealthCheckResponse(summary = summary, findings = findings)
    }

    internal suspend fun buildFindings(request: HealthCheckRequest): List<HealthCheckFinding> {
        val config = request.config
        val findings = mutableListOf<HealthCheckFinding>()

        if (!config.dsnConfigured) {
            findings += finding(
                id = "dsn-missing",
                title = "Configure a DSN",
                description = "Buddy cannot correlate this app with a Sentry project until the SDK is initialized with a DSN.",
                severity = Severity.HIGH,
                currentValue = "Missing",
                suggestedValue = "Set options.dsn"
            )
        }

        val currentVersion = parseSdkVersion(request.sdk)
        val latestVersion = fetchLatestReleaseVersion()
        if (currentVersion != null && latestVersion != null && isOutdated(currentVersion, latestVersion)) {
            findings += finding(
                id = "sdk-outdated",
                title = "Upgrade the Sentry SDK",
                description = "This app is using $currentVersion, but sentry-java $latestVersion is available with newer fixes and instrumentation improvements.",
                severity = Severity.LOW,
                currentValue = currentVersion,
                suggestedValue = latestVersion,
                link = "https://github.com/getsentry/sentry-java/releases/tag/$latestVersion"
            )
        }

        if (config.tracesSampleRate == null && !config.hasTracesSampler) {
            findings += finding(
                id = "tracing-disabled",
                title = "Turn on tracing for performance visibility",
                description = "Buddy could not find a traces sample rate or traces sampler, so transaction tracing is likely off.",
                severity = Severity.MEDIUM,
                currentValue = "Tracing disabled",
                suggestedValue = "Set tracesSampleRate or tracesSampler"
            )
        }

        if (!config.sessionReplayEnabled && !config.sessionReplayOnErrorEnabled) {
            findings += finding(
                id = "replay-disabled",
                title = "Consider enabling Session Replay",
                description = "Replay is off for both full sessions and error-triggered captures, so visual debugging context is unavailable.",
                severity = Severity.LOW,
                currentValue = "Disabled",
                suggestedValue = "Set sessionReplay.sessionSampleRate or onErrorSampleRate"
            )
        }

        if (config.anrEnabled == false) {
            findings += finding(
                id = "anr-disabled",
                title = "Enable ANR reporting",
                description = "Android ANR detection is turned off, so app hangs will be harder to diagnose in Sentry.",
                severity = Severity.LOW,
                currentValue = "Disabled",
                suggestedValue = "Set anrEnabled = true"
            )
        }

        return findings
    }

    private fun finding(
        id: String,
        title: String,
        description: String,
        severity: Severity,
        currentValue: String? = null,
        suggestedValue: String? = null,
        link: String? = null,
    ): HealthCheckFinding = HealthCheckFinding(
        id = id,
        title = title,
        description = description,
        severity = severity,
        currentValue = currentValue,
        suggestedValue = suggestedValue,
        link = link,
    )

    private suspend fun fetchLatestReleaseVersion(): String? = try {
        httpClient.get(releasesUrl) { header("Accept", "application/vnd.github+json") }
            .body<GithubReleaseDto>()
            .tagName
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
        for (index in 0 until length) {
            val currentPart = currentParts.getOrElse(index) { 0 }
            val latestPart = latestParts.getOrElse(index) { 0 }
            if (latestPart > currentPart) return true
            if (latestPart < currentPart) return false
        }
        return false
    }

    private fun String.pluralize(count: Int): String = if (count == 1) this else "${this}s"
}

@Serializable
private data class GithubReleaseDto(
    @kotlinx.serialization.SerialName("tag_name") val tagName: String,
)
