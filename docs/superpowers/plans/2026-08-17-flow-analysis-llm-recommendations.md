# Flow Analysis — LLM-Powered Recommendations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a second recommendation source. It sends the raw flow events, the user annotation
and the related Sentry issues to the Seer explorer chat of the Sentry monolith, and it makes
free-form recommendations from the answer.

**Architecture:** `SeerRecommendationEnrichment` implements the existing `Enrichment`. It builds one
prompt from the `FlowAnalysisRequest` and `response.issues`, starts a Seer run with
`POST /api/0/organizations/{org}/seer/explorer-chat/`, polls
`GET /api/0/organizations/{org}/seer/explorer-chat/{run_id}/` until the status is `completed`, and
parses the JSON array in the last block into `Recommendation`s. The enrichment gives the `id` and
the `status` itself (fresh UUID, `OPEN`) — the model does not control these fields.

**Change against the first version of this plan:** the first version started the local `claude` CLI
with `ProcessBuilder` and a `.claude/skills/flow-analysis/SKILL.md` document. This version uses the
monolith HTTP endpoints. Two results of the change:

- The analysis instructions cannot be a local skill document, because the model operates in Seer.
  The instructions become a prompt resource that the server sends in the `query` field.
- The call becomes an HTTP call. It uses the same `SENTRY_AUTH_TOKEN` and the same Ktor
  `HttpClient` pattern as `IssueEnrichment`. If there is no token, the enrichment is not in the
  pipeline.

**Tech Stack:** Ktor `HttpClient` (CIO) with `ContentNegotiation`, kotlinx.serialization,
`kotlinx.coroutines` `delay`/`withTimeout` for the poll loop. Tests use the Ktor `MockEngine`
(already a test dependency).

**Spec:** `docs/superpowers/specs/2026-08-17-flow-analysis-api-design.md`, section 5.2.

**Endpoint reference:** `monolith_chat_endpoints.md` (sections 2, 3 and 5). Only step 1 (start) and
step 2 (poll) of that document are necessary. This plan does not continue a run and does not make a
pull request.

**Depends on:** the existing `io.sentry.buddy` models (`FlowAnalysisRequest`, `FlowAnalysisEvent`,
`SentryIssue`, `Recommendation`, `Severity`, `RecommendationStatus`, `FlowAnalysisResponse`) and
the existing `io.sentry.buddy.enrichment` package (`Enrichment`, `IssueEnrichment`).

## Global Constraints

- The model must answer with **only** a JSON array that agrees with the schema in the prompt
  resource. Option 1 of `monolith_chat_endpoints.md` has no `artifact_schema` field, thus the
  format is not enforced by the API. Parse defensively: remove markdown fences, then take the
  text from the first `[` to the last `]`.
- `id` and `status` never come from the model. `id` is a fresh UUID. `status` keeps the `OPEN`
  default of `Recommendation`. Kotlin sets them after the parse.
- A failure (HTTP error, timeout, bad JSON) throws. `FlowAnalysisService` catches it, keeps the
  response of the previous enrichments, and writes a message into `enrichment_errors`. This
  agrees with the behavior that `FlowAnalysisService.runPipeline` gives to all enrichments.
- The related Sentry issues come from `response.issues`, not from a parameter. `IssueEnrichment`
  operates before this enrichment and fills `response.issues`.
- Authentication and access (see `monolith_chat_endpoints.md`, section 2):
  - Scope `org:read` is necessary.
  - Only the start call and the poll call are used. The start call permits an organization token,
    but a **user auth token** is recommended, because it also permits later steps.
  - The organization must have the feature flag `organizations:seer-explorer`, the
    `gen-ai-features` feature, `hideAiFeatures` off, an accepted Seer agreement, and
    `allow_joinleave` on. If not, the endpoint gives `403`.
  - Coding is not necessary. The enrichment only reads text.
- Rate limits: 25 POST/60 s and 100 GET/60 s per user. Poll with an interval of 2 s and a
  timeout of 120 s.
- The organization slug comes from the DSN, as in `IssueEnrichment.organizationSlugFrom`. Task 1
  makes this function a shared top-level function.

---

## File Structure

- Create: `src/main/resources/flow-analysis-prompt.md` — the analysis instructions and the output
  schema that the server puts in front of the flow data in the `query` field.
- Modify: `src/main/kotlin/io/sentry/buddy/enrichment/IssueEnrichment.kt` — make
  `organizationSlugFrom` a top-level `internal` function.
- Create: `src/main/kotlin/io/sentry/buddy/enrichment/SeerRecommendationEnrichment.kt`
- Modify: `src/main/kotlin/io/sentry/buddy/flow/ConfigureFlowAnalysis.kt` — add the enrichment
  after `IssueEnrichment`, with the same token condition.
- Test: `src/test/kotlin/io/sentry/buddy/enrichment/SeerRecommendationEnrichmentTest.kt`

---

### Task 1: Share `organizationSlugFrom`

**Files:**
- Modify: `src/main/kotlin/io/sentry/buddy/enrichment/IssueEnrichment.kt`

**Interfaces:**
- Produces: `internal fun organizationSlugFrom(dsn: String): String?` at the package level of
  `io.sentry.buddy.enrichment` — used by `IssueEnrichment` and by `SeerRecommendationEnrichment`
  (Task 3).

- [ ] **Step 1: Move the function out of the class**

Remove the `internal fun organizationSlugFrom` member from `IssueEnrichment` and put the same body
at the file level (below the class):

```kotlin
internal fun organizationSlugFrom(dsn: String): String? = try {
    URI(dsn).host?.substringBefore(".")?.ifBlank { null }?.let { prefix ->
        Regex("^o(\\d+)$").matchEntire(prefix)?.groupValues?.get(1) ?: prefix
    }
} catch (e: Exception) {
    null
}
```

The call in `fetchIssues` (`organizationSlugFrom(request.dsn)`) does not change.

- [ ] **Step 2: Run the existing tests**

Run: `./gradlew test --tests "io.sentry.buddy.enrichment.IssueEnrichmentTest"`
Expected: PASS. If `IssueEnrichmentTest` calls `IssueEnrichment(...).organizationSlugFrom(...)`,
change the call to the top-level function.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/io/sentry/buddy/enrichment/IssueEnrichment.kt \
        src/test/kotlin/io/sentry/buddy/enrichment/IssueEnrichmentTest.kt
git commit -m "refactor(enrichment): share organizationSlugFrom between enrichments"
```

---

### Task 2: Write the analysis prompt resource

**Files:**
- Create: `src/main/resources/flow-analysis-prompt.md`

**Interfaces:** none. `SeerRecommendationEnrichment` (Task 3) reads this file from the classpath
and puts it in front of the flow data in the `query` field.

- [ ] **Step 1: Write the prompt document**

```markdown
# Flow Analysis

You get a description of one recorded user flow: a short dictated description from the user, a
list of raw client-side events in time sequence (clicks, scrolls, network requests, db queries,
etc.), and the Sentry issues that were found for the trace ids of the flow.

Analyze the flow and make improvement recommendations.

## What to look for

- Slow or unsuccessful network requests in the event log
- Slow database requests
- Missing instrumentation. For example, if there are user interactions but no network or database
  queries, the Sentry instrumentation is probably not sufficient
- A relation between a user action and a Sentry issue near in time
- A problem in the user's own description that the events agree with

## Output format

Answer with **only** a JSON array (no markdown code fences, no text before or after) of objects
that agree with this schema:

```json
[
  {
    "title": "string, short imperative summary, max 12 words",
    "description": "string, 1-3 sentences explaining the issue and the suggested fix",
    "link": "string or null, a URL if directly relevant (e.g. a docs page), otherwise null",
    "severity": "LOW | MEDIUM | HIGH"
  }
]
```

- Give an empty array `[]` if you find nothing important. Do not invent recommendations.
- Do not put `id` or `status` in your output. The calling system gives these fields.
- Do not use tools that change code. Only analyze and answer.
```

- [ ] **Step 2: Manually sanity-check the endpoint and the prompt**

With a user auth token in `SENTRY_AUTH_TOKEN` and your organization slug in `ORG`:

```bash
RUN_ID=$(curl -s -X POST "https://sentry.io/api/0/organizations/$ORG/seer/explorer-chat/" \
  -H "Authorization: Bearer $SENTRY_AUTH_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"query": "<contents of flow-analysis-prompt.md>\n\nUser annotation: tapped checkout twice, nothing happened.\nEvents (2):\n- [100] click: {\"target\":\"checkout_button\"}\n- [250] click: {\"target\":\"checkout_button\"}\nRelated Sentry issues (0):", "page_name": "external:flow-analysis"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["run_id"])')

curl -s "https://sentry.io/api/0/organizations/$ORG/seer/explorer-chat/$RUN_ID/" \
  -H "Authorization: Bearer $SENTRY_AUTH_TOKEN" | python3 -m json.tool
```

Expected: the POST gives `200` with `run_id` and `sentry_run_id`. The GET gives `session.status`
`processing` first, then `completed`. The last block with `loading: false` contains a JSON array.

If you get `403`, read the `detail` field and compare it with section 2 of
`monolith_chat_endpoints.md` (user token, feature flags, organization options).

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/flow-analysis-prompt.md
git commit -m "docs: add the flow analysis prompt for the Seer explorer chat"
```

---

### Task 3: `SeerRecommendationEnrichment`

**Files:**
- Create: `src/main/kotlin/io/sentry/buddy/enrichment/SeerRecommendationEnrichment.kt`
- Test: `src/test/kotlin/io/sentry/buddy/enrichment/SeerRecommendationEnrichmentTest.kt`

**Interfaces:**
- Consumes: `FlowAnalysisRequest`, `FlowAnalysisResponse`, `SentryIssue`, `Recommendation`,
  `Severity`, `Enrichment`
  (`fun interface Enrichment { suspend fun enrich(request: FlowAnalysisRequest, response: FlowAnalysisResponse): FlowAnalysisResponse }`),
  `organizationSlugFrom` (Task 1), `flow-analysis-prompt.md` (Task 2).
- Produces: `internal fun extractJsonArray(output: String): String`,
  `internal fun parseRecommendations(output: String, json: Json): List<Recommendation>`,
  `class SeerRecommendationEnrichment(authToken: String, httpClient: HttpClient, baseUrl: String, pollIntervalMs: Long, timeoutMs: Long, json: Json) : Enrichment` —
  used by `ConfigureFlowAnalysis.kt` (Task 4).

`httpClient`, `baseUrl`, `pollIntervalMs` and `timeoutMs` are constructor parameters so that the
tests can use a `MockEngine` and a short interval — the same reason as the `httpClient` parameter
of `IssueEnrichment`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package io.sentry.buddy.enrichment

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import io.sentry.buddy.AnalysisStatus
import io.sentry.buddy.FlowAnalysisEvent
import io.sentry.buddy.FlowAnalysisRequest
import io.sentry.buddy.FlowAnalysisResponse
import io.sentry.buddy.RecommendationStatus
import io.sentry.buddy.SentryIssue
import io.sentry.buddy.Severity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SeerRecommendationEnrichmentTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun sampleRequest() = FlowAnalysisRequest(
        flowId = "flow-1",
        traceIds = listOf("trace-1"),
        startTimeMs = 1000L,
        endTimeMs = 2000L,
        dsn = "https://key@o123.ingest.sentry.io/456",
        userAnnotation = "tapped checkout twice",
        sdk = "io.sentry.android@8.40.0",
        events = listOf(FlowAnalysisEvent(type = "click", timestamp = 1500L, data = JsonObject(emptyMap())))
    )

    private fun sampleResponse(issues: List<SentryIssue> = emptyList()) = FlowAnalysisResponse(
        flowId = "flow-1",
        status = AnalysisStatus.PROCESSING,
        issues = issues
    )

    private fun clientOf(vararg responses: Pair<String, HttpStatusCode>): HttpClient {
        var index = 0
        val engine = MockEngine { _ ->
            val (body, status) = responses[minOf(index++, responses.size - 1)]
            respond(
                content = ByteReadChannel(body),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        return HttpClient(engine) { install(ContentNegotiation) { json(json) } }
    }

    private fun enrichmentWith(client: HttpClient) = SeerRecommendationEnrichment(
        authToken = "token",
        httpClient = client,
        baseUrl = "https://sentry.io",
        pollIntervalMs = 1L,
        timeoutMs = 1000L,
        json = json
    )

    @Test
    fun `parseRecommendations maps a well-formed JSON array to Recommendations with fresh ids`() {
        val output = """
            [
              {"title": "Debounce the checkout button", "description": "It was tapped twice within 200ms.", "severity": "MEDIUM", "resolvable": true},
              {"title": "Retry failed network request", "description": "The submit call timed out.", "link": "https://docs.sentry.io/retries", "severity": "HIGH"}
            ]
        """.trimIndent()

        val recommendations = parseRecommendations(output, json)

        assertEquals(2, recommendations.size)
        assertEquals("Debounce the checkout button", recommendations[0].title)
        assertEquals(Severity.MEDIUM, recommendations[0].severity)
        assertEquals(RecommendationStatus.OPEN, recommendations[0].status)
        assertEquals("https://docs.sentry.io/retries", recommendations[1].link)
        assertNotEquals(recommendations[0].id, recommendations[1].id)
        assertTrue(recommendations[0].id.isNotBlank())
    }

    @Test
    fun `parseRecommendations tolerates markdown fences and surrounding prose`() {
        val output = "Here you are:\n```json\n[{\"title\": \"T\", \"description\": \"D\", \"severity\": \"LOW\"}]\n```\nHope that helps."

        val recommendations = parseRecommendations(output, json)

        assertEquals(1, recommendations.size)
        assertEquals("T", recommendations.single().title)
    }

    @Test
    fun `parseRecommendations returns an empty list for an empty array`() {
        assertEquals(emptyList(), parseRecommendations("[]", json))
    }

    @Test
    fun `enrich starts a run, polls until completed, and appends the recommendations`() = runBlocking {
        val client = clientOf(
            """{"run_id": 42, "sentry_run_id": "uuid"}""" to HttpStatusCode.OK,
            """{"session": {"run_id": 42, "status": "processing", "blocks": []}}""" to HttpStatusCode.OK,
            """{"session": {"run_id": 42, "status": "completed", "blocks": [
                 {"id": "b1", "message": "thinking", "loading": true},
                 {"id": "b2", "message": "[{\"title\": \"T\", \"description\": \"D\", \"severity\": \"LOW\"}]", "loading": false}
               ]}}""" to HttpStatusCode.OK
        )

        val result = enrichmentWith(client).enrich(sampleRequest(), sampleResponse())

        assertEquals(1, result.recommendations.size)
        assertEquals("T", result.recommendations.single().title)
    }

    @Test
    fun `enrich retries while the run is not yet created`() = runBlocking {
        val client = clientOf(
            """{"run_id": 42, "sentry_run_id": "uuid"}""" to HttpStatusCode.OK,
            """{"detail": "This run is still being created; retry shortly."}""" to HttpStatusCode.Conflict,
            """{"session": {"run_id": 42, "status": "completed", "blocks": [
                 {"id": "b1", "message": "[]", "loading": false}
               ]}}""" to HttpStatusCode.OK
        )

        val result = enrichmentWith(client).enrich(sampleRequest(), sampleResponse())

        assertEquals(emptyList(), result.recommendations)
    }

    @Test
    fun `enrich throws when the run ends with an error status`() = runBlocking {
        val client = clientOf(
            """{"run_id": 42, "sentry_run_id": "uuid"}""" to HttpStatusCode.OK,
            """{"session": {"run_id": 42, "status": "error", "blocks": []}}""" to HttpStatusCode.OK
        )

        assertFailsWith<IllegalStateException> {
            enrichmentWith(client).enrich(sampleRequest(), sampleResponse())
        }
        Unit
    }

    @Test
    fun `enrich throws when the start call is denied`() = runBlocking {
        val client = clientOf(
            """{"detail": "A user account is required to continue a conversation."}""" to HttpStatusCode.Forbidden
        )

        assertFailsWith<IllegalStateException> {
            enrichmentWith(client).enrich(sampleRequest(), sampleResponse())
        }
        Unit
    }

    @Test
    fun `enrich throws when the answer is not valid JSON`() = runBlocking {
        val client = clientOf(
            """{"run_id": 42, "sentry_run_id": "uuid"}""" to HttpStatusCode.OK,
            """{"session": {"run_id": 42, "status": "completed", "blocks": [
                 {"id": "b1", "message": "Sure, here are some recommendations: not json", "loading": false}
               ]}}""" to HttpStatusCode.OK
        )

        assertFailsWith<IllegalStateException> {
            enrichmentWith(client).enrich(sampleRequest(), sampleResponse())
        }
        Unit
    }

    @Test
    fun `enrich returns the response unchanged when the dsn has no organization`() = runBlocking {
        val client = clientOf("""{"run_id": 42}""" to HttpStatusCode.OK)

        val response = sampleResponse()
        val result = enrichmentWith(client).enrich(sampleRequest().copy(dsn = "not a url"), response)

        assertEquals(response, result)
    }
}
```

Note on the `error`, `403` and bad-JSON tests: the enrichment throws, and
`FlowAnalysisService.runPipeline` catches the exception and puts the message in
`enrichment_errors`. The pipeline does not stop.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.sentry.buddy.enrichment.SeerRecommendationEnrichmentTest"`
Expected: FAIL — compilation error, `SeerRecommendationEnrichment.kt` does not exist.

- [ ] **Step 3: Write the implementation**

```kotlin
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.sentry.buddy.enrichment.SeerRecommendationEnrichmentTest"`
Expected: PASS (9 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/sentry/buddy/enrichment/SeerRecommendationEnrichment.kt \
        src/test/kotlin/io/sentry/buddy/enrichment/SeerRecommendationEnrichmentTest.kt
git commit -m "feat(flow-analysis): add Seer explorer chat recommendation enrichment"
```

---

### Task 4: Wire it into `ConfigureFlowAnalysis.kt`

**Files:**
- Modify: `src/main/kotlin/io/sentry/buddy/flow/ConfigureFlowAnalysis.kt`

**Interfaces:**
- Consumes: `SeerRecommendationEnrichment` (Task 3), `IssueEnrichment`, `SdkUpgradeEnrichment`,
  `TitleEnrichment` (the existing items of the `enrichments` list of `FlowAnalysisService`).

- [ ] **Step 1: Add the enrichment after `IssueEnrichment`**

```kotlin
package io.sentry.buddy.flow

import io.ktor.server.application.*
import io.sentry.buddy.enrichment.IssueEnrichment
import io.sentry.buddy.enrichment.SdkUpgradeEnrichment
import io.sentry.buddy.enrichment.SeerRecommendationEnrichment
import io.sentry.buddy.enrichment.TitleEnrichment
import java.io.File

fun Application.configureFlowAnalysis(
    flowAnalysisService: FlowAnalysisService = FlowAnalysisService(
        store = FlowAnalysisStore(
            File(environment.config.propertyOrNull("flowAnalysis.dataDir")?.getString() ?: "data/flow-analysis")
        ),
        enrichments = buildList {
            val token = System.getenv("SENTRY_AUTH_TOKEN")?.takeIf { it.isNotBlank() }
            if (token != null) {
                add(IssueEnrichment(authToken = token))
                add(SeerRecommendationEnrichment(authToken = token))
            }
            add(SdkUpgradeEnrichment())
            add(TitleEnrichment())
        }
    )
) {
    flowAnalysisRoutes(flowAnalysisService)
}
```

`SeerRecommendationEnrichment` operates directly after `IssueEnrichment`, thus `response.issues` is
full when it makes the prompt — see the Global Constraints. It needs the same `SENTRY_AUTH_TOKEN`,
because the explorer-chat endpoints use the Sentry API. Without a token, both enrichments are not in
the pipeline, and `SdkUpgradeEnrichment` and `TitleEnrichment` continue to operate.

- [ ] **Step 2: Run the full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 3: Manually verify against a running server**

```bash
SENTRY_AUTH_TOKEN=<user-auth-token> ./gradlew run
```

```bash
curl -s -X POST http://localhost:8080/v1/flow-analysis \
  -H "Content-Type: application/json" \
  -d '{
        "flow_id": "llm-check-1",
        "trace_ids": [],
        "start_time_ms": 1000,
        "end_time_ms": 2000,
        "dsn": "https://key@o123.ingest.sentry.io/456",
        "user_annotation": "I tapped the checkout button twice because nothing seemed to happen the first time",
        "sdk": "io.sentry.android@8.40.0",
        "events": [
          {"type": "click", "timestamp": 1000, "data": {"target": "checkout_button"}},
          {"type": "click", "timestamp": 1150, "data": {"target": "checkout_button"}}
        ]
      }'

curl -s http://localhost:8080/v1/flow-analysis/llm-check-1
```

Expected: when `status` is `COMPLETED`, `recommendations` contains the SDK-upgrade item (if the SDK
version is old) and one or more Seer items about the double tap. Each item has a different `id` and
`status: "OPEN"`. The Seer run can need more time than the other enrichments; poll the GET endpoint
again after some seconds.

If `enrichment_errors` contains a `SeerRecommendationEnrichment` message, compare the message with
section 9 of `monolith_chat_endpoints.md`.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/io/sentry/buddy/flow/ConfigureFlowAnalysis.kt
git commit -m "feat(flow-analysis): wire the Seer recommendation enrichment into the pipeline"
```
