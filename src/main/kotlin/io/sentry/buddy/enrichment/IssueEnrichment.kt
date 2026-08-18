package io.sentry.buddy.enrichment

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.sentry.buddy.FlowAnalysisRequest
import io.sentry.buddy.FlowAnalysisResponse
import io.sentry.buddy.SentryIssue
import io.sentry.buddy.seer.seerJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI
import java.time.Instant

/**
 * `organizations/{org}/events/` is the Discover endpoint. It answers with `{"data": [...]}`, one row
 * for each event, and each row carries exactly the columns that were asked for with `field`.
 */
@Serializable
private data class EventsResponse(val data: List<SentryEventDto> = emptyList())

@Serializable
private data class SentryEventDto(
    val id: String? = null,
    @SerialName("issue.id") val issueId: Long? = null,
    val title: String? = null,
    val culprit: String? = null,
    val level: String? = null
)

/** The columns the endpoint must return. Without them it answers `400 No columns selected`. */
private val EVENT_FIELDS = listOf("id", "issue.id", "title", "culprit", "level")

/** Searches every project the token can read, instead of only the default one. */
private const val ALL_PROJECTS = "-1"

class IssueEnrichment(
    private val authToken: String,
    private val httpClient: HttpClient = HttpClient(CIO) { install(ContentNegotiation) { json(seerJson) } },
    private val baseUrl: String = "https://sentry.io",
    /**
     * The organization slug. The DSN only carries the numeric organization id, which the API accepts
     * but which gives a dead issue permalink, so the configured slug is preferred when there is one.
     */
    private val org: String? = null
) : Enrichment {

    override suspend fun enrich(request: FlowAnalysisRequest, response: FlowAnalysisResponse): FlowAnalysisResponse =
        response.copy(issues = fetchIssues(request))

    /**
     * A failure is not swallowed: it reaches `FlowAnalysisService`, which records it in
     * `enrichment_errors`. Only a DSN without an organization gives a quiet empty list, because
     * that is a configuration fact and not a failure.
     */
    internal suspend fun fetchIssues(request: FlowAnalysisRequest): List<SentryIssue> {
        val organization = org ?: organizationSlugFrom(request.dsn) ?: return emptyList()

        return request.traceIds.flatMap { traceId -> fetchEventsForTrace(organization, traceId, request) }
            .groupBy { it.issueId?.toString() ?: it.id }
            .values
            .map { toIssue(organization, it) }
            .sortedWith(compareByDescending<SentryIssue> { levelWeight(it.level) }.thenByDescending { it.count })
            .take(10)
    }

    private suspend fun fetchEventsForTrace(
        org: String,
        traceId: String,
        request: FlowAnalysisRequest
    ): List<SentryEventDto> {
        val httpResponse = httpClient.get("$baseUrl/api/0/organizations/$org/events/") {
            header("Authorization", "Bearer $authToken")
            parameter("query", "trace:$traceId")
            EVENT_FIELDS.forEach { parameter("field", it) }
            parameter("start", Instant.ofEpochMilli(request.startTimeMs).toString())
            parameter("end", Instant.ofEpochMilli(request.endTimeMs).toString())
            parameter("project", ALL_PROJECTS)
        }
        if (!httpResponse.status.isSuccess()) {
            throw IllegalStateException(
                "Sentry events gave ${httpResponse.status}: ${httpResponse.bodyAsText().take(300)}"
            )
        }
        return httpResponse.body<EventsResponse>().data
    }

    private fun toIssue(org: String, events: List<SentryEventDto>): SentryIssue {
        val first = events.first()
        return SentryIssue(
            id = first.issueId?.toString() ?: first.id ?: "unknown",
            title = first.title ?: "Untitled issue",
            culprit = first.culprit,
            count = events.size,
            level = first.level ?: "error",
            permalink = first.issueId?.let { issuePermalink(org, it) } ?: ""
        )
    }

    /**
     * The endpoint gives no permalink, so it is built the way Sentry itself writes it: the
     * organization has its own subdomain of the host of [baseUrl].
     */
    private fun issuePermalink(org: String, issueId: Long): String {
        val base = URI(baseUrl)
        val port = if (base.port != -1) ":${base.port}" else ""
        return "${base.scheme}://$org.${base.host}$port/issues/$issueId/"
    }

    private fun levelWeight(level: String): Int = when (level) {
        "fatal" -> 4
        "error" -> 3
        "warning" -> 2
        "info" -> 1
        else -> 0
    }
}

internal fun organizationSlugFrom(dsn: String): String? = try {
    URI(dsn).host?.substringBefore(".")?.ifBlank { null }?.let { prefix ->
        Regex("^o(\\d+)$").matchEntire(prefix)?.groupValues?.get(1) ?: prefix
    }
} catch (e: Exception) {
    null
}
