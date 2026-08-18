package io.sentry.buddy.tooling

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import io.sentry.buddy.flow.Enrichment
import io.sentry.buddy.flow.FlowAnalysisRequest
import io.sentry.buddy.flow.FlowAnalysisResponse
import io.sentry.buddy.flow.SentryIssue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
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
    private val httpClient: HttpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } },
    private val baseUrl: String = "https://sentry.io"
) : Enrichment {

    private val logger = LoggerFactory.getLogger(IssueEnrichment::class.java)

    override suspend fun enrich(request: FlowAnalysisRequest, response: FlowAnalysisResponse): FlowAnalysisResponse =
        response.copy(issues = fetchIssues(request))

    internal suspend fun fetchIssues(request: FlowAnalysisRequest): List<SentryIssue> {
        val org = organizationSlugFrom(request.dsn) ?: return emptyList()

        val events = try {
            request.traceIds.flatMap { traceId -> fetchEventsForTrace(org, traceId) }
        } catch (e: Exception) {
            logger.warn("Failed to fetch Sentry issues for org $org", e)
            return emptyList()
        }

        return events
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

    internal fun organizationSlugFrom(dsn: String): String? = try {
        URI(dsn).host?.substringBefore(".")?.ifBlank { null }?.let { prefix ->
            Regex("^o(\\d+)$").matchEntire(prefix)?.groupValues?.get(1) ?: prefix
        }
    } catch (e: Exception) {
        null
    }
}
