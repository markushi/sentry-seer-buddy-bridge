# Flow Analysis API — Core Skeleton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a working, end-to-end `/v1/flow-analysis` API (submit → async pipeline → poll → resolve) backed by local file persistence, with issue-fetching and recommendation-generation as no-op stubs and real LLM-based title generation via `claude -p`.

**Architecture:** New `io.sentry.buddy` package added alongside the existing flat `io.sentry` files. Ktor routes (`FlowAnalysisRoutes.kt`) delegate to a `FlowAnalysisService` that persists request/result JSON per flow via `FlowAnalysisStore` (plain files under a configurable data dir) and runs a 3-step pipeline (fetch issues → generate recommendations → generate title) as a background coroutine launched on submit. Issue-fetching and recommendation-generation are injected interfaces defaulting to no-op implementations so later plans can swap in real ones without touching `FlowAnalysisService`. The whole thing is wired into the app the same way `configureRouting`/`configureSerialization` are: an `Application.configureFlowAnalysis()` module referenced from `application.yaml`.

**Tech Stack:** Ktor 3.5, kotlinx.serialization, Kotlin coroutines (already transitive via ktor-server-core), JDK `ProcessBuilder` for shelling out to the `claude` CLI. No new Gradle dependencies needed for this plan.

**Spec:** `docs/superpowers/specs/2026-08-17-flow-analysis-api-design.md` — read it alongside this plan; the "Decisions made during planning" section at the bottom resolves every ambiguity referenced below.

## Global Constraints

- No auth/scoping layer — `flowId` is the sole lookup key (spec decision #1).
- Issue-fetching and recommendation-generation are no-op stubs in this plan — real
  implementations land in separate future plans (spec decision #3).
- Initial status on submit is `AnalysisStatus.PROCESSING`, not `PENDING` (spec decision #4).
- Only structural request validation — no invented size caps (spec decision #5).
- Resolve endpoint returns the full `FlowAnalysisResponse` (spec decision #6).
- Follow existing repo convention: one file per module concern, `Application.configureX()`
  functions registered by fully-qualified name in `application.yaml`.
- Package for all new code: `io.sentry.buddy`.

---

## File Structure

- Create: `src/main/kotlin/buddy/FlowAnalysisModels.kt` — request/response data classes and enums.
- Create: `src/main/kotlin/buddy/FlowAnalysisStore.kt` — local file persistence for flow requests/results.
- Create: `src/main/kotlin/buddy/FlowAnalysisPipelineDependencies.kt` — `IssueFetcher`/`RecommendationEngine`
  interfaces + no-op implementations.
- Create: `src/main/kotlin/buddy/TitleGenerator.kt` — `TitleGenerator` interface + `claude -p`-backed
  implementation.
- Create: `src/main/kotlin/buddy/FlowAnalysisService.kt` — orchestration: submit/get/resolve + async pipeline.
- Create: `src/main/kotlin/buddy/FlowAnalysisRoutes.kt` — Ktor route definitions.
- Create: `src/main/kotlin/buddy/ConfigureFlowAnalysis.kt` — `Application.configureFlowAnalysis()` module wiring.
- Modify: `src/main/resources/application.yaml` — register the new module, add `flowAnalysis.dataDir` config.
- Test: `src/test/kotlin/buddy/FlowAnalysisModelsTest.kt`
- Test: `src/test/kotlin/buddy/FlowAnalysisStoreTest.kt`
- Test: `src/test/kotlin/buddy/FlowAnalysisServiceTest.kt`
- Test: `src/test/kotlin/buddy/FlowAnalysisRoutesTest.kt`

---

### Task 1: Data models

**Files:**
- Create: `src/main/kotlin/buddy/FlowAnalysisModels.kt`
- Test: `src/test/kotlin/buddy/FlowAnalysisModelsTest.kt`

**Interfaces:**
- Produces: `FlowAnalysisEvent`, `FlowAnalysisRequest`, `AnalysisStatus`, `RecommendationStatus`, `Severity`,
  `Recommendation`, `SentryIssue`, `FlowAnalysisResponse` — used by every later task.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.sentry.buddy

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class FlowAnalysisModelsTest {

    @Test
    fun `FlowAnalysisRequest round-trips through snake_case JSON keys`() {
        val json = """
            {
              "flow_id": "flow-1",
              "trace_ids": ["trace-1", "trace-2"],
              "start_time_ms": 1000,
              "end_time_ms": 2000,
              "dsn": "https://key@sentry.io/1",
              "user_annotation": "tapped checkout twice",
              "sdk": "io.sentry.android@8.40.0",
              "events": [
                {"type": "click", "timestamp": 1500, "data": {}}
              ]
            }
        """.trimIndent()

        val request = Json.decodeFromString(FlowAnalysisRequest.serializer(), json)

        assertEquals("flow-1", request.flowId)
        assertEquals(listOf("trace-1", "trace-2"), request.traceIds)
        assertEquals(1000L, request.startTimeMs)
        assertEquals("tapped checkout twice", request.userAnnotation)
        assertEquals("click", request.events.single().type)

        val reencoded = Json.encodeToString(FlowAnalysisRequest.serializer(), request)
        val roundTripped = Json.decodeFromString(FlowAnalysisRequest.serializer(), reencoded)
        assertEquals(request, roundTripped)
    }

    @Test
    fun `FlowAnalysisResponse defaults recommendations and issues to empty lists`() {
        val response = FlowAnalysisResponse(flowId = "flow-1", status = AnalysisStatus.PROCESSING)

        assertEquals(emptyList(), response.recommendations)
        assertEquals(emptyList(), response.issues)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.sentry.buddy.FlowAnalysisModelsTest"`
Expected: FAIL — compilation error, `FlowAnalysisModels.kt` doesn't exist yet.

- [ ] **Step 3: Write the implementation**

```kotlin
package io.sentry.buddy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class FlowAnalysisEvent(
    val type: String,
    val timestamp: Long,
    val data: JsonObject
)

@Serializable
data class FlowAnalysisRequest(
    @SerialName("flow_id") val flowId: String,
    @SerialName("trace_ids") val traceIds: List<String>,
    @SerialName("start_time_ms") val startTimeMs: Long,
    @SerialName("end_time_ms") val endTimeMs: Long,
    val dsn: String,
    @SerialName("user_annotation") val userAnnotation: String,
    val sdk: String,
    val events: List<FlowAnalysisEvent>
)

enum class AnalysisStatus { PROCESSING, COMPLETED, FAILED }

enum class RecommendationStatus { OPEN, RESOLVED, DISMISSED, FAILED }

enum class Severity { LOW, MEDIUM, HIGH }

@Serializable
data class Recommendation(
    val id: String,
    val title: String,
    val description: String,
    val link: String? = null,
    val severity: Severity = Severity.MEDIUM,
    val resolvable: Boolean = true,
    val status: RecommendationStatus = RecommendationStatus.OPEN
)

@Serializable
data class SentryIssue(
    val id: String,
    val title: String,
    val culprit: String? = null,
    val count: Int,
    val level: String,
    val permalink: String
)

@Serializable
data class FlowAnalysisResponse(
    @SerialName("flow_id") val flowId: String,
    val status: AnalysisStatus,
    val title: String? = null,
    val recommendations: List<Recommendation> = emptyList(),
    val issues: List<SentryIssue> = emptyList(),
    val error: String? = null
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "io.sentry.buddy.FlowAnalysisModelsTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/buddy/FlowAnalysisModels.kt src/test/kotlin/buddy/FlowAnalysisModelsTest.kt
git commit -m "feat(flow-analysis): add flow analysis data models"
```

---

### Task 2: File-based persistence (`FlowAnalysisStore`)

**Files:**
- Create: `src/main/kotlin/buddy/FlowAnalysisStore.kt`
- Test: `src/test/kotlin/buddy/FlowAnalysisStoreTest.kt`

**Interfaces:**
- Consumes: `FlowAnalysisRequest`, `FlowAnalysisResponse` (Task 1).
- Produces: `class FlowAnalysisStore(baseDir: File)` with `saveRequest(FlowAnalysisRequest)`,
  `loadRequest(flowId: String): FlowAnalysisRequest?`, `saveResult(FlowAnalysisResponse)`,
  `loadResult(flowId: String): FlowAnalysisResponse?` — used by `FlowAnalysisService` (Task 5).

- [ ] **Step 1: Write the failing test**

```kotlin
package io.sentry.buddy

import kotlinx.serialization.json.JsonObject
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FlowAnalysisStoreTest {

    private fun newStore() = FlowAnalysisStore(createTempDirectory("flow-store-test").toFile())

    private fun sampleRequest() = FlowAnalysisRequest(
        flowId = "flow-1",
        traceIds = listOf("trace-1"),
        startTimeMs = 1000L,
        endTimeMs = 2000L,
        dsn = "https://key@sentry.io/1",
        userAnnotation = "tapped checkout twice",
        sdk = "io.sentry.android@8.40.0",
        events = listOf(FlowAnalysisEvent(type = "click", timestamp = 1500L, data = JsonObject(emptyMap())))
    )

    @Test
    fun `saveRequest then loadRequest returns the same request`() {
        val store = newStore()
        val request = sampleRequest()

        store.saveRequest(request)

        assertEquals(request, store.loadRequest("flow-1"))
    }

    @Test
    fun `loadRequest returns null for unknown flow`() {
        assertNull(newStore().loadRequest("unknown"))
    }

    @Test
    fun `saveResult then loadResult returns the same result`() {
        val store = newStore()
        val response = FlowAnalysisResponse(flowId = "flow-1", status = AnalysisStatus.COMPLETED, title = "Checkout flow")

        store.saveResult(response)

        assertEquals(response, store.loadResult("flow-1"))
    }

    @Test
    fun `loadResult returns null for unknown flow`() {
        assertNull(newStore().loadResult("unknown"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.sentry.buddy.FlowAnalysisStoreTest"`
Expected: FAIL — compilation error, `FlowAnalysisStore.kt` doesn't exist yet.

- [ ] **Step 3: Write the implementation**

```kotlin
package io.sentry.buddy

import kotlinx.serialization.json.Json
import java.io.File

class FlowAnalysisStore(private val baseDir: File) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private fun flowAnalysisDir(flowId: String): File = File(baseDir, flowId).apply { mkdirs() }

    fun saveRequest(request: FlowAnalysisRequest) {
        File(flowAnalysisDir(request.flowId), "request.json")
            .writeText(json.encodeToString(FlowAnalysisRequest.serializer(), request))
    }

    fun loadRequest(flowId: String): FlowAnalysisRequest? {
        val file = File(flowAnalysisDir(flowId), "request.json")
        if (!file.exists()) return null
        return json.decodeFromString(FlowAnalysisRequest.serializer(), file.readText())
    }

    fun saveResult(response: FlowAnalysisResponse) {
        File(flowAnalysisDir(response.flowId), "result.json")
            .writeText(json.encodeToString(FlowAnalysisResponse.serializer(), response))
    }

    fun loadResult(flowId: String): FlowAnalysisResponse? {
        val file = File(flowAnalysisDir(flowId), "result.json")
        if (!file.exists()) return null
        return json.decodeFromString(FlowAnalysisResponse.serializer(), file.readText())
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "io.sentry.buddy.FlowAnalysisStoreTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/buddy/FlowAnalysisStore.kt src/test/kotlin/buddy/FlowAnalysisStoreTest.kt
git commit -m "feat(flow-analysis): add local file persistence for flow requests and results"
```

---

### Task 3: Pipeline dependency interfaces (issue fetching + recommendations, stubbed)

**Files:**
- Create: `src/main/kotlin/buddy/FlowAnalysisPipelineDependencies.kt`

**Interfaces:**
- Consumes: `FlowAnalysisRequest`, `SentryIssue`, `Recommendation` (Task 1).
- Produces: `fun interface IssueFetcher { suspend fun fetchIssues(request: FlowAnalysisRequest): List<SentryIssue> }`,
  `object NoOpIssueFetcher : IssueFetcher`, `fun interface RecommendationEngine { suspend fun generateRecommendations(request: FlowAnalysisRequest, issues: List<SentryIssue>): List<Recommendation> }`,
  `object NoOpRecommendationEngine : RecommendationEngine` — used as `FlowAnalysisService` (Task 5) defaults, and
  as the extension points future plans (Sentry issue fetching, SDK-upgrade recommendations, LLM
  recommendations) will implement against.

No test-writing cycle for this task — it's two one-line interfaces and two stateless objects with no
branching logic to assert on; they're exercised indirectly by `FlowAnalysisServiceTest` in Task 5.

- [ ] **Step 1: Write the implementation**

```kotlin
package io.sentry.buddy

fun interface IssueFetcher {
    suspend fun fetchIssues(request: FlowAnalysisRequest): List<SentryIssue>
}

object NoOpIssueFetcher : IssueFetcher {
    override suspend fun fetchIssues(request: FlowAnalysisRequest): List<SentryIssue> = emptyList()
}

fun interface RecommendationEngine {
    suspend fun generateRecommendations(request: FlowAnalysisRequest, issues: List<SentryIssue>): List<Recommendation>
}

object NoOpRecommendationEngine : RecommendationEngine {
    override suspend fun generateRecommendations(
        request: FlowAnalysisRequest,
        issues: List<SentryIssue>
    ): List<Recommendation> = emptyList()
}
```

- [ ] **Step 2: Verify the module compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/buddy/FlowAnalysisPipelineDependencies.kt
git commit -m "feat(flow-analysis): add issue-fetcher and recommendation-engine extension points (no-op)"
```

---

### Task 4: Title generation via `claude -p`

**Files:**
- Create: `src/main/kotlin/buddy/TitleGenerator.kt`

**Interfaces:**
- Consumes: `FlowAnalysisRequest` (Task 1).
- Produces: `fun interface TitleGenerator { suspend fun generateTitle(request: FlowAnalysisRequest): String }`,
  `class ClaudeCliTitleGenerator : TitleGenerator` — used by `FlowAnalysisService` (Task 5) and
  `ConfigureFlowAnalysis.kt` (Task 6).

`ClaudeCliTitleGenerator` shells out to a real `claude` binary via `ProcessBuilder`, so it isn't
covered by an automated unit test here (that would either require the binary in CI or mock
`ProcessBuilder`, which tests nothing useful). `FlowAnalysisService` depends on the `TitleGenerator`
interface, not this class directly, so `FlowAnalysisServiceTest` (Task 5) exercises the pipeline with a
fake. Verify this class manually once, per Step 3 below.

- [ ] **Step 1: Write the implementation**

```kotlin
package io.sentry.buddy

fun interface TitleGenerator {
    suspend fun generateTitle(request: FlowAnalysisRequest): String
}

class ClaudeCliTitleGenerator : TitleGenerator {

    override suspend fun generateTitle(request: FlowAnalysisRequest): String {
        val prompt = buildString {
            appendLine("In one short sentence (max 12 words), summarize what happened in this user")
            appendLine("session, based on the user's own description and the raw event log. Respond")
            appendLine("with only the sentence, no quotes, no preamble.")
            appendLine()
            appendLine("User description: ${request.userAnnotation}")
            appendLine("Event types observed: ${request.events.map { it.type }.distinct().joinToString(", ")}")
        }

        val process = ProcessBuilder("claude", "-p", prompt).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            val stderr = process.errorStream.bufferedReader().readText().trim()
            throw IllegalStateException("claude -p exited with code $exitCode: $stderr")
        }

        return output.ifBlank { "Untitled flow" }
    }
}
```

- [ ] **Step 2: Verify the module compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Manually verify against the real `claude` CLI**

Run this from a Kotlin REPL or a throwaway `main` function, with `claude` on `PATH`:

```bash
claude -p "In one short sentence (max 12 words), summarize: user tapped checkout twice, then the app crashed."
```

Expected: a single short sentence printed to stdout, exit code 0.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/buddy/TitleGenerator.kt
git commit -m "feat(flow-analysis): generate flow titles via the claude CLI"
```

---

### Task 5: `FlowAnalysisService` — submit, get, resolve, async pipeline

**Files:**
- Create: `src/main/kotlin/buddy/FlowAnalysisService.kt`
- Test: `src/test/kotlin/buddy/FlowAnalysisServiceTest.kt`

**Interfaces:**
- Consumes: `FlowAnalysisStore` (Task 2), `IssueFetcher`/`NoOpIssueFetcher`/`RecommendationEngine`/
  `NoOpRecommendationEngine` (Task 3), `TitleGenerator` (Task 4).
- Produces: `sealed class ResolveOutcome` (`Success(response)`, `FlowAnalysisNotFound`,
  `RecommendationNotFound`, `NotResolvable`), `class FlowAnalysisService(store, issueFetcher, recommendationEngine, titleGenerator, scope)`
  with `submitOrGetExisting(request: FlowAnalysisRequest): FlowAnalysisResponse`,
  `get(flowId: String): FlowAnalysisResponse?`,
  `resolveRecommendation(flowId: String, recommendationId: String): ResolveOutcome` — used by
  `FlowAnalysisRoutes.kt` (Task 6).

Tests inject `scope = CoroutineScope(Dispatchers.Unconfined)` so the pipeline coroutine runs
synchronously to completion within `submitOrGetExisting`'s `launch` call (our fakes never suspend
on real I/O), making assertions deterministic without sleeps or polling.

- [ ] **Step 1: Write the failing tests**

```kotlin
package io.sentry.buddy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FlowAnalysisServiceTest {

    private fun newService(
        store: FlowAnalysisStore = FlowAnalysisStore(createTempDirectory("flow-service-test").toFile()),
        titleGenerator: TitleGenerator = TitleGenerator { "Test title" }
    ): FlowAnalysisService = FlowAnalysisService(
        store = store,
        titleGenerator = titleGenerator,
        scope = CoroutineScope(Dispatchers.Unconfined)
    )

    private fun sampleRequest(flowId: String = "flow-1") = FlowAnalysisRequest(
        flowId = flowId,
        traceIds = listOf("trace-1"),
        startTimeMs = 1000L,
        endTimeMs = 2000L,
        dsn = "https://key@sentry.io/1",
        userAnnotation = "tapped checkout twice",
        sdk = "io.sentry.android@8.40.0",
        events = listOf(FlowAnalysisEvent(type = "click", timestamp = 1500L, data = JsonObject(emptyMap())))
    )

    @Test
    fun `submit accepts as PROCESSING then completes with a title`() {
        val service = newService()

        val accepted = service.submitOrGetExisting(sampleRequest())
        assertEquals(AnalysisStatus.PROCESSING, accepted.status)

        val result = service.get("flow-1")
        assertNotNull(result)
        assertEquals(AnalysisStatus.COMPLETED, result.status)
        assertEquals("Test title", result.title)
    }

    @Test
    fun `resubmitting the same flow_id returns the existing result instead of reprocessing`() {
        val service = newService()
        service.submitOrGetExisting(sampleRequest())
        val first = service.get("flow-1")

        val second = service.submitOrGetExisting(sampleRequest())

        assertEquals(first, second)
    }

    @Test
    fun `pipeline failure marks the flow as FAILED with the error message`() {
        val service = newService(titleGenerator = TitleGenerator { throw IllegalStateException("boom") })

        service.submitOrGetExisting(sampleRequest())

        val result = service.get("flow-1")
        assertNotNull(result)
        assertEquals(AnalysisStatus.FAILED, result.status)
        assertEquals("boom", result.error)
    }

    @Test
    fun `resolveRecommendation marks a resolvable recommendation as RESOLVED`() {
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
        val resolved = (outcome as ResolveOutcome.Success).response.recommendations.single()
        assertEquals(RecommendationStatus.RESOLVED, resolved.status)
    }

    @Test
    fun `resolveRecommendation returns NotResolvable for a non-resolvable recommendation`() {
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
    fun `resolveRecommendation returns FlowAnalysisNotFound for an unknown flow`() {
        val service = newService()

        assertEquals(ResolveOutcome.FlowAnalysisNotFound, service.resolveRecommendation("unknown", "rec-1"))
    }

    @Test
    fun `resolveRecommendation returns RecommendationNotFound for an unknown recommendation id`() {
        val store = FlowAnalysisStore(createTempDirectory("flow-resolve-test-3").toFile())
        store.saveResult(FlowAnalysisResponse(flowId = "flow-1", status = AnalysisStatus.COMPLETED))
        val service = newService(store = store)

        assertEquals(ResolveOutcome.RecommendationNotFound, service.resolveRecommendation("flow-1", "unknown"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.sentry.buddy.FlowAnalysisServiceTest"`
Expected: FAIL — compilation error, `FlowAnalysisService.kt` doesn't exist yet.

- [ ] **Step 3: Write the implementation**

```kotlin
package io.sentry.buddy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

sealed class ResolveOutcome {
    data class Success(val response: FlowAnalysisResponse) : ResolveOutcome()
    object FlowAnalysisNotFound : ResolveOutcome()
    object RecommendationNotFound : ResolveOutcome()
    object NotResolvable : ResolveOutcome()
}

class FlowAnalysisService(
    private val store: FlowAnalysisStore,
    private val issueFetcher: IssueFetcher = NoOpIssueFetcher,
    private val recommendationEngine: RecommendationEngine = NoOpRecommendationEngine,
    private val titleGenerator: TitleGenerator,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    fun submitOrGetExisting(request: FlowAnalysisRequest): FlowAnalysisResponse {
        store.loadResult(request.flowId)?.let { return it }

        store.saveRequest(request)
        val initial = FlowAnalysisResponse(flowId = request.flowId, status = AnalysisStatus.PROCESSING)
        store.saveResult(initial)

        scope.launch { runPipeline(request) }

        return initial
    }

    fun get(flowId: String): FlowAnalysisResponse? = store.loadResult(flowId)

    fun resolveRecommendation(flowId: String, recommendationId: String): ResolveOutcome {
        val current = store.loadResult(flowId) ?: return ResolveOutcome.FlowAnalysisNotFound
        val target = current.recommendations.find { it.id == recommendationId }
            ?: return ResolveOutcome.RecommendationNotFound
        if (!target.resolvable) return ResolveOutcome.NotResolvable

        val updated = current.copy(
            recommendations = current.recommendations.map {
                if (it.id == recommendationId) it.copy(status = RecommendationStatus.RESOLVED) else it
            }
        )
        store.saveResult(updated)
        return ResolveOutcome.Success(updated)
    }

    private suspend fun runPipeline(request: FlowAnalysisRequest) {
        val result = try {
            val issues = issueFetcher.fetchIssues(request)
            val recommendations = recommendationEngine.generateRecommendations(request, issues)
            val title = titleGenerator.generateTitle(request)
            FlowAnalysisResponse(
                flowId = request.flowId,
                status = AnalysisStatus.COMPLETED,
                title = title,
                recommendations = recommendations,
                issues = issues
            )
        } catch (e: Exception) {
            FlowAnalysisResponse(
                flowId = request.flowId,
                status = AnalysisStatus.FAILED,
                error = e.message ?: "unknown error"
            )
        }
        store.saveResult(result)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.sentry.buddy.FlowAnalysisServiceTest"`
Expected: PASS (7 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/buddy/FlowAnalysisService.kt src/test/kotlin/buddy/FlowAnalysisServiceTest.kt
git commit -m "feat(flow-analysis): add FlowAnalysisService orchestrating submit/get/resolve and the async pipeline"
```

---

### Task 6: HTTP routes + module wiring

**Files:**
- Create: `src/main/kotlin/buddy/FlowAnalysisRoutes.kt`
- Create: `src/main/kotlin/buddy/ConfigureFlowAnalysis.kt`
- Modify: `src/main/resources/application.yaml`
- Test: `src/test/kotlin/buddy/FlowAnalysisRoutesTest.kt`

**Interfaces:**
- Consumes: `FlowAnalysisService`, `ResolveOutcome` (Task 5), `FlowAnalysisRequest`, `FlowAnalysisResponse` (Task 1),
  `FlowAnalysisStore` (Task 2), `ClaudeCliTitleGenerator` (Task 4).
- Produces: `fun Application.flowAnalysisRoutes(flowAnalysisService: FlowAnalysisService)`,
  `fun Application.configureFlowAnalysis(flowAnalysisService: FlowAnalysisService = <default>)` — the latter is what
  `application.yaml` references as a module.

- [ ] **Step 1: Write the failing tests**

```kotlin
package io.sentry.buddy

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class FlowAnalysisRoutesTest {

    private fun requestJson(flowId: String) = """
        {
          "flow_id": "$flowId",
          "trace_ids": ["trace-1"],
          "start_time_ms": 1000,
          "end_time_ms": 2000,
          "dsn": "https://key@sentry.io/1",
          "user_annotation": "tapped checkout twice",
          "sdk": "io.sentry.android@8.40.0",
          "events": [{"type": "click", "timestamp": 1500, "data": {}}]
        }
    """.trimIndent()

    private fun newTestService() = FlowAnalysisService(
        store = FlowAnalysisStore(createTempDirectory("flow-routes-test").toFile()),
        titleGenerator = TitleGenerator { "Test title" },
        scope = CoroutineScope(Dispatchers.Unconfined)
    )

    @Test
    fun `POST v1 flow-analysis returns 202 with PROCESSING then COMPLETED status`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            flowAnalysisRoutes(newTestService())
        }

        val response = client.post("/v1/flow-analysis") {
            contentType(ContentType.Application.Json)
            setBody(requestJson("flow-1"))
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText())
        assertEquals("\"PROCESSING\"", body.jsonObject["status"].toString())
    }

    @Test
    fun `GET v1 flow-analysis id returns COMPLETED after the synchronous pipeline finishes`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            flowAnalysisRoutes(newTestService())
        }

        client.post("/v1/flow-analysis") {
            contentType(ContentType.Application.Json)
            setBody(requestJson("flow-2"))
        }

        val response = client.get("/v1/flow-analysis/flow-2")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText())
        assertEquals("\"COMPLETED\"", body.jsonObject["status"].toString())
        assertEquals("\"Test title\"", body.jsonObject["title"].toString())
    }

    @Test
    fun `GET v1 flow-analysis unknown id returns 404`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            flowAnalysisRoutes(newTestService())
        }

        val response = client.get("/v1/flow-analysis/does-not-exist")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `POST v1 flow-analysis with blank flow_id returns 400`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            flowAnalysisRoutes(newTestService())
        }

        val response = client.post("/v1/flow-analysis") {
            contentType(ContentType.Application.Json)
            setBody(requestJson(""))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.sentry.buddy.FlowAnalysisRoutesTest"`
Expected: FAIL — compilation error, `FlowAnalysisRoutes.kt` doesn't exist yet.

- [ ] **Step 3: Write `FlowAnalysisRoutes.kt`**

```kotlin
package io.sentry.buddy

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.flowAnalysisRoutes(flowAnalysisService: FlowAnalysisService) {
    routing {
        route("/v1/flow-analysis") {

            post {
                val request = call.receive<FlowAnalysisRequest>()
                val validationError = validateFlowRequest(request)
                if (validationError != null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to validationError))
                    return@post
                }

                val accepted = flowAnalysisService.submitOrGetExisting(request)
                call.respond(HttpStatusCode.Accepted, accepted)
            }

            get("/{flowId}") {
                val flowId = call.parameters["flowId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest)

                val analysis = flowAnalysisService.get(flowId)
                    ?: return@get call.respond(HttpStatusCode.NotFound)

                call.respond(analysis)
            }

            post("/{flowId}/recommendations/{recommendationId}/resolve") {
                val flowId = call.parameters["flowId"]!!
                val recommendationId = call.parameters["recommendationId"]!!

                when (val outcome = flowAnalysisService.resolveRecommendation(flowId, recommendationId)) {
                    is ResolveOutcome.Success -> call.respond(outcome.response)
                    ResolveOutcome.FlowAnalysisNotFound ->
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "flow not found"))
                    ResolveOutcome.RecommendationNotFound ->
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "recommendation not found"))
                    ResolveOutcome.NotResolvable ->
                        call.respond(HttpStatusCode.Conflict, mapOf("error" to "recommendation is not resolvable"))
                }
            }
        }
    }
}

internal fun validateFlowRequest(request: FlowAnalysisRequest): String? = when {
    request.flowId.isBlank() -> "flow_id must not be blank"
    request.dsn.isBlank() -> "dsn must not be blank"
    request.events.isEmpty() -> "events must not be empty"
    request.startTimeMs > request.endTimeMs -> "start_time_ms must be <= end_time_ms"
    else -> null
}
```

- [ ] **Step 4: Write `ConfigureFlowAnalysis.kt`**

```kotlin
package io.sentry.buddy

import io.ktor.server.application.Application
import java.io.File

fun Application.configureFlowAnalysis(
    flowAnalysisService: FlowAnalysisService = FlowAnalysisService(
        store = FlowAnalysisStore(
            File(environment.config.propertyOrNull("flowAnalysis.dataDir")?.getString() ?: "data/flow-analysis")
        ),
        titleGenerator = ClaudeCliTitleGenerator()
    )
) {
    flowAnalysisRoutes(flowAnalysisService)
}
```

- [ ] **Step 5: Register the module in `application.yaml`**

```yaml
ktor:
  deployment:
    port: 8080
  application:
    modules:
      - io.sentry.SerializationKt.configureSerialization
      - io.sentry.RoutingKt.configureRouting
      - io.sentry.buddy.ConfigureFlowAnalysisKt.configureFlowAnalysis

flowAnalysis:
  dataDir: "data/flow-analysis"
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew test --tests "io.sentry.buddy.FlowAnalysisRoutesTest"`
Expected: PASS (4 tests)

- [ ] **Step 7: Run the full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, all tests (existing `ServerTest` + all new `buddy` tests) pass.

- [ ] **Step 8: Manually verify against a running server**

```bash
./gradlew run
```

In another terminal:

```bash
curl -s -X POST http://localhost:8080/v1/flow-analysis \
  -H "Content-Type: application/json" \
  -d '{
        "flow_id": "manual-1",
        "trace_ids": ["trace-1"],
        "start_time_ms": 1000,
        "end_time_ms": 2000,
        "dsn": "https://key@sentry.io/1",
        "user_annotation": "tapped checkout twice",
        "sdk": "io.sentry.android@8.40.0",
        "events": [{"type": "click", "timestamp": 1500, "data": {}}]
      }'
# Expect: HTTP 202, body status "PROCESSING"

curl -s http://localhost:8080/v1/flow-analysis/manual-1
# Expect: HTTP 200, body status "COMPLETED" once claude -p finishes, with a generated title
```

Note: this requires the `claude` CLI to be installed and authenticated on the machine running
the server — the title-generation step will mark the flow `FAILED` with the process's stderr as
`error` otherwise, which is itself useful to confirm.

- [ ] **Step 9: Commit**

```bash
git add src/main/kotlin/buddy/FlowAnalysisRoutes.kt src/main/kotlin/buddy/ConfigureFlowAnalysis.kt \
        src/main/resources/application.yaml src/test/kotlin/buddy/FlowAnalysisRoutesTest.kt
git commit -m "feat(flow-analysis): wire /v1/flow-analysis routes into the application"
```

---

## Follow-up plans (not part of this one)

- **Plan 2:** `docs/superpowers/plans/2026-08-17-flow-analysis-sentry-issues.md` — `SentryIssuesClient`
  implementing `IssueFetcher`, calls the Sentry organization events API filtered by trace id, ranks
  by event count/level, caps at 10.
- **Plan 3:** `docs/superpowers/plans/2026-08-17-flow-analysis-sdk-upgrade-recommendation.md` —
  SDK-upgrade `RecommendationEngine` source checking `sentry-java` GitHub releases, plus
  `CompositeRecommendationEngine` to combine multiple sources.
- **Plan 4:** `docs/superpowers/plans/2026-08-17-flow-analysis-llm-recommendations.md` — LLM-powered
  `RecommendationEngine` source: a skill document + `claude -p` + JSON parsing into `Recommendation`s.

Plans 2-4 each modify `ConfigureFlowAnalysis.kt` to progressively replace the no-op defaults from this
plan; run them in order (2, then 3, then 4) since 3 depends on 2's Gradle dependency addition and
4 depends on 3's `CompositeRecommendationEngine`.
