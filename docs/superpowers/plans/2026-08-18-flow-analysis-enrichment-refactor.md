# Flow Analysis — Enrichment Pipeline Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the three separate pipeline seams (`IssueFetcher`, `RecommendationEngine` + `CompositeRecommendationEngine`, `TitleGenerator`) with a single `Enrichment` abstraction, and convert every existing pipeline step (`SentryApiClient`, `SdkUpgradeRecommendationSource`, `ClaudeCliTitleGenerator`) into an `Enrichment` implementation that `FlowAnalysisService` runs as an ordered list.

**Architecture:** `Enrichment` is one function shape — `suspend fun enrich(request, response): FlowAnalysisResponse` — that takes the request plus the response accumulated so far and returns the next response. `FlowAnalysisService` folds a `List<Enrichment>` over an initial in-progress `FlowAnalysisResponse`, so each enrichment can read what earlier ones produced (e.g. a future LLM-recommendation enrichment reading the issues an earlier enrichment attached) and layer its own contribution on top (issues, recommendations, title). This removes the need for `CompositeRecommendationEngine` and the `NoOp*` placeholder objects entirely: an empty or partial list is already a no-op, and `ConfigureFlowAnalysis.kt` builds the list directly with `listOfNotNull(...)`. This is a pure internal restructuring — no HTTP-visible behavior changes, no new dependencies.

**Tech Stack:** No new dependencies. Reuses existing Ktor client, kotlinx.serialization, and SLF4J already in the project.

**Spec:** `docs/superpowers/specs/2026-08-17-flow-analysis-api-design.md`, section 4 (processing pipeline) and section 5 (recommendation engine) — this plan does not change what the pipeline does, only how its steps are wired together, so it doesn't add or remove spec coverage.

**Depends on:** Plans 1–3 (all already merged to `main`) — this plan refactors `FlowAnalysisService`, `SentryApiClient`, `SdkUpgradeRecommendationSource`, `ClaudeCliTitleGenerator`, and `CompositeRecommendationEngine`, all of which already exist on `main`.

## Global Constraints

- Package layout stays as established in Plan 3: `io.sentry.buddy.flow` (pure domain/orchestration, no I/O) holds the `Enrichment` interface and `FlowAnalysisService`; `io.sentry.buddy.tooling` (physically under `src/main/kotlin/buddy/util/`) holds every concrete `Enrichment` that calls an external system (Sentry API, GitHub API, the `claude` CLI).
- Enrichment order in `ConfigureFlowAnalysis.kt` must stay `IssueEnrichment` → `SdkUpgradeEnrichment` → `TitleEnrichment`, preserving the existing pipeline order (fetch issues, then recommend, then title) documented in spec section 4.
- No behavior change: the same HTTP responses, the same recommendation content, the same title-generation prompt, the same error handling (an enrichment's exception still fails the whole flow with `AnalysisStatus.FAILED`, matching today's `runPipeline` try/catch).
- `LlmRecommendationEnrichment` (spec section 5.2 / Plan 4) is **out of scope for this plan** — this plan only prepares the `Enrichment` seam it will plug into later; do not create a stub or placeholder file for it.
- Every renamed/converted class keeps its existing internal test seams (`internal fun parseSdkVersion`, `internal fun isOutdated`, `internal suspend fun fetchIssues`, `internal fun organizationSlugFrom`) so existing test coverage carries over with minimal churn.

---

## File Structure

- Modify: `src/main/kotlin/buddy/flow/FlowAnalysisPipelineDependencies.kt` — replace `IssueFetcher`/`NoOpIssueFetcher`/`RecommendationEngine`/`NoOpRecommendationEngine` with the single `Enrichment` interface.
- Modify: `src/main/kotlin/buddy/flow/FlowAnalysisService.kt` — take `enrichments: List<Enrichment>` instead of `issueFetcher`/`recommendationEngine`/`titleGenerator`; fold them in `runPipeline`.
- Delete: `src/main/kotlin/buddy/flow/CompositeRecommendationEngine.kt` — superseded by the plain `List<Enrichment>` fold.
- Delete: `src/test/kotlin/buddy/CompositeRecommendationEngineTest.kt` — tests a class that no longer exists.
- Delete: `src/main/kotlin/buddy/util/SentryClient.kt` — replaced by `IssueEnrichment.kt`.
- Create: `src/main/kotlin/buddy/util/IssueEnrichment.kt` (package `io.sentry.buddy.tooling`) — `SentryApiClient` renamed to `IssueEnrichment`, implements `Enrichment`.
- Delete: `src/main/kotlin/buddy/util/SdkUpgradeRecommendationSource.kt` — replaced by `SdkUpgradeEnrichment.kt`.
- Create: `src/main/kotlin/buddy/util/SdkUpgradeEnrichment.kt` (package `io.sentry.buddy.tooling`) — `SdkUpgradeRecommendationSource` renamed to `SdkUpgradeEnrichment`, implements `Enrichment`.
- Delete: `src/main/kotlin/buddy/util/TitleGenerator.kt` — the standalone `TitleGenerator` interface is superseded by `Enrichment`; `ClaudeCliTitleGenerator` is replaced by `TitleEnrichment.kt`.
- Create: `src/main/kotlin/buddy/util/TitleEnrichment.kt` (package `io.sentry.buddy.tooling`) — `ClaudeCliTitleGenerator` renamed to `TitleEnrichment`, implements `Enrichment`.
- Modify: `src/main/kotlin/buddy/flow/ConfigureFlowAnalysis.kt` — wire `enrichments = listOfNotNull(IssueEnrichment(...)?, SdkUpgradeEnrichment(), TitleEnrichment())`.
- Modify: `src/test/kotlin/buddy/FlowAnalysisServiceTest.kt` — use `Enrichment` stubs instead of `TitleGenerator` stubs; add a test proving enrichments run in order over the accumulated response.
- Delete: `src/test/kotlin/buddy/SentryApiClientTest.kt` — replaced by `IssueEnrichmentTest.kt`.
- Create: `src/test/kotlin/buddy/IssueEnrichmentTest.kt` — same coverage as the old file plus an `enrich(...)` test.
- Delete: `src/test/kotlin/buddy/SdkUpgradeRecommendationSourceTest.kt` — replaced by `SdkUpgradeEnrichmentTest.kt`.
- Create: `src/test/kotlin/buddy/SdkUpgradeEnrichmentTest.kt` — same coverage as the old file, using `enrich(...)` instead of `generateRecommendations(...)`.

---

### Task 1: Introduce `Enrichment` and convert every pipeline step to it

This is one task, not several, because the interface change and every implementer of the old interfaces are mutually dependent — Kotlin compiles the whole module together, so there is no intermediate state where only some of these files are converted and the build still passes. Do all the steps below, then run the full suite once at the end.

**Files:**
- Modify: `src/main/kotlin/buddy/flow/FlowAnalysisPipelineDependencies.kt`
- Modify: `src/main/kotlin/buddy/flow/FlowAnalysisService.kt`
- Delete: `src/main/kotlin/buddy/flow/CompositeRecommendationEngine.kt`
- Delete: `src/test/kotlin/buddy/CompositeRecommendationEngineTest.kt`
- Delete: `src/main/kotlin/buddy/util/SentryClient.kt`
- Create: `src/main/kotlin/buddy/util/IssueEnrichment.kt`
- Delete: `src/main/kotlin/buddy/util/SdkUpgradeRecommendationSource.kt`
- Create: `src/main/kotlin/buddy/util/SdkUpgradeEnrichment.kt`
- Delete: `src/main/kotlin/buddy/util/TitleGenerator.kt`
- Create: `src/main/kotlin/buddy/util/TitleEnrichment.kt`
- Modify: `src/test/kotlin/buddy/FlowAnalysisServiceTest.kt`
- Delete: `src/test/kotlin/buddy/SentryApiClientTest.kt`
- Create: `src/test/kotlin/buddy/IssueEnrichmentTest.kt`
- Delete: `src/test/kotlin/buddy/SdkUpgradeRecommendationSourceTest.kt`
- Create: `src/test/kotlin/buddy/SdkUpgradeEnrichmentTest.kt`

**Interfaces:**
- Produces: `fun interface Enrichment { suspend fun enrich(request: FlowAnalysisRequest, response: FlowAnalysisResponse): FlowAnalysisResponse }` in `io.sentry.buddy.flow` — the seam `ConfigureFlowAnalysis.kt` (Task 2) wires concrete enrichments into, and the seam Plan 4's `LlmRecommendationEnrichment` will implement later.
- Produces: `class IssueEnrichment(authToken: String, httpClient: HttpClient = ..., baseUrl: String = ...) : Enrichment` with `internal suspend fun fetchIssues(request): List<SentryIssue>` and `internal fun organizationSlugFrom(dsn: String): String?`.
- Produces: `class SdkUpgradeEnrichment(httpClient: HttpClient = ..., releasesUrl: String = ...) : Enrichment` with `internal fun parseSdkVersion(sdk: String): String?` and `internal fun isOutdated(current: String, latest: String): Boolean`.
- Produces: `class TitleEnrichment : Enrichment`.
- Consumes: `FlowAnalysisRequest`, `FlowAnalysisResponse`, `SentryIssue`, `Recommendation`, `Severity` (all from Plan 1, unchanged).

- [ ] **Step 1: Write/update all the failing tests**

Replace `src/test/kotlin/buddy/FlowAnalysisServiceTest.kt` in full:

```kotlin
package io.sentry.buddy

import io.sentry.buddy.flow.AnalysisStatus
import io.sentry.buddy.flow.Enrichment
import io.sentry.buddy.flow.FlowAnalysisEvent
import io.sentry.buddy.flow.FlowAnalysisRequest
import io.sentry.buddy.flow.FlowAnalysisResponse
import io.sentry.buddy.flow.FlowAnalysisService
import io.sentry.buddy.flow.FlowAnalysisStore
import io.sentry.buddy.flow.Recommendation
import io.sentry.buddy.flow.RecommendationStatus
import io.sentry.buddy.flow.ResolveOutcome
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
        enrichments: List<Enrichment> = listOf(Enrichment { _, response -> response.copy(title = "Test title") })
    ): FlowAnalysisService = FlowAnalysisService(
        store = store,
        enrichments = enrichments,
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
        val service = newService(enrichments = listOf(Enrichment { _, _ -> throw IllegalStateException("boom") }))

        service.submitOrGetExisting(sampleRequest())

        val result = service.get("flow-1")
        assertNotNull(result)
        assertEquals(AnalysisStatus.FAILED, result.status)
        assertEquals("boom", result.error)
    }

    @Test
    fun `enrichments run in order, each building on the previous response`() {
        val service = newService(
            enrichments = listOf(
                Enrichment { _, response -> response.copy(title = "First") },
                Enrichment { _, response -> response.copy(title = response.title + " then second") }
            )
        )

        service.submitOrGetExisting(sampleRequest())

        val result = service.get("flow-1")
        assertNotNull(result)
        assertEquals("First then second", result.title)
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
        val resolved = outcome.response.recommendations.single()
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

Delete `src/test/kotlin/buddy/CompositeRecommendationEngineTest.kt`.

Delete `src/test/kotlin/buddy/SentryApiClientTest.kt` and create `src/test/kotlin/buddy/IssueEnrichmentTest.kt`:

```kotlin
package io.sentry.buddy

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.sentry.buddy.flow.AnalysisStatus
import io.sentry.buddy.flow.FlowAnalysisEvent
import io.sentry.buddy.flow.FlowAnalysisRequest
import io.sentry.buddy.flow.FlowAnalysisResponse
import io.sentry.buddy.tooling.IssueEnrichment
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class IssueEnrichmentTest {

    private fun sampleRequest(dsn: String = "https://examplekey@o123.ingest.sentry.io/456") = FlowAnalysisRequest(
        flowId = "flow-1",
        traceIds = listOf("trace-1"),
        startTimeMs = 1000L,
        endTimeMs = 2000L,
        dsn = dsn,
        userAnnotation = "tapped checkout twice",
        sdk = "io.sentry.android@8.40.0",
        events = listOf(FlowAnalysisEvent(type = "click", timestamp = 1500L, data = JsonObject(emptyMap())))
    )

    private fun emptyResponse() = FlowAnalysisResponse(flowId = "flow-1", status = AnalysisStatus.PROCESSING)

    @Test
    fun `organizationSlugFrom extracts the org from a standard ingest DSN`() {
        val enrichment = IssueEnrichment(authToken = "token")

        assertEquals("123", enrichment.organizationSlugFrom("https://examplekey@o123.ingest.sentry.io/456"))
    }

    @Test
    fun `organizationSlugFrom strips the leading o from a numeric ingest-host org id`() {
        val enrichment = IssueEnrichment(authToken = "token")

        assertEquals("447951", enrichment.organizationSlugFrom("https://examplekey@o447951.ingest.sentry.io/456"))
    }

    @Test
    fun `organizationSlugFrom returns null for an unparseable dsn`() {
        val enrichment = IssueEnrichment(authToken = "token")

        assertEquals(null, enrichment.organizationSlugFrom("not a uri"))
    }

    @Test
    fun `fetchIssues parses events, dedups by group, ranks by level then count, caps at 10`() = runBlocking {
        val responseJson = """
            [
              {"id": "e1", "groupID": "g1", "title": "NPE in checkout", "culprit": "Checkout.submit", "level": "error", "permalink": "https://sentry.io/g1"},
              {"id": "e2", "groupID": "g1", "title": "NPE in checkout", "culprit": "Checkout.submit", "level": "error", "permalink": "https://sentry.io/g1"},
              {"id": "e3", "groupID": "g2", "title": "Network timeout", "culprit": "Api.fetch", "level": "warning", "permalink": "https://sentry.io/g2"}
            ]
        """.trimIndent()
        val mockEngine = MockEngine { _ ->
            respond(
                content = responseJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val httpClient = HttpClient(mockEngine) { install(ContentNegotiation) { json() } }
        val enrichment = IssueEnrichment(authToken = "token", httpClient = httpClient)

        val issues = enrichment.fetchIssues(sampleRequest())

        assertEquals(2, issues.size)
        assertEquals("g1", issues[0].id)
        assertEquals(2, issues[0].count)
        assertEquals("g2", issues[1].id)
        assertEquals(1, issues[1].count)
    }

    @Test
    fun `fetchIssues returns an empty list when the dsn cannot be parsed`() = runBlocking {
        val mockEngine = MockEngine { _ -> respond(content = "[]", status = HttpStatusCode.OK) }
        val httpClient = HttpClient(mockEngine) { install(ContentNegotiation) { json() } }
        val enrichment = IssueEnrichment(authToken = "token", httpClient = httpClient)

        val issues = enrichment.fetchIssues(sampleRequest(dsn = "not a uri"))

        assertEquals(emptyList(), issues)
    }

    @Test
    fun `enrich sets the response issues from fetchIssues`() = runBlocking {
        val responseJson = """
            [{"id": "e1", "groupID": "g1", "title": "NPE in checkout", "level": "error", "permalink": "https://sentry.io/g1"}]
        """.trimIndent()
        val mockEngine = MockEngine { _ ->
            respond(
                content = responseJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val httpClient = HttpClient(mockEngine) { install(ContentNegotiation) { json() } }
        val enrichment = IssueEnrichment(authToken = "token", httpClient = httpClient)

        val enriched = enrichment.enrich(sampleRequest(), emptyResponse())

        assertEquals(1, enriched.issues.size)
        assertEquals("g1", enriched.issues[0].id)
    }
}
```

Delete `src/test/kotlin/buddy/SdkUpgradeRecommendationSourceTest.kt` and create `src/test/kotlin/buddy/SdkUpgradeEnrichmentTest.kt`:

```kotlin
package io.sentry.buddy

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.sentry.buddy.flow.AnalysisStatus
import io.sentry.buddy.flow.FlowAnalysisEvent
import io.sentry.buddy.flow.FlowAnalysisRequest
import io.sentry.buddy.flow.FlowAnalysisResponse
import io.sentry.buddy.tooling.SdkUpgradeEnrichment
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SdkUpgradeEnrichmentTest {

    private fun sampleRequest(sdk: String) = FlowAnalysisRequest(
        flowId = "flow-1",
        traceIds = listOf("trace-1"),
        startTimeMs = 1000L,
        endTimeMs = 2000L,
        dsn = "https://key@o123.ingest.sentry.io/456",
        userAnnotation = "tapped checkout twice",
        sdk = sdk,
        events = listOf(FlowAnalysisEvent(type = "click", timestamp = 1500L, data = JsonObject(emptyMap())))
    )

    private fun emptyResponse() = FlowAnalysisResponse(flowId = "flow-1", status = AnalysisStatus.PROCESSING)

    private fun mockClient(tagName: String): HttpClient {
        val mockEngine = MockEngine { _ ->
            respond(
                content = """{"tag_name": "$tagName"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        return HttpClient(mockEngine) { install(ContentNegotiation) { json() } }
    }

    @Test
    fun `parseSdkVersion extracts the version after the @`() {
        val enrichment = SdkUpgradeEnrichment()

        assertEquals("8.40.0", enrichment.parseSdkVersion("io.sentry.android@8.40.0"))
    }

    @Test
    fun `parseSdkVersion returns null when there is no @`() {
        val enrichment = SdkUpgradeEnrichment()

        assertEquals(null, enrichment.parseSdkVersion("io.sentry.android"))
    }

    @Test
    fun `isOutdated is true when the latest release has a higher version`() {
        val enrichment = SdkUpgradeEnrichment()

        assertTrue(enrichment.isOutdated(current = "8.40.0", latest = "8.41.0"))
    }

    @Test
    fun `isOutdated is false when current already matches latest`() {
        val enrichment = SdkUpgradeEnrichment()

        assertTrue(!enrichment.isOutdated(current = "8.41.0", latest = "8.41.0"))
    }

    @Test
    fun `isOutdated treats missing trailing components as zero`() {
        val enrichment = SdkUpgradeEnrichment()

        assertTrue(!enrichment.isOutdated(current = "8.41.0", latest = "8.41"))
    }

    @Test
    fun `enrich appends an upgrade recommendation when outdated`() = runBlocking {
        val enrichment = SdkUpgradeEnrichment(httpClient = mockClient("8.41.0"))

        val enriched = enrichment.enrich(sampleRequest("io.sentry.android@8.40.0"), emptyResponse())

        assertEquals(1, enriched.recommendations.size)
        assertTrue(enriched.recommendations.single().title.contains("8.41.0"))
    }

    @Test
    fun `enrich preserves recommendations already on the response`() = runBlocking {
        val enrichment = SdkUpgradeEnrichment(httpClient = mockClient("8.40.0"))

        val enriched = enrichment.enrich(sampleRequest("io.sentry.android@8.40.0"), emptyResponse())

        assertEquals(emptyList(), enriched.recommendations)
    }
}
```

- [ ] **Step 2: Run the affected tests to verify they fail**

Run: `./gradlew test --tests "io.sentry.buddy.FlowAnalysisServiceTest" --tests "io.sentry.buddy.IssueEnrichmentTest" --tests "io.sentry.buddy.SdkUpgradeEnrichmentTest"`
Expected: FAIL — compilation errors, `Enrichment`/`IssueEnrichment`/`SdkUpgradeEnrichment` don't exist yet.

- [ ] **Step 3: Write the implementation**

Replace `src/main/kotlin/buddy/flow/FlowAnalysisPipelineDependencies.kt` in full:

```kotlin
package io.sentry.buddy.flow

fun interface Enrichment {
    suspend fun enrich(request: FlowAnalysisRequest, response: FlowAnalysisResponse): FlowAnalysisResponse
}
```

Replace `src/main/kotlin/buddy/flow/FlowAnalysisService.kt` in full:

```kotlin
package io.sentry.buddy.flow

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
    private val enrichments: List<Enrichment> = emptyList(),
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
            val initial = FlowAnalysisResponse(flowId = request.flowId, status = AnalysisStatus.PROCESSING)
            val enriched = enrichments.fold(initial) { response, enrichment -> enrichment.enrich(request, response) }
            enriched.copy(status = AnalysisStatus.COMPLETED)
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

Delete `src/main/kotlin/buddy/flow/CompositeRecommendationEngine.kt`.

Delete `src/main/kotlin/buddy/util/SentryClient.kt` and create `src/main/kotlin/buddy/util/IssueEnrichment.kt`:

```kotlin
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
```

Delete `src/main/kotlin/buddy/util/SdkUpgradeRecommendationSource.kt` and create `src/main/kotlin/buddy/util/SdkUpgradeEnrichment.kt`:

```kotlin
package io.sentry.buddy.tooling

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import io.sentry.buddy.flow.Enrichment
import io.sentry.buddy.flow.FlowAnalysisRequest
import io.sentry.buddy.flow.FlowAnalysisResponse
import io.sentry.buddy.flow.Recommendation
import io.sentry.buddy.flow.Severity
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.UUID

@Serializable
private data class GithubReleaseDto(val tag_name: String)

class SdkUpgradeEnrichment(
    private val httpClient: HttpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } },
    private val releasesUrl: String = "https://api.github.com/repos/getsentry/sentry-java/releases/latest"
) : Enrichment {

    private val logger = LoggerFactory.getLogger(SdkUpgradeEnrichment::class.java)

    override suspend fun enrich(request: FlowAnalysisRequest, response: FlowAnalysisResponse): FlowAnalysisResponse {
        val currentVersion = parseSdkVersion(request.sdk) ?: return response
        val latestVersion = fetchLatestReleaseVersion() ?: return response

        if (!isOutdated(current = currentVersion, latest = latestVersion)) return response

        val recommendation = Recommendation(
            id = UUID.randomUUID().toString(),
            title = "Upgrade Sentry SDK to $latestVersion",
            description = "This flow used ${request.sdk}, but sentry-java $latestVersion is available. " +
                "Newer SDK versions include bug fixes and performance improvements.",
            link = "https://github.com/getsentry/sentry-java/releases/tag/$latestVersion",
            severity = Severity.LOW
        )
        return response.copy(recommendations = response.recommendations + recommendation)
    }

    private suspend fun fetchLatestReleaseVersion(): String? = try {
        httpClient.get(releasesUrl) { header("Accept", "application/vnd.github+json") }
            .body<GithubReleaseDto>()
            .tag_name
            .removePrefix("v")
    } catch (e: Exception) {
        logger.warn("Failed to fetch the latest sentry-java release", e)
        null
    }

    internal fun parseSdkVersion(sdk: String): String? =
        sdk.substringAfter("@", missingDelimiterValue = "").ifBlank { null }

    internal fun isOutdated(current: String, latest: String): Boolean {
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
        val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val length = maxOf(currentParts.size, latestParts.size)
        for (i in 0 until length) {
            val currentPart = currentParts.getOrElse(i) { 0 }
            val latestPart = latestParts.getOrElse(i) { 0 }
            if (latestPart > currentPart) return true
            if (latestPart < currentPart) return false
        }
        return false
    }
}
```

Delete `src/main/kotlin/buddy/util/TitleGenerator.kt` and create `src/main/kotlin/buddy/util/TitleEnrichment.kt`:

```kotlin
package io.sentry.buddy.tooling

import io.sentry.buddy.flow.Enrichment
import io.sentry.buddy.flow.FlowAnalysisRequest
import io.sentry.buddy.flow.FlowAnalysisResponse

class TitleEnrichment : Enrichment {

    override suspend fun enrich(request: FlowAnalysisRequest, response: FlowAnalysisResponse): FlowAnalysisResponse =
        response.copy(title = generateTitle(request))

    private fun generateTitle(request: FlowAnalysisRequest): String {
        val prompt = buildString {
            appendLine("In one short sentence (max 12 words), summarize what happened in this user")
            appendLine("session, based on the user's own description and the raw event log. Respond")
            appendLine("with only the sentence, no quotes, no preamble.")
            appendLine()
            appendLine("User description: ${request.userAnnotation}")
            appendLine("Event types observed: ${request.events.map { it.type }.distinct().joinToString(", ")}")
        }

        val process = ProcessBuilder("claude", "-p", prompt).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw IllegalStateException("claude -p exited with code $exitCode: $output")
        }

        return output.ifBlank { "Untitled flow" }
    }
}
```

- [ ] **Step 4: Run the full test suite to verify everything passes**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. Verify the JUnit XML test counts under `build/test-results/` directly rather than trusting a summary — this refactor should not change the total number of tests by more than the one new `FlowAnalysisServiceTest` case and the one new `IssueEnrichmentTest` case added above (minus the two deleted test files' counts moving to their renamed replacements).

- [ ] **Step 5: Commit**

```bash
git add -A -- src/main/kotlin/buddy/flow/FlowAnalysisPipelineDependencies.kt \
  src/main/kotlin/buddy/flow/FlowAnalysisService.kt \
  src/main/kotlin/buddy/flow/CompositeRecommendationEngine.kt \
  src/main/kotlin/buddy/util/SentryClient.kt src/main/kotlin/buddy/util/IssueEnrichment.kt \
  src/main/kotlin/buddy/util/SdkUpgradeRecommendationSource.kt src/main/kotlin/buddy/util/SdkUpgradeEnrichment.kt \
  src/main/kotlin/buddy/util/TitleGenerator.kt src/main/kotlin/buddy/util/TitleEnrichment.kt \
  src/test/kotlin/buddy/FlowAnalysisServiceTest.kt src/test/kotlin/buddy/CompositeRecommendationEngineTest.kt \
  src/test/kotlin/buddy/SentryApiClientTest.kt src/test/kotlin/buddy/IssueEnrichmentTest.kt \
  src/test/kotlin/buddy/SdkUpgradeRecommendationSourceTest.kt src/test/kotlin/buddy/SdkUpgradeEnrichmentTest.kt
git commit -m "refactor(flow-analysis): unify pipeline steps behind a single Enrichment interface"
```

Note: `ConfigureFlowAnalysis.kt` still references the old `SentryApiClient`/`SdkUpgradeRecommendationSource`/`ClaudeCliTitleGenerator`/`CompositeRecommendationEngine` names at this point and will not compile on its own — Task 2 fixes it immediately after. If your toolchain requires a green build before every commit, do Task 2's Step 1 edit as part of this same commit instead of committing here; otherwise proceed straight to Task 2.

---

### Task 2: Wire the enrichment list into `ConfigureFlowAnalysis.kt`

**Files:**
- Modify: `src/main/kotlin/buddy/flow/ConfigureFlowAnalysis.kt`

**Interfaces:**
- Consumes: `Enrichment`, `FlowAnalysisService` (Task 1); `IssueEnrichment`, `SdkUpgradeEnrichment`, `TitleEnrichment` (Task 1, package `io.sentry.buddy.tooling`).

- [ ] **Step 1: Update the wiring**

Replace `src/main/kotlin/buddy/flow/ConfigureFlowAnalysis.kt` in full:

```kotlin
package io.sentry.buddy.flow

import io.ktor.server.application.Application
import io.sentry.buddy.tooling.IssueEnrichment
import io.sentry.buddy.tooling.SdkUpgradeEnrichment
import io.sentry.buddy.tooling.TitleEnrichment
import java.io.File

fun Application.configureFlowAnalysis(
    flowAnalysisService: FlowAnalysisService = FlowAnalysisService(
        store = FlowAnalysisStore(
            File(environment.config.propertyOrNull("flowAnalysis.dataDir")?.getString() ?: "data/flow-analysis")
        ),
        enrichments = listOfNotNull(
            System.getenv("SENTRY_AUTH_TOKEN")
                ?.takeIf { it.isNotBlank() }
                ?.let { token -> IssueEnrichment(authToken = token) },
            SdkUpgradeEnrichment(),
            TitleEnrichment()
        )
    )
) {
    flowAnalysisRoutes(flowAnalysisService)
}
```

- [ ] **Step 2: Run the full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 3: Manually verify against a running server**

```bash
./gradlew run
```

```bash
curl -s -X POST http://localhost:8080/v1/flow-analysis \
  -H "Content-Type: application/json" \
  -d '{
        "flow_id": "enrichment-refactor-check-1",
        "trace_ids": [],
        "start_time_ms": 1000,
        "end_time_ms": 2000,
        "dsn": "https://key@o123.ingest.sentry.io/456",
        "user_annotation": "checking the enrichment refactor",
        "sdk": "io.sentry.android@1.0.0",
        "events": [{"type": "click", "timestamp": 1500, "data": {}}]
      }'

curl -s http://localhost:8080/v1/flow-analysis/enrichment-refactor-check-1
```

Expected: once `status` is `COMPLETED`, the response has both a `title` (via `TitleEnrichment`) and a `recommendations` entry ("Upgrade Sentry SDK to ...", via `SdkUpgradeEnrichment`) — identical shape to before this refactor, just produced by the new pipeline.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/buddy/flow/ConfigureFlowAnalysis.kt
git commit -m "feat(flow-analysis): wire the enrichment list into the pipeline"
```
