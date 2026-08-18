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
import io.sentry.buddy.Recommendation
import io.sentry.buddy.SentryIssue
import io.sentry.buddy.Severity
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID

private const val MAX_EVENTS_IN_PROMPT = 200

@Serializable
private data class StartRunRequest(
    val query: String,
    @SerialName("page_name") val pageName: String = "external:flow-analysis"
)

@Serializable
private data class StartRunResponse(@SerialName("run_id") val runId: Long)

@Serializable
private data class RunStateResponse(val session: SeerSession? = null)

@Serializable
private data class SeerSession(
    val status: String,
    val blocks: List<SeerBlock> = emptyList()
)

@Serializable
private data class SeerBlock(
    val message: String? = null,
    val loading: Boolean = false
)

@Serializable
private data class SeerRecommendationDto(
    val title: String,
    val description: String,
    val link: String? = null,
    val severity: Severity = Severity.MEDIUM,
    val resolvable: Boolean = true
)

/** Takes the JSON array out of an answer that can have fences or text around it. */
internal fun extractJsonArray(output: String): String {
    val start = output.indexOf('[')
    val end = output.lastIndexOf(']')
    if (start < 0 || end <= start) throw IllegalStateException("No JSON array in the model answer")
    return output.substring(start, end + 1)
}

internal fun parseRecommendations(output: String, json: Json): List<Recommendation> {
    val dtos = try {
        json.decodeFromString(ListSerializer(SeerRecommendationDto.serializer()), extractJsonArray(output))
    } catch (e: Exception) {
        throw IllegalStateException("Could not parse the recommendations from the Seer answer", e)
    }
    return dtos.map {
        Recommendation(
            id = UUID.randomUUID().toString(),
            title = it.title,
            description = it.description,
            link = it.link,
            severity = it.severity,
            resolvable = it.resolvable
        )
    }
}

class SeerRecommendationEnrichment(
    private val authToken: String,
    private val httpClient: HttpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } },
    private val baseUrl: String = "https://sentry.io",
    private val pollIntervalMs: Long = 2_000,
    private val timeoutMs: Long = 120_000,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : Enrichment {

    private val logger = LoggerFactory.getLogger(SeerRecommendationEnrichment::class.java)

    override suspend fun enrich(request: FlowAnalysisRequest, response: FlowAnalysisResponse): FlowAnalysisResponse {
        val org = organizationSlugFrom(request.dsn)
        if (org == null) {
            logger.warn("No organization slug in the DSN; skipping the Seer recommendations")
            return response
        }

        val runId = startRun(org, buildPrompt(request, response.issues))
        val answer = awaitAnswer(org, runId)
        val recommendations = parseRecommendations(answer, json)

        if (recommendations.isEmpty()) return response
        return response.copy(recommendations = response.recommendations + recommendations)
    }

    private suspend fun startRun(org: String, query: String): Long {
        val httpResponse = httpClient.post("$baseUrl/api/0/organizations/$org/seer/explorer-chat/") {
            header("Authorization", "Bearer $authToken")
            contentType(ContentType.Application.Json)
            setBody(StartRunRequest(query = query))
        }
        if (!httpResponse.status.isSuccess()) {
            throw IllegalStateException(
                "Seer explorer-chat start gave ${httpResponse.status}: ${httpResponse.bodyAsText().take(300)}"
            )
        }
        return httpResponse.body<StartRunResponse>().runId
    }

    private suspend fun awaitAnswer(org: String, runId: Long): String {
        val answer = withTimeoutOrNull(timeoutMs) {
            while (true) {
                val session = pollSession(org, runId)
                when (session?.status) {
                    "completed" -> return@withTimeoutOrNull session.blocks.lastOrNull { !it.loading }?.message
                        ?: throw IllegalStateException("The Seer run $runId completed with no answer block")

                    "error" -> throw IllegalStateException("The Seer run $runId ended with the status error")

                    "awaiting_user_input" ->
                        throw IllegalStateException("The Seer run $runId waits for user input")

                    else -> delay(pollIntervalMs)
                }
            }
            @Suppress("UNREACHABLE_CODE") ""
        }
        return answer ?: throw IllegalStateException("The Seer run $runId did not complete in $timeoutMs ms")
    }

    /** Gives null while the run is not yet available (404, 409) or is still processing. */
    private suspend fun pollSession(org: String, runId: Long): SeerSession? {
        val httpResponse = httpClient.get("$baseUrl/api/0/organizations/$org/seer/explorer-chat/$runId/") {
            header("Authorization", "Bearer $authToken")
        }
        if (httpResponse.status == HttpStatusCode.NotFound || httpResponse.status == HttpStatusCode.Conflict) {
            return null
        }
        if (!httpResponse.status.isSuccess()) {
            throw IllegalStateException(
                "Seer explorer-chat poll gave ${httpResponse.status}: ${httpResponse.bodyAsText().take(300)}"
            )
        }
        return httpResponse.body<RunStateResponse>().session
    }

    private fun buildPrompt(request: FlowAnalysisRequest, issues: List<SentryIssue>): String = buildString {
        appendLine(instructions)
        appendLine()
        appendLine("## Flow data")
        appendLine()
        appendLine("User annotation: ${request.userAnnotation}")
        appendLine("SDK: ${request.sdk}")
        appendLine("Events (${request.events.size}):")
        request.events.take(MAX_EVENTS_IN_PROMPT).forEach { appendLine("- [${it.timestamp}] ${it.type}: ${it.data}") }
        if (request.events.size > MAX_EVENTS_IN_PROMPT) {
            appendLine("- ... ${request.events.size - MAX_EVENTS_IN_PROMPT} more events not shown")
        }
        appendLine("Related Sentry issues (${issues.size}):")
        issues.forEach { appendLine("- ${it.title} (${it.level}, count=${it.count}): ${it.permalink}") }
    }

    private val instructions: String by lazy {
        SeerRecommendationEnrichment::class.java.getResource("/flow-analysis-prompt.md")?.readText()
            ?: throw IllegalStateException("flow-analysis-prompt.md is not on the classpath")
    }
}
