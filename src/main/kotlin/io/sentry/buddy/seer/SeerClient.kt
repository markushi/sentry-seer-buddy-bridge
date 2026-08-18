package io.sentry.buddy.seer

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
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

/** The two identities of one Seer run: the numeric id for the API, the UUID for the UI link. */
data class SeerRun(val runId: Long, val sentryRunId: String)

@Serializable
private data class StartRunRequest(
    val query: String,
    @SerialName("page_name") val pageName: String = "external:flow-analysis"
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
    val message: String? = null,
    val loading: Boolean = false
)

class SeerClient(
    private val authToken: String,
    private val org: String,
    private val projectId: String? = null,
    private val httpClient: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    },
    private val baseUrl: String = "https://sentry.io",
    private val pollIntervalMs: Long = 2_000,
    private val timeoutMs: Long = 120_000
) {

    /** Starts a new explorer run. See monolith_chat_endpoints.md section 3. */
    suspend fun startRun(query: String): SeerRun {
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
        val body = httpResponse.body<StartRunResponse>()
        return SeerRun(runId = body.runId, sentryRunId = body.sentryRunId)
    }

    /** Polls the run and gives the message of the last block that is not loading. */
    suspend fun awaitAnswer(runId: Long): String {
        val answer = withTimeoutOrNull(timeoutMs) {
            while (true) {
                val session = pollSession(runId)
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

    /** The link that shows the run in the Sentry UI. */
    fun runUrl(sentryRunId: String): String = buildString {
        append("https://$org.sentry.io/issues/?")
        if (projectId != null) append("project=$projectId&")
        append("statsPeriod=10m&explorerRunId=$sentryRunId")
    }

    /** Gives null while the run is not yet available (404, 409) or is still processing. */
    private suspend fun pollSession(runId: Long): SeerSession? {
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
}
