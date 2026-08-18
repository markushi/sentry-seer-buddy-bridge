package io.sentry.buddy.seer

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

/** The two identities of one Seer run: the numeric id for the API, the UUID for the UI link. */
data class SeerRun(val runId: Long, val sentryRunId: String)

/**
 * The `Json` used to decode Seer HTTP responses. Real responses carry far more fields than the
 * DTOs below declare (see monolith_chat_endpoints.md section 5), so unknown keys must be ignored.
 * Exposed so tests can install the same value instead of a second literal that could drift.
 */
internal val seerJson: Json = Json { ignoreUnknownKeys = true }

/** The `page_name` of a run that analyzes a flow. See monolith_chat_endpoints.md section 3.1. */
const val PAGE_NAME_FLOW_ANALYSIS = "external:flow-analysis"

/** The `page_name` of a run that implements one recommendation. */
const val PAGE_NAME_FLOW_IMPLEMENT = "external:flow-implement"

private const val REQUEST_TIMEOUT_MS = 15_000L

/** The wait between two polls never grows beyond this. */
private const val MAX_POLL_INTERVAL_MS = 10_000L

/** A poll with one of these answers is retried: the run is not ready yet, or we polled too often. */
private val retryablePollStatuses = setOf(
    HttpStatusCode.NotFound,
    HttpStatusCode.Conflict,
    HttpStatusCode.TooManyRequests
)

@Serializable
private data class StartRunRequest(
    val query: String,
    @SerialName("page_name") val pageName: String
)

@Serializable
private data class StartRunResponse(
    @SerialName("run_id") val runId: Long,
    @SerialName("sentry_run_id") val sentryRunId: String
)

@Serializable
private data class RunStateResponse(val session: SeerSession? = null)

@Serializable
private data class SeerSession(
    val status: String,
    val blocks: List<SeerBlock> = emptyList()
)

@Serializable
private data class SeerBlock(
    val message: SeerMessage? = null,
    val loading: Boolean = false
)

/**
 * The `message` of a block is an object, not a string: the real answer carries `role`, `content`,
 * `thinking_content`, `tool_calls` and `metadata`. Only `role` and `content` are read here.
 */
@Serializable
private data class SeerMessage(
    val role: String? = null,
    val content: String? = null
)

/** The role of the block that only echoes the prompt back. It is never the answer. */
private const val ROLE_USER = "user"

class SeerClient(
    private val authToken: String,
    private val org: String,
    private val projectId: String? = null,
    private val httpClient: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json(seerJson) }
        install(HttpTimeout) { requestTimeoutMillis = REQUEST_TIMEOUT_MS }
    },
    private val baseUrl: String = "https://sentry.io",
    private val pollIntervalMs: Long = 2_000,
    private val timeoutMs: Long = 120_000
) {

    /** Starts a new explorer run. See monolith_chat_endpoints.md section 3. */
    suspend fun startRun(query: String, pageName: String = PAGE_NAME_FLOW_ANALYSIS): SeerRun {
        val httpResponse = httpClient.post("$baseUrl/api/0/organizations/$org/seer/explorer-chat/") {
            header("Authorization", "Bearer $authToken")
            contentType(ContentType.Application.Json)
            setBody(StartRunRequest(query = query, pageName = pageName))
        }
        if (!httpResponse.status.isSuccess()) {
            throw IllegalStateException(
                "Seer explorer-chat start gave ${httpResponse.status}: ${httpResponse.bodyAsText().take(300)}"
            )
        }
        val body = httpResponse.body<StartRunResponse>()
        return SeerRun(runId = body.runId, sentryRunId = body.sentryRunId)
    }

    /**
     * Polls the run and gives the content of the last block that is not loading, does not come
     * from the user, and carries content. The first block echoes the prompt back with the role
     * `user`, and a block can hold only `tool_results`, `file_patches` or `todos` (contract
     * section 5), thus the last block of a completed run is not necessarily the one with the answer.
     */
    suspend fun awaitAnswer(runId: Long): String {
        val answer = withTimeoutOrNull(timeoutMs) {
            var wait = pollIntervalMs
            while (true) {
                val session = pollSession(runId)
                when (session?.status) {
                    "completed" ->
                        return@withTimeoutOrNull session.blocks.lastOrNull {
                            !it.loading && it.message?.role != ROLE_USER && !it.message?.content.isNullOrBlank()
                        }?.message?.content
                            ?: throw IllegalStateException("The Seer run $runId completed with no answer block")

                    "error" -> throw IllegalStateException("The Seer run $runId ended with the status error")

                    "awaiting_user_input" ->
                        throw IllegalStateException("The Seer run $runId waits for user input")

                    else -> {
                        delay(wait)
                        wait = minOf(wait * 2, MAX_POLL_INTERVAL_MS)
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE") ""
        }
        return answer ?: throw IllegalStateException("The Seer run $runId did not complete in $timeoutMs ms")
    }

    /**
     * The link that shows the run in the Sentry UI. The organization has its own subdomain of the
     * host of [baseUrl], so `https://sentry.io` gives `https://{org}.sentry.io/issues/?...`.
     */
    fun runUrl(sentryRunId: String): String = buildString {
        val base = URI(baseUrl)
        append("${base.scheme}://$org.${base.host}")
        if (base.port != -1) append(":${base.port}")
        append("/issues/?")
        if (projectId != null) append("project=$projectId&")
        append("statsPeriod=10m&explorerRunId=$sentryRunId")
    }

    /**
     * Gives null while the run is not yet available (404, 409), while it is rate limited (429), or
     * while it is still processing.
     */
    private suspend fun pollSession(runId: Long): SeerSession? {
        val httpResponse = httpClient.get("$baseUrl/api/0/organizations/$org/seer/explorer-chat/$runId/") {
            header("Authorization", "Bearer $authToken")
        }
        if (httpResponse.status in retryablePollStatuses) return null
        if (!httpResponse.status.isSuccess()) {
            throw IllegalStateException(
                "Seer explorer-chat poll gave ${httpResponse.status}: ${httpResponse.bodyAsText().take(300)}"
            )
        }
        return httpResponse.body<RunStateResponse>().session
    }
}
