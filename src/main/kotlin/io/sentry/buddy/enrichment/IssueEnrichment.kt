package io.sentry.buddy.enrichment

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import io.sentry.buddy.FlowAnalysisRequest
import io.sentry.buddy.FlowAnalysisResponse
import io.sentry.buddy.SentryIssue
import io.sentry.buddy.seer.seerJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
private data class SentryEventDto(
    val id: String? = null,
    @SerialName("groupID") val groupId: String? = null,
    val title: String? = null,
    val culprit: String? = null,
    val level: String? = null,
    val permalink: String? = null
)

class IssueEnrichment(
    private val authToken: String,
    private val httpClient: HttpClient = HttpClient(CIO) { install(ContentNegotiation) { json(seerJson) } },
    private val baseUrl: String = "https://sentry.io"
) : Enrichment {

    override suspend fun enrich(request: FlowAnalysisRequest, response: FlowAnalysisResponse): FlowAnalysisResponse =
        response.copy(issues = fetchIssues(request))

    /**
     * A failure is not swallowed: it reaches `FlowAnalysisService`, which records it in
     * `enrichment_errors`. Only a DSN without an organization gives a quiet empty list, because
     * that is a configuration fact and not a failure.
     */
    internal suspend fun fetchIssues(request: FlowAnalysisRequest): List<SentryIssue> {
        val org = organizationSlugFrom(request.dsn) ?: return emptyList()

        return request.traceIds.flatMap { traceId -> fetchEventsForTrace(org, traceId) }
            .groupBy { it.groupId ?: it.id }
            .values
            .map { toIssue(it) }
            .sortedWith(compareByDescending<SentryIssue> { levelWeight(it.level) }.thenByDescending { it.count })
            .take(10)
    }

    private suspend fun fetchEventsForTrace(org: String, traceId: String): List<SentryEventDto> =
        httpClient.get("$baseUrl/api/0/organizations/$org/events/") {
            header("Authorization", "Bearer $authToken")
            parameter("query", "trace:$traceId")
        }.body()

    private fun toIssue(events: List<SentryEventDto>): SentryIssue {
        val first = events.first()
        return SentryIssue(
            id = first.groupId ?: first.id ?: "unknown",
            title = first.title ?: "Untitled issue",
            culprit = first.culprit,
            count = events.size,
            level = first.level ?: "error",
            permalink = first.permalink ?: ""
        )
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
