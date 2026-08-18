# Resolve a Recommendation with a Seer Implement Run — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make "resolve a recommendation" start a new Seer run that implements that
recommendation, store the run link in the recommendation, and give the updated recommendation back
so the app can open the link in a browser.

**Architecture:** The Seer HTTP calls move out of `SeerRecommendationEnrichment` into a reusable
`SeerClient` (`io.sentry.buddy.seer`), which knows the auth token, the organization slug and the
project id from environment variables. `FlowAnalysisService.resolveRecommendation` becomes
`suspend`: it starts a **fresh** Seer run with an implement prompt that carries the full flow
context and the one recommendation, builds a `seerRunUrl` from the `sentry_run_id` of that run,
saves the recommendation with `status = RESOLVED` and the URL, and returns only that
recommendation. Buddy never polls the implement run and never makes the pull request — the human
opens the link and presses "Create PR" in the Seer UI.

**Tech Stack:** Ktor `HttpClient` (CIO) with `ContentNegotiation`, kotlinx.serialization,
Ktor `MockEngine` for the tests.

**Spec:** none. This change was designed as a *bounded* change in chat (superpowers:brainstorming),
thus there is no separate spec file. This plan is the written record of that design. The endpoint
reference is `monolith_chat_endpoints.md`, sections 2, 3 and 5.

**Depends on:** the work already on this branch — `SeerRecommendationEnrichment`,
`src/main/resources/flow-analysis-prompt.md`, and the packages `io.sentry.buddy.endpoints.flow`
and `io.sentry.buddy.enrichment`.

## Global Constraints

- **Configuration is three environment variables:** `SENTRY_AUTH_TOKEN` (user auth token, scope
  `org:read`), `SENTRY_ORG` (organization **slug**, e.g. `sentry-sdks`), `SENTRY_PROJECT_ID`
  (e.g. `5428559`). `SENTRY_ORG` replaces the organization slug that
  `SeerRecommendationEnrichment` takes from the DSN today, because the DSN gives the numeric
  organization id, which is the wrong value for the browser URL.
- **Seer is optional.** If `SENTRY_AUTH_TOKEN` or `SENTRY_ORG` is absent, there is no `SeerClient`:
  the recommendation enrichment is not in the pipeline, and `resolveRecommendation` keeps the
  behavior of today (mark `RESOLVED`, no URL). The server continues to operate.
- **The URL format is**
  `https://{org}.sentry.io/issues/?project={projectId}&statsPeriod=10m&explorerRunId={sentry_run_id}`.
  `explorerRunId` is the **UUID** `sentry_run_id` of the start response, not the numeric `run_id`.
  If `SENTRY_PROJECT_ID` is absent, the `project` parameter is not in the URL.
- **One fresh run for each resolve.** Do not continue the analysis run
  (`POST .../explorer-chat/{run_id}/`). A fresh `POST .../explorer-chat/` gets the whole context
  again in the prompt.
- **Resolve does not wait.** It starts the run and answers at once. No poll loop, no `create_pr`
  call.
- **A failed start changes nothing.** If the Seer start call fails, the recommendation stays
  `OPEN` and is not saved, and the route answers `502`. Thus the app can try again.
- `id`, `status` and `seerRunUrl` are never taken from a model answer. Kotlin gives them.

---

## File Structure

- Create: `src/main/kotlin/io/sentry/buddy/seer/SeerClient.kt` — the HTTP access to the Seer
  explorer chat (start a run, wait for the answer) and the run URL for the browser. One
  responsibility: talk to Seer.
- Create: `src/main/kotlin/io/sentry/buddy/seer/SeerPrompts.kt` — builds the two prompts from the
  flow data. One responsibility: text. It has no HTTP knowledge.
- Create: `src/main/resources/flow-implement-prompt.md` — the instructions for the implement run.
- Create: `src/test/kotlin/io/sentry/buddy/seer/SeerClientTest.kt`
- Create: `src/test/kotlin/io/sentry/buddy/seer/SeerPromptsTest.kt`
- Modify: `src/main/kotlin/io/sentry/buddy/enrichment/SeerRecommendationEnrichment.kt` — keeps only
  the parse of the recommendations and the pipeline step; the HTTP and the prompt move out.
- Modify: `src/test/kotlin/io/sentry/buddy/enrichment/SeerRecommendationEnrichmentTest.kt`
- Modify: `src/main/kotlin/io/sentry/buddy/Models.kt` — `Recommendation.seerRunUrl`.
- Modify: `src/main/kotlin/io/sentry/buddy/endpoints/flow/FlowAnalysisService.kt` — `suspend`
  resolve, new `ResolveOutcome`.
- Modify: `src/main/kotlin/io/sentry/buddy/endpoints/flow/FlowAnalysisRoutes.kt` — answer with the
  recommendation, `502` for a failed start.
- Modify: `src/main/kotlin/io/sentry/buddy/endpoints/flow/ConfigureFlowAnalysis.kt` — read the
  three environment variables and make the `SeerClient`.
- Modify: `src/test/kotlin/io/sentry/buddy/flow/FlowAnalysisServiceTest.kt`,
  `src/test/kotlin/io/sentry/buddy/flow/FlowAnalysisRoutesTest.kt`.

**Note on the test suite:** `OpenUrlValidatorTest > rejects a subdomain of sentry_io` fails before
this work starts (it also fails on `main`). It is not related to this plan. Do not repair it, and
do not let it stop you — but do not let the count of other failures grow.

---

### Task 1: `SeerClient`

**Files:**
- Create: `src/main/kotlin/io/sentry/buddy/seer/SeerClient.kt`
- Test: `src/test/kotlin/io/sentry/buddy/seer/SeerClientTest.kt`
- Modify: `src/main/kotlin/io/sentry/buddy/enrichment/SeerRecommendationEnrichment.kt`
- Modify: `src/test/kotlin/io/sentry/buddy/enrichment/SeerRecommendationEnrichmentTest.kt`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces, all in the package `io.sentry.buddy.seer`:
  - `data class SeerRun(val runId: Long, val sentryRunId: String)`
  - `class SeerClient(authToken: String, org: String, projectId: String? = null, httpClient: HttpClient = <CIO default>, baseUrl: String = "https://sentry.io", pollIntervalMs: Long = 2_000, timeoutMs: Long = 120_000)`
  - `suspend fun SeerClient.startRun(query: String): SeerRun`
  - `suspend fun SeerClient.awaitAnswer(runId: Long): String`
  - `fun SeerClient.runUrl(sentryRunId: String): String`
- Also produces the new constructor of the enrichment:
  `class SeerRecommendationEnrichment(seerClient: SeerClient, json: Json = Json { ignoreUnknownKeys = true })`.
  Task 5 uses it.

The enrichment keeps `internal fun extractJsonArray(output: String): String` and
`internal fun parseRecommendations(output: String, json: Json): List<Recommendation>` unchanged —
they are about recommendations, not about transport.

- [ ] **Step 1: Write the failing `SeerClient` test**

Create `src/test/kotlin/io/sentry/buddy/seer/SeerClientTest.kt`:

```kotlin
package io.sentry.buddy.seer

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SeerClientTest {

    private val requestedUrls = mutableListOf<String>()

    private fun clientOf(vararg responses: Pair<String, HttpStatusCode>): HttpClient {
        var index = 0
        val engine = MockEngine { request ->
            requestedUrls += request.url.toString()
            val (body, status) = responses[minOf(index++, responses.size - 1)]
            respond(
                content = ByteReadChannel(body),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        return HttpClient(engine) { install(ContentNegotiation) { json() } }
    }

    private fun seerClient(httpClient: HttpClient, projectId: String? = "5428559") = SeerClient(
        authToken = "token",
        org = "sentry-sdks",
        projectId = projectId,
        httpClient = httpClient,
        pollIntervalMs = 1L,
        timeoutMs = 1000L
    )

    @Test
    fun `startRun posts to the org explorer-chat endpoint and returns both run ids`() = runBlocking {
        val client = seerClient(clientOf("""{"run_id": 42, "sentry_run_id": "3f2c-uuid"}""" to HttpStatusCode.OK))

        val run = client.startRun("analyze this")

        assertEquals(42L, run.runId)
        assertEquals("3f2c-uuid", run.sentryRunId)
        assertEquals("https://sentry.io/api/0/organizations/sentry-sdks/seer/explorer-chat/", requestedUrls.single())
    }

    @Test
    fun `startRun throws when the endpoint denies the call`() = runBlocking {
        val client = seerClient(clientOf("""{"detail": "no access"}""" to HttpStatusCode.Forbidden))

        val error = assertFailsWith<IllegalStateException> { client.startRun("analyze this") }

        assertTrue(error.message!!.contains("403"))
    }

    @Test
    fun `awaitAnswer polls until the run is completed and gives the last finished block`() = runBlocking {
        val client = seerClient(
            clientOf(
                """{"session": {"run_id": 42, "status": "processing", "blocks": []}}""" to HttpStatusCode.OK,
                """{"session": {"run_id": 42, "status": "completed", "blocks": [
                     {"id": "b1", "message": "thinking", "loading": true},
                     {"id": "b2", "message": "the answer", "loading": false}
                   ]}}""" to HttpStatusCode.OK
            )
        )

        assertEquals("the answer", client.awaitAnswer(42L))
    }

    @Test
    fun `awaitAnswer retries while the run is not yet created`() = runBlocking {
        val client = seerClient(
            clientOf(
                """{"detail": "This run is still being created; retry shortly."}""" to HttpStatusCode.Conflict,
                """{"session": {"run_id": 42, "status": "completed", "blocks": [
                     {"id": "b1", "message": "the answer", "loading": false}
                   ]}}""" to HttpStatusCode.OK
            )
        )

        assertEquals("the answer", client.awaitAnswer(42L))
    }

    @Test
    fun `awaitAnswer throws when the run ends with an error status`() = runBlocking {
        val client = seerClient(
            clientOf("""{"session": {"run_id": 42, "status": "error", "blocks": []}}""" to HttpStatusCode.OK)
        )

        assertFailsWith<IllegalStateException> { client.awaitAnswer(42L) }
        Unit
    }

    @Test
    fun `awaitAnswer throws when the run does not complete in time`() = runBlocking {
        val client = seerClient(
            clientOf("""{"session": {"run_id": 42, "status": "processing", "blocks": []}}""" to HttpStatusCode.OK)
        )

        assertFailsWith<IllegalStateException> { client.awaitAnswer(42L) }
        Unit
    }

    @Test
    fun `runUrl builds the explorer link for the org and the project`() {
        val client = seerClient(clientOf("{}" to HttpStatusCode.OK))

        assertEquals(
            "https://sentry-sdks.sentry.io/issues/?project=5428559&statsPeriod=10m&explorerRunId=3f2c-uuid",
            client.runUrl("3f2c-uuid")
        )
    }

    @Test
    fun `runUrl leaves out the project parameter when there is no project id`() {
        val client = seerClient(clientOf("{}" to HttpStatusCode.OK), projectId = null)

        assertEquals(
            "https://sentry-sdks.sentry.io/issues/?statsPeriod=10m&explorerRunId=3f2c-uuid",
            client.runUrl("3f2c-uuid")
        )
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "io.sentry.buddy.seer.SeerClientTest"`
Expected: FAIL — compilation error, `SeerClient` does not exist.

- [ ] **Step 3: Write `SeerClient`**

Create `src/main/kotlin/io/sentry/buddy/seer/SeerClient.kt`. The body of `startRun`,
`awaitAnswer` and the private DTOs comes from the present
`SeerRecommendationEnrichment`; new are `sentryRunId` in the start response, the `org` from the
configuration in place of the DSN, and `runUrl`.

```kotlin
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
    private val httpClient: HttpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } },
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
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "io.sentry.buddy.seer.SeerClientTest"`
Expected: PASS (8 tests)

- [ ] **Step 5: Make the enrichment use the client**

Replace the whole content of
`src/main/kotlin/io/sentry/buddy/enrichment/SeerRecommendationEnrichment.kt` with this. The prompt
is still built here; Task 2 moves it out.

```kotlin
package io.sentry.buddy.enrichment

import io.sentry.buddy.FlowAnalysisRequest
import io.sentry.buddy.FlowAnalysisResponse
import io.sentry.buddy.Recommendation
import io.sentry.buddy.SentryIssue
import io.sentry.buddy.Severity
import io.sentry.buddy.seer.SeerClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

private const val MAX_EVENTS_IN_PROMPT = 200

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
    private val seerClient: SeerClient,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : Enrichment {

    override suspend fun enrich(request: FlowAnalysisRequest, response: FlowAnalysisResponse): FlowAnalysisResponse {
        val run = seerClient.startRun(buildPrompt(request, response.issues))
        val recommendations = parseRecommendations(seerClient.awaitAnswer(run.runId), json)

        if (recommendations.isEmpty()) return response
        return response.copy(recommendations = response.recommendations + recommendations)
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

- [ ] **Step 6: Repair the enrichment test**

In `src/test/kotlin/io/sentry/buddy/enrichment/SeerRecommendationEnrichmentTest.kt`:

1. Add the import `io.sentry.buddy.seer.SeerClient`.
2. Replace `enrichmentWith` with:

```kotlin
    private fun enrichmentWith(client: HttpClient) = SeerRecommendationEnrichment(
        seerClient = SeerClient(
            authToken = "token",
            org = "sentry-sdks",
            projectId = "5428559",
            httpClient = client,
            pollIntervalMs = 1L,
            timeoutMs = 1000L
        ),
        json = json
    )
```

3. Put `"sentry_run_id": "uuid"` in every mocked start body (the four `{"run_id": 42, ...}` bodies).
   The body of the "start call is denied" test does not change.
4. Delete the test `enrich returns the response unchanged when the dsn has no organization`. The
   organization no longer comes from the DSN, thus the case does not exist. Delete the now unused
   import of `assertEquals`-only helpers **only if** the compiler reports them as unused; keep
   everything else.

The other seven tests do not change: `parseRecommendations` keeps its signature, and the
enrichment behavior through the client is the same.

- [ ] **Step 7: Run the whole suite**

Run: `./gradlew test`
Expected: BUILD FAILED with exactly one failure, the pre-existing
`OpenUrlValidatorTest > rejects a subdomain of sentry_io`. Every other test passes. If any other
test fails, repair it before you continue.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/io/sentry/buddy/seer/SeerClient.kt \
        src/test/kotlin/io/sentry/buddy/seer/SeerClientTest.kt \
        src/main/kotlin/io/sentry/buddy/enrichment/SeerRecommendationEnrichment.kt \
        src/test/kotlin/io/sentry/buddy/enrichment/SeerRecommendationEnrichmentTest.kt
git commit -m "refactor(seer): extract SeerClient with the run id, the org and the run url"
```

---

### Task 2: The two prompts in one place

**Files:**
- Create: `src/main/kotlin/io/sentry/buddy/seer/SeerPrompts.kt`
- Create: `src/main/resources/flow-implement-prompt.md`
- Test: `src/test/kotlin/io/sentry/buddy/seer/SeerPromptsTest.kt`
- Modify: `src/main/kotlin/io/sentry/buddy/enrichment/SeerRecommendationEnrichment.kt`

**Interfaces:**
- Consumes: `SeerClient` (Task 1) only as the neighbor in the same package. It consumes
  `FlowAnalysisRequest`, `SentryIssue` and `Recommendation` from `io.sentry.buddy`.
- Produces, in `io.sentry.buddy.seer`:
  - `object SeerPrompts`
  - `fun SeerPrompts.analysis(request: FlowAnalysisRequest, issues: List<SentryIssue>): String`
  - `fun SeerPrompts.implement(request: FlowAnalysisRequest, issues: List<SentryIssue>, recommendation: Recommendation): String`
  Task 4 uses `implement`.

- [ ] **Step 1: Write the failing prompt test**

Create `src/test/kotlin/io/sentry/buddy/seer/SeerPromptsTest.kt`:

```kotlin
package io.sentry.buddy.seer

import io.sentry.buddy.FlowAnalysisEvent
import io.sentry.buddy.FlowAnalysisRequest
import io.sentry.buddy.Recommendation
import io.sentry.buddy.SentryIssue
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertTrue

class SeerPromptsTest {

    private fun request(eventCount: Int = 1) = FlowAnalysisRequest(
        flowId = "flow-1",
        traceIds = listOf("trace-1"),
        startTimeMs = 1000L,
        endTimeMs = 2000L,
        dsn = "https://key@o123.ingest.sentry.io/456",
        userAnnotation = "tapped checkout twice",
        sdk = "io.sentry.android@8.40.0",
        events = (1..eventCount).map {
            FlowAnalysisEvent(type = "click", timestamp = 1000L + it, data = JsonObject(emptyMap()))
        }
    )

    private val issue = SentryIssue(
        id = "g1",
        title = "NPE in checkout",
        count = 3,
        level = "error",
        permalink = "https://sentry.io/g1"
    )

    private val recommendation = Recommendation(
        id = "rec-1",
        title = "Debounce the checkout button",
        description = "It was tapped twice within 200ms."
    )

    @Test
    fun `analysis carries the instructions and the flow context`() {
        val prompt = SeerPrompts.analysis(request(), listOf(issue))

        assertTrue(prompt.contains("Respond with"), "the analysis instructions are missing")
        assertTrue(prompt.contains("tapped checkout twice"))
        assertTrue(prompt.contains("io.sentry.android@8.40.0"))
        assertTrue(prompt.contains("NPE in checkout"))
    }

    @Test
    fun `implement carries the recommendation and the flow context`() {
        val prompt = SeerPrompts.implement(request(), listOf(issue), recommendation)

        assertTrue(prompt.contains("Debounce the checkout button"))
        assertTrue(prompt.contains("It was tapped twice within 200ms."))
        assertTrue(prompt.contains("tapped checkout twice"), "the flow context is missing")
        assertTrue(prompt.contains("NPE in checkout"))
    }

    @Test
    fun `the flow context caps the number of events and says how many are left out`() {
        val prompt = SeerPrompts.analysis(request(eventCount = 250), emptyList())

        assertTrue(prompt.contains("Events (250):"))
        assertTrue(prompt.contains("50 more events not shown"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "io.sentry.buddy.seer.SeerPromptsTest"`
Expected: FAIL — compilation error, `SeerPrompts` does not exist.

- [ ] **Step 3: Write the implement instructions**

Create `src/main/resources/flow-implement-prompt.md`:

```markdown
# Implement One Flow Recommendation

A recorded user flow was analyzed, and one recommendation from that analysis was accepted. Your
task is to make that one change in the code of this repository.

## Rules

- Implement only the recommendation below. Do not do the other recommendations of the analysis,
  and do not do unrelated improvements.
- Read the code before you change it. Keep the style, the naming and the structure of the code
  near your change.
- If the repository has tests for the area that you change, make the necessary test.
- If the recommendation cannot be implemented in this repository (for example, it is about the
  configuration of the Sentry organization, or about a different repository), do not change code.
  Say clearly why, and stop.
- Do not make the pull request. A person looks at your changes and makes the pull request.

## Context

The flow data below is the same data that produced the recommendation. Use it to understand the
problem, and use the Sentry issues to find the code that is concerned.
```

- [ ] **Step 4: Write `SeerPrompts`**

Create `src/main/kotlin/io/sentry/buddy/seer/SeerPrompts.kt`:

```kotlin
package io.sentry.buddy.seer

import io.sentry.buddy.FlowAnalysisRequest
import io.sentry.buddy.Recommendation
import io.sentry.buddy.SentryIssue

private const val MAX_EVENTS_IN_PROMPT = 200

/** Builds the prompts that go into the `query` field of a Seer explorer run. */
object SeerPrompts {

    fun analysis(request: FlowAnalysisRequest, issues: List<SentryIssue>): String = buildString {
        appendLine(resource("/flow-analysis-prompt.md"))
        appendLine()
        append(flowContext(request, issues))
    }

    fun implement(
        request: FlowAnalysisRequest,
        issues: List<SentryIssue>,
        recommendation: Recommendation
    ): String = buildString {
        appendLine(resource("/flow-implement-prompt.md"))
        appendLine()
        appendLine("## Recommendation to implement")
        appendLine()
        appendLine("Title: ${recommendation.title}")
        appendLine("Description: ${recommendation.description}")
        recommendation.link?.let { appendLine("Link: $it") }
        appendLine("Severity: ${recommendation.severity}")
        appendLine()
        append(flowContext(request, issues))
    }

    private fun flowContext(request: FlowAnalysisRequest, issues: List<SentryIssue>): String = buildString {
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

    private fun resource(path: String): String =
        SeerPrompts::class.java.getResource(path)?.readText()
            ?: throw IllegalStateException("$path is not on the classpath")
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests "io.sentry.buddy.seer.SeerPromptsTest"`
Expected: PASS (3 tests)

- [ ] **Step 6: Take the prompt out of the enrichment**

In `src/main/kotlin/io/sentry/buddy/enrichment/SeerRecommendationEnrichment.kt`:

1. Delete the private `buildPrompt` function, the private `instructions` value and the constant
   `MAX_EVENTS_IN_PROMPT`.
2. Delete the now unused imports `io.sentry.buddy.SentryIssue` — keep `FlowAnalysisRequest` and
   `FlowAnalysisResponse`.
3. Add the import `io.sentry.buddy.seer.SeerPrompts`.
4. `enrich` becomes:

```kotlin
    override suspend fun enrich(request: FlowAnalysisRequest, response: FlowAnalysisResponse): FlowAnalysisResponse {
        val run = seerClient.startRun(SeerPrompts.analysis(request, response.issues))
        val recommendations = parseRecommendations(seerClient.awaitAnswer(run.runId), json)

        if (recommendations.isEmpty()) return response
        return response.copy(recommendations = response.recommendations + recommendations)
    }
```

- [ ] **Step 7: Run the whole suite**

Run: `./gradlew test`
Expected: only the pre-existing `OpenUrlValidatorTest` failure.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/io/sentry/buddy/seer/SeerPrompts.kt \
        src/test/kotlin/io/sentry/buddy/seer/SeerPromptsTest.kt \
        src/main/resources/flow-implement-prompt.md \
        src/main/kotlin/io/sentry/buddy/enrichment/SeerRecommendationEnrichment.kt
git commit -m "feat(seer): add the implement prompt and share the flow context between prompts"
```

---

### Task 3: `Recommendation.seerRunUrl`

**Files:**
- Modify: `src/main/kotlin/io/sentry/buddy/Models.kt`
- Test: `src/test/kotlin/io/sentry/buddy/ModelsTest.kt`

**Interfaces:**
- Produces: `Recommendation.seerRunUrl: String?`, JSON name `seer_run_url`, default `null`.
  Tasks 4 and 5 use it.

- [ ] **Step 1: Write the failing model test**

Add these two tests to the existing class in `src/test/kotlin/io/sentry/buddy/ModelsTest.kt`. Keep
the existing tests and the existing imports; add what the compiler asks for
(`io.sentry.buddy.Recommendation`, `kotlinx.serialization.json.Json`, `kotlin.test.assertEquals`,
`kotlin.test.assertTrue` — only those that are not there already).

```kotlin
    @Test
    fun `a recommendation without a seer run url leaves the field out of the JSON`() {
        val json = Json { encodeDefaults = false }

        val encoded = json.encodeToString(
            Recommendation.serializer(),
            Recommendation(id = "rec-1", title = "T", description = "D")
        )

        assertTrue(!encoded.contains("seer_run_url"), "expected no seer_run_url in $encoded")
    }

    @Test
    fun `a recommendation encodes and decodes its seer run url as seer_run_url`() {
        val json = Json { ignoreUnknownKeys = true }
        val url = "https://sentry-sdks.sentry.io/issues/?project=1&statsPeriod=10m&explorerRunId=uuid"

        val encoded = json.encodeToString(
            Recommendation.serializer(),
            Recommendation(id = "rec-1", title = "T", description = "D", seerRunUrl = url)
        )
        val decoded = json.decodeFromString(Recommendation.serializer(), encoded)

        assertTrue(encoded.contains("\"seer_run_url\""), "expected seer_run_url in $encoded")
        assertEquals(url, decoded.seerRunUrl)
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "io.sentry.buddy.ModelsTest"`
Expected: FAIL — compilation error, `Recommendation` has no parameter `seerRunUrl`.

- [ ] **Step 3: Add the field**

In `src/main/kotlin/io/sentry/buddy/Models.kt`, `Recommendation` becomes:

```kotlin
@Serializable
data class Recommendation(
    val id: String,
    val title: String,
    val description: String,
    val link: String? = null,
    val severity: Severity = Severity.MEDIUM,
    val resolvable: Boolean = true,
    val status: RecommendationStatus = RecommendationStatus.OPEN,
    @SerialName("seer_run_url") val seerRunUrl: String? = null
)
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "io.sentry.buddy.ModelsTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/sentry/buddy/Models.kt src/test/kotlin/io/sentry/buddy/ModelsTest.kt
git commit -m "feat(flow-analysis): add seer_run_url to a recommendation"
```

---

### Task 4: Resolve starts an implement run

**Files:**
- Modify: `src/main/kotlin/io/sentry/buddy/endpoints/flow/FlowAnalysisService.kt`
- Modify: `src/main/kotlin/io/sentry/buddy/endpoints/flow/FlowAnalysisRoutes.kt`
- Test: `src/test/kotlin/io/sentry/buddy/flow/FlowAnalysisServiceTest.kt`
- Test: `src/test/kotlin/io/sentry/buddy/flow/FlowAnalysisRoutesTest.kt`

**Interfaces:**
- Consumes: `SeerClient`, `SeerRun`, `SeerClient.startRun`, `SeerClient.runUrl` (Task 1),
  `SeerPrompts.implement` (Task 2), `Recommendation.seerRunUrl` (Task 3).
- Produces:
  - `class FlowAnalysisService(store: FlowAnalysisStore, enrichments: List<Enrichment> = emptyList(), scope: CoroutineScope = ..., seerClient: SeerClient? = null)`
  - `suspend fun FlowAnalysisService.resolveRecommendation(flowId: String, recommendationId: String): ResolveOutcome`
  - `ResolveOutcome.Success(val recommendation: Recommendation)` — **not** the whole response any
    more — and the new `ResolveOutcome.SeerStartFailed(val message: String)`.
  Task 5 uses the constructor.

- [ ] **Step 1: Write the failing service tests**

In `src/test/kotlin/io/sentry/buddy/flow/FlowAnalysisServiceTest.kt`:

1. Add these imports:

```kotlin
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import io.sentry.buddy.seer.SeerClient
import kotlinx.coroutines.runBlocking
import kotlin.test.assertNull
```

2. Give `newService` a `seerClient` parameter, and add a helper that makes one:

```kotlin
    private fun newService(
        store: FlowAnalysisStore = FlowAnalysisStore(createTempDirectory("flow-service-test").toFile()),
        enrichments: List<Enrichment> = listOf(Enrichment { _, response -> response.copy(title = "Test title") }),
        seerClient: SeerClient? = null
    ): FlowAnalysisService = FlowAnalysisService(
        store = store,
        enrichments = enrichments,
        scope = CoroutineScope(Dispatchers.Unconfined),
        seerClient = seerClient
    )

    private fun seerClientThatResponds(body: String, status: HttpStatusCode = HttpStatusCode.OK) = SeerClient(
        authToken = "token",
        org = "sentry-sdks",
        projectId = "5428559",
        httpClient = HttpClient(
            MockEngine { _ ->
                respond(
                    content = ByteReadChannel(body),
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            }
        ) { install(ContentNegotiation) { json() } },
        pollIntervalMs = 1L,
        timeoutMs = 1000L
    )
```

3. The four existing `resolveRecommendation` tests must become `runBlocking`, and the success test
   must read `outcome.recommendation`. Replace them with these six tests:

```kotlin
    @Test
    fun `resolveRecommendation marks a resolvable recommendation as RESOLVED`() = runBlocking {
        val store = FlowAnalysisStore(createTempDirectory("flow-resolve-test").toFile())
        store.saveResult(
            FlowAnalysisResponse(
                flowId = "flow-1",
                status = AnalysisStatus.COMPLETED,
                recommendations = listOf(
                    Recommendation(id = "rec-1", title = "Upgrade SDK", description = "...", resolvable = true)
                )
            )
        )
        val service = newService(store = store)

        val outcome = service.resolveRecommendation("flow-1", "rec-1")

        assertTrue(outcome is ResolveOutcome.Success)
        assertEquals(RecommendationStatus.RESOLVED, outcome.recommendation.status)
        assertNull(outcome.recommendation.seerRunUrl, "without a Seer client there is no run url")
        assertEquals(RecommendationStatus.RESOLVED, store.loadResult("flow-1")!!.recommendations.single().status)
    }

    @Test
    fun `resolveRecommendation starts a Seer run and stores the run url`() = runBlocking {
        val store = FlowAnalysisStore(createTempDirectory("flow-resolve-seer").toFile())
        store.saveRequest(sampleRequest())
        store.saveResult(
            FlowAnalysisResponse(
                flowId = "flow-1",
                status = AnalysisStatus.COMPLETED,
                recommendations = listOf(Recommendation(id = "rec-1", title = "T", description = "D"))
            )
        )
        val service = newService(
            store = store,
            seerClient = seerClientThatResponds("""{"run_id": 77, "sentry_run_id": "1ebfee71-uuid"}""")
        )

        val outcome = service.resolveRecommendation("flow-1", "rec-1")

        assertTrue(outcome is ResolveOutcome.Success)
        assertEquals(
            "https://sentry-sdks.sentry.io/issues/?project=5428559&statsPeriod=10m&explorerRunId=1ebfee71-uuid",
            outcome.recommendation.seerRunUrl
        )
        assertEquals(RecommendationStatus.RESOLVED, outcome.recommendation.status)
        assertEquals(
            outcome.recommendation.seerRunUrl,
            store.loadResult("flow-1")!!.recommendations.single().seerRunUrl
        )
    }

    @Test
    fun `resolveRecommendation leaves the recommendation OPEN when the Seer run cannot start`() = runBlocking {
        val store = FlowAnalysisStore(createTempDirectory("flow-resolve-seer-fail").toFile())
        store.saveRequest(sampleRequest())
        store.saveResult(
            FlowAnalysisResponse(
                flowId = "flow-1",
                status = AnalysisStatus.COMPLETED,
                recommendations = listOf(Recommendation(id = "rec-1", title = "T", description = "D"))
            )
        )
        val service = newService(
            store = store,
            seerClient = seerClientThatResponds("""{"detail": "no access"}""", HttpStatusCode.Forbidden)
        )

        val outcome = service.resolveRecommendation("flow-1", "rec-1")

        assertTrue(outcome is ResolveOutcome.SeerStartFailed)
        val stored = store.loadResult("flow-1")!!.recommendations.single()
        assertEquals(RecommendationStatus.OPEN, stored.status)
        assertNull(stored.seerRunUrl)
    }

    @Test
    fun `resolveRecommendation returns NotResolvable for a non-resolvable recommendation`() = runBlocking {
        val store = FlowAnalysisStore(createTempDirectory("flow-resolve-test-2").toFile())
        store.saveResult(
            FlowAnalysisResponse(
                flowId = "flow-1",
                status = AnalysisStatus.COMPLETED,
                recommendations = listOf(
                    Recommendation(id = "rec-1", title = "x", description = "y", resolvable = false)
                )
            )
        )
        val service = newService(store = store)

        assertEquals(ResolveOutcome.NotResolvable, service.resolveRecommendation("flow-1", "rec-1"))
    }

    @Test
    fun `resolveRecommendation returns FlowAnalysisNotFound for an unknown flow`() = runBlocking {
        val service = newService()

        assertEquals(ResolveOutcome.FlowAnalysisNotFound, service.resolveRecommendation("unknown", "rec-1"))
    }

    @Test
    fun `resolveRecommendation returns RecommendationNotFound for an unknown recommendation id`() = runBlocking {
        val store = FlowAnalysisStore(createTempDirectory("flow-resolve-test-3").toFile())
        store.saveResult(FlowAnalysisResponse(flowId = "flow-1", status = AnalysisStatus.COMPLETED))
        val service = newService(store = store)

        assertEquals(ResolveOutcome.RecommendationNotFound, service.resolveRecommendation("flow-1", "unknown"))
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "io.sentry.buddy.flow.FlowAnalysisServiceTest"`
Expected: FAIL — compilation error: `FlowAnalysisService` has no parameter `seerClient`, and
`ResolveOutcome.Success` has no member `recommendation`.

- [ ] **Step 3: Change the service**

In `src/main/kotlin/io/sentry/buddy/endpoints/flow/FlowAnalysisService.kt`:

1. Add the imports `io.sentry.buddy.Recommendation`, `io.sentry.buddy.seer.SeerClient` and
   `io.sentry.buddy.seer.SeerPrompts`.
2. Replace `ResolveOutcome` with:

```kotlin
sealed class ResolveOutcome {
    data class Success(val recommendation: Recommendation) : ResolveOutcome()
    object FlowAnalysisNotFound : ResolveOutcome()
    object RecommendationNotFound : ResolveOutcome()
    object NotResolvable : ResolveOutcome()
    data class SeerStartFailed(val message: String) : ResolveOutcome()
}
```

3. Add the constructor parameter `private val seerClient: SeerClient? = null` as the last
   parameter of `FlowAnalysisService`.
4. Replace `resolveRecommendation` with:

```kotlin
    suspend fun resolveRecommendation(flowId: String, recommendationId: String): ResolveOutcome {
        val current = store.loadResult(flowId) ?: return ResolveOutcome.FlowAnalysisNotFound
        val target = current.recommendations.find { it.id == recommendationId }
            ?: return ResolveOutcome.RecommendationNotFound
        if (!target.resolvable) return ResolveOutcome.NotResolvable

        val seerRunUrl = if (seerClient == null) {
            null
        } else {
            val request = store.loadRequest(flowId)
                ?: return ResolveOutcome.SeerStartFailed("no stored request for flow $flowId")
            try {
                val run = seerClient.startRun(SeerPrompts.implement(request, current.issues, target))
                logger.info("Started the Seer implement run ${run.runId} for $flowId/$recommendationId")
                seerClient.runUrl(run.sentryRunId)
            } catch (e: Exception) {
                logger.warn("Could not start the Seer implement run for $flowId/$recommendationId", e)
                return ResolveOutcome.SeerStartFailed(e.message ?: "unknown error")
            }
        }

        val resolved = target.copy(status = RecommendationStatus.RESOLVED, seerRunUrl = seerRunUrl)
        store.saveResult(
            current.copy(
                recommendations = current.recommendations.map { if (it.id == recommendationId) resolved else it }
            )
        )
        return ResolveOutcome.Success(resolved)
    }
```

- [ ] **Step 4: Run the service tests to verify they pass**

Run: `./gradlew test --tests "io.sentry.buddy.flow.FlowAnalysisServiceTest"`
Expected: FAIL to compile at first, because `FlowAnalysisRoutes.kt` still reads
`outcome.response`. Do Step 5, then run again. Expected then: PASS (11 tests)

- [ ] **Step 5: Change the route**

In `src/main/kotlin/io/sentry/buddy/endpoints/flow/FlowAnalysisRoutes.kt`, replace the `when` of
the resolve route with:

```kotlin
                when (val outcome = flowAnalysisService.resolveRecommendation(flowId, recommendationId)) {
                    is ResolveOutcome.Success -> call.respond(outcome.recommendation)
                    ResolveOutcome.FlowAnalysisNotFound ->
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "flow not found"))

                    ResolveOutcome.RecommendationNotFound ->
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "recommendation not found"))

                    ResolveOutcome.NotResolvable ->
                        call.respond(HttpStatusCode.Conflict, mapOf("error" to "recommendation is not resolvable"))

                    is ResolveOutcome.SeerStartFailed ->
                        call.respond(
                            HttpStatusCode.BadGateway,
                            mapOf("error" to "could not start the Seer run: ${outcome.message}")
                        )
                }
```

The route body is already a suspend context, thus the `suspend` resolve needs no other change.

- [ ] **Step 6: Write the route test**

Add to `src/test/kotlin/io/sentry/buddy/flow/FlowAnalysisRoutesTest.kt` — add the imports
`io.sentry.buddy.AnalysisStatus`, `io.sentry.buddy.FlowAnalysisResponse`,
`io.sentry.buddy.Recommendation` and `io.sentry.buddy.endpoints.flow.FlowAnalysisStore` (if it is
not there already):

```kotlin
    @Test
    fun `POST resolve answers with the updated recommendation only`() = testApplication {
        val store = FlowAnalysisStore(createTempDirectory("flow-routes-resolve").toFile())
        store.saveResult(
            FlowAnalysisResponse(
                flowId = "flow-3",
                status = AnalysisStatus.COMPLETED,
                recommendations = listOf(Recommendation(id = "rec-1", title = "T", description = "D"))
            )
        )
        application {
            install(ContentNegotiation) { json() }
            flowAnalysisRoutes(
                FlowAnalysisService(store = store, scope = CoroutineScope(Dispatchers.Unconfined))
            )
        }

        val response = client.post("/v1/flow-analysis/flow-3/recommendations/rec-1/resolve")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("\"rec-1\"", body["id"].toString())
        assertEquals("\"RESOLVED\"", body["status"].toString())
        assertEquals(null, body["flow_id"], "the answer is the recommendation, not the whole analysis")
    }
```

- [ ] **Step 7: Run the whole suite**

Run: `./gradlew test`
Expected: only the pre-existing `OpenUrlValidatorTest` failure.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/io/sentry/buddy/endpoints/flow/FlowAnalysisService.kt \
        src/main/kotlin/io/sentry/buddy/endpoints/flow/FlowAnalysisRoutes.kt \
        src/test/kotlin/io/sentry/buddy/flow/FlowAnalysisServiceTest.kt \
        src/test/kotlin/io/sentry/buddy/flow/FlowAnalysisRoutesTest.kt
git commit -m "feat(flow-analysis): resolve a recommendation with a Seer implement run"
```

---

### Task 5: Configuration and manual verification

**Files:**
- Modify: `src/main/kotlin/io/sentry/buddy/endpoints/flow/ConfigureFlowAnalysis.kt`
- Modify: `README.md`

**Interfaces:**
- Consumes: `SeerClient` (Task 1), `SeerRecommendationEnrichment(seerClient, json)` (Task 1),
  `FlowAnalysisService(..., seerClient)` (Task 4).
- Produces: nothing that a later task uses.

- [ ] **Step 1: Read the three environment variables**

Replace the content of `src/main/kotlin/io/sentry/buddy/endpoints/flow/ConfigureFlowAnalysis.kt`
with:

```kotlin
package io.sentry.buddy.endpoints.flow

import io.ktor.server.application.*
import io.sentry.buddy.enrichment.IssueEnrichment
import io.sentry.buddy.enrichment.SdkUpgradeEnrichment
import io.sentry.buddy.enrichment.SeerRecommendationEnrichment
import io.sentry.buddy.enrichment.TitleEnrichment
import io.sentry.buddy.seer.SeerClient
import java.io.File

private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

fun Application.configureFlowAnalysis(
    flowAnalysisService: FlowAnalysisService = defaultFlowAnalysisService(
        File(environment.config.propertyOrNull("flowAnalysis.dataDir")?.getString() ?: "data/flow-analysis")
    )
) {
    flowAnalysisRoutes(flowAnalysisService)
}

private fun defaultFlowAnalysisService(dataDir: File): FlowAnalysisService {
    val authToken = env("SENTRY_AUTH_TOKEN")
    val org = env("SENTRY_ORG")
    val seerClient = if (authToken != null && org != null) {
        SeerClient(authToken = authToken, org = org, projectId = env("SENTRY_PROJECT_ID"))
    } else {
        null
    }

    return FlowAnalysisService(
        store = FlowAnalysisStore(dataDir),
        enrichments = buildList {
            if (authToken != null) add(IssueEnrichment(authToken = authToken))
            if (seerClient != null) add(SeerRecommendationEnrichment(seerClient))
            add(SdkUpgradeEnrichment())
            add(TitleEnrichment())
        },
        seerClient = seerClient
    )
}
```

- [ ] **Step 2: Run the whole suite**

Run: `./gradlew test`
Expected: only the pre-existing `OpenUrlValidatorTest` failure.

- [ ] **Step 3: Write down the configuration in the README**

Add this section to `README.md`, at the end:

```markdown
## Configuration

| Environment variable | Necessary for | Notes |
|---|---|---|
| `SENTRY_AUTH_TOKEN` | the Sentry issues and all Seer calls | A **user** auth token with the scope `org:read`. |
| `SENTRY_ORG` | all Seer calls | The organization **slug**, e.g. `sentry-sdks`. |
| `SENTRY_PROJECT_ID` | the `project` parameter of the Seer run link | E.g. `5428559`. Optional. |

Without `SENTRY_AUTH_TOKEN` or `SENTRY_ORG` the server operates, but it makes no Seer
recommendations, and resolving a recommendation only marks it `RESOLVED` and gives no
`seer_run_url`.
```

- [ ] **Step 4: Manually verify against a running server**

```bash
SENTRY_AUTH_TOKEN=<user-auth-token> SENTRY_ORG=<org-slug> SENTRY_PROJECT_ID=<project-id> ./gradlew run
```

```bash
curl -s -X POST http://localhost:8080/v1/flow-analysis \
  -H "Content-Type: application/json" \
  -d '{
        "flow_id": "resolve-check-1",
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

# wait for COMPLETED, then take the id of one recommendation
curl -s http://localhost:8080/v1/flow-analysis/resolve-check-1

curl -s -X POST \
  http://localhost:8080/v1/flow-analysis/resolve-check-1/recommendations/<recommendation-id>/resolve
```

Expected: the resolve call answers in about one second with one recommendation object, with
`"status": "RESOLVED"` and a `seer_run_url` of the form
`https://<org>.sentry.io/issues/?project=<project>&statsPeriod=10m&explorerRunId=<uuid>`. The link
opens the run in the Sentry UI, where Seer implements the recommendation and where you press
"Create PR".

If the answer is `502`, read the `error` field and compare it with section 9 of
`monolith_chat_endpoints.md`.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/sentry/buddy/endpoints/flow/ConfigureFlowAnalysis.kt README.md
git commit -m "feat(flow-analysis): configure Seer with SENTRY_ORG and SENTRY_PROJECT_ID"
```
