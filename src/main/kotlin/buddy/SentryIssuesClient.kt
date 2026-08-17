package io.sentry.buddy

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
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

class SentryIssuesClient(
    private val authToken: String,
    private val httpClient: HttpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } },
    private val baseUrl: String = "https://sentry.io"
) : IssueFetcher {

    override suspend fun fetchIssues(request: FlowAnalysisRequest): List<SentryIssue> {
        val org = organizationSlugFrom(request.dsn) ?: return emptyList()

        val events = try {
            request.traceIds.flatMap { traceId -> fetchEventsForTrace(org, traceId) }
        } catch (e: Exception) {
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
        URI(dsn).host?.substringBefore(".")?.ifBlank { null }
    } catch (e: Exception) {
        null
    }
}
