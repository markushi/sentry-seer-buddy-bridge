# Flow Analysis — SDK Upgrade Recommendation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the first real recommendation source: flag flows recorded with an outdated Sentry SDK by comparing `FlowAnalysisRequest.sdk` against the latest `sentry-java` GitHub release.

**Architecture:** `SdkUpgradeRecommendationSource` implements the existing `RecommendationEngine` interface (from Plan 1) directly — it's just a second implementation, not a new abstraction. A new `CompositeRecommendationEngine` implements `RecommendationEngine` by concatenating results from a list of other `RecommendationEngine`s, so `ConfigureFlowAnalysis.kt` can combine this source with future ones (Plan 4's LLM source) without changing `FlowAnalysisService`.

**Package layout (per the post-Plan-2 refactor):** the codebase now splits `io.sentry.buddy` into
`io.sentry.buddy.flow` (domain models, service, routes, and pipeline-dependency interfaces — no
outbound I/O) and `io.sentry.buddy.tooling` (integrations that call external systems — the Claude
CLI, the Sentry API). `CompositeRecommendationEngine` is pure in-process composition with no I/O,
so it belongs in `io.sentry.buddy.flow` alongside `RecommendationEngine`/`NoOpRecommendationEngine`.
`SdkUpgradeRecommendationSource` calls the GitHub API, so it belongs in `io.sentry.buddy.tooling`
alongside `SentryApiClient`/`ClaudeCliTitleGenerator` — physically under `src/main/kotlin/buddy/util/`
(the directory is named `util`; the package is `tooling` — an existing inconsistency in the
refactor, not something this plan introduces or fixes). Test files stay flat under
`src/test/kotlin/buddy/` with package `io.sentry.buddy`, importing `flow`/`tooling` types
explicitly — matching the existing test files' convention.

**Tech Stack:** Reuses the Ktor client added in Plan 2 (Sentry Issue Fetching) for the GitHub API call.

**Spec:** `docs/superpowers/specs/2026-08-17-flow-analysis-api-design.md`, section 5.1.

**Depends on:** Plan 1 (`FlowAnalysisRequest`, `Recommendation`, `Severity`, `RecommendationEngine`,
`NoOpRecommendationEngine`, `ConfigureFlowAnalysis.kt`) and Plan 2 (adds the `ktor-client-*` dependencies
this plan's HTTP call reuses — if this plan is implemented before Plan 2, add Task 1 from Plan 2's
plan doc first).

## Global Constraints

- GitHub releases endpoint: `GET https://api.github.com/repos/getsentry/sentry-java/releases/latest`,
  response has a `tag_name` field (e.g. `"8.41.0"` or `"v8.41.0"` — strip a leading `v` if present).
- `FlowAnalysisRequest.sdk` is shaped like `"io.sentry.android@8.40.0"` — the version is everything after `@`.
- Version comparison is plain dotted-numeric, left-to-right, missing trailing components treated as 0
  (e.g. `8.41` == `8.41.0`). No pre-release/build-metadata handling — out of scope for a prototype.
- If the SDK version can't be parsed, or the GitHub API call fails for any reason, return an empty
  recommendation list rather than throwing (same reasoning as Plan 2: one source failing shouldn't
  fail the whole flow).
- `Recommendation.resolvable` stays at its model default (`true`) — the user can acknowledge/dismiss
  the upgrade recommendation via the existing resolve endpoint once they've upgraded.

---

## File Structure

- Create: `src/main/kotlin/buddy/flow/CompositeRecommendationEngine.kt` (package `io.sentry.buddy.flow`)
- Create: `src/main/kotlin/buddy/util/SdkUpgradeRecommendationSource.kt` (package `io.sentry.buddy.tooling`)
- Modify: `src/main/kotlin/buddy/flow/ConfigureFlowAnalysis.kt` — wire `CompositeRecommendationEngine(listOf(SdkUpgradeRecommendationSource()))` in place of `NoOpRecommendationEngine`.
- Test: `src/test/kotlin/buddy/CompositeRecommendationEngineTest.kt` (package `io.sentry.buddy`)
- Test: `src/test/kotlin/buddy/SdkUpgradeRecommendationSourceTest.kt` (package `io.sentry.buddy`)

---

### Task 1: `CompositeRecommendationEngine`

**Files:**
- Create: `src/main/kotlin/buddy/flow/CompositeRecommendationEngine.kt` (package `io.sentry.buddy.flow`)
- Test: `src/test/kotlin/buddy/CompositeRecommendationEngineTest.kt` (package `io.sentry.buddy`, imports the `flow` types it needs)

**Interfaces:**
- Consumes: `RecommendationEngine`, `Recommendation`, `FlowAnalysisRequest`, `SentryIssue` (Plan 1).
- Produces: `class CompositeRecommendationEngine(sources: List<RecommendationEngine>) : RecommendationEngine` —
  used by `ConfigureFlowAnalysis.kt` (Task 3) and by Plan 4.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.sentry.buddy

import io.sentry.buddy.flow.CompositeRecommendationEngine
import io.sentry.buddy.flow.FlowAnalysisEvent
import io.sentry.buddy.flow.FlowAnalysisRequest
import io.sentry.buddy.flow.Recommendation
import io.sentry.buddy.flow.RecommendationEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class CompositeRecommendationEngineTest {

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

    @Test
    fun `concatenates recommendations from every source`() = runBlocking {
        val sourceA = RecommendationEngine { _, _ -> listOf(Recommendation(id = "a", title = "A", description = "a")) }
        val sourceB = RecommendationEngine { _, _ -> listOf(Recommendation(id = "b", title = "B", description = "b")) }
        val composite = CompositeRecommendationEngine(listOf(sourceA, sourceB))

        val recommendations = composite.generateRecommendations(sampleRequest(), emptyList())

        assertEquals(listOf("a", "b"), recommendations.map { it.id })
    }

    @Test
    fun `returns an empty list when there are no sources`() = runBlocking {
        val composite = CompositeRecommendationEngine(emptyList())

        assertEquals(emptyList(), composite.generateRecommendations(sampleRequest(), emptyList()))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.sentry.buddy.CompositeRecommendationEngineTest"`
Expected: FAIL — compilation error, `CompositeRecommendationEngine.kt` doesn't exist yet.

- [ ] **Step 3: Write the implementation**

```kotlin
package io.sentry.buddy.flow

class CompositeRecommendationEngine(
    private val sources: List<RecommendationEngine>
) : RecommendationEngine {

    override suspend fun generateRecommendations(
        request: FlowAnalysisRequest,
        issues: List<SentryIssue>
    ): List<Recommendation> = sources.flatMap { it.generateRecommendations(request, issues) }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "io.sentry.buddy.CompositeRecommendationEngineTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/buddy/flow/CompositeRecommendationEngine.kt src/test/kotlin/buddy/CompositeRecommendationEngineTest.kt
git commit -m "feat(flow-analysis): add CompositeRecommendationEngine to combine multiple sources"
```

---

### Task 2: `SdkUpgradeRecommendationSource`

**Files:**
- Create: `src/main/kotlin/buddy/util/SdkUpgradeRecommendationSource.kt` (package `io.sentry.buddy.tooling`)
- Test: `src/test/kotlin/buddy/SdkUpgradeRecommendationSourceTest.kt` (package `io.sentry.buddy`, imports the `flow`/`tooling` types it needs)

**Interfaces:**
- Consumes: `FlowAnalysisRequest`, `Recommendation`, `Severity`, `RecommendationEngine` (Plan 1).
- Produces: `class SdkUpgradeRecommendationSource(httpClient: HttpClient = <default CIO client>, releasesUrl: String = <github releases url>) : RecommendationEngine`,
  with internal `parseSdkVersion(sdk: String): String?` and `isOutdated(current: String, latest: String): Boolean` —
  used by `ConfigureFlowAnalysis.kt` (Task 3).

- [ ] **Step 1: Write the failing tests**

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
import io.sentry.buddy.flow.FlowAnalysisEvent
import io.sentry.buddy.flow.FlowAnalysisRequest
import io.sentry.buddy.tooling.SdkUpgradeRecommendationSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SdkUpgradeRecommendationSourceTest {

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
        val source = SdkUpgradeRecommendationSource()

        assertEquals("8.40.0", source.parseSdkVersion("io.sentry.android@8.40.0"))
    }

    @Test
    fun `parseSdkVersion returns null when there is no @`() {
        val source = SdkUpgradeRecommendationSource()

        assertEquals(null, source.parseSdkVersion("io.sentry.android"))
    }

    @Test
    fun `isOutdated is true when the latest release has a higher version`() {
        val source = SdkUpgradeRecommendationSource()

        assertTrue(source.isOutdated(current = "8.40.0", latest = "8.41.0"))
    }

    @Test
    fun `isOutdated is false when current already matches latest`() {
        val source = SdkUpgradeRecommendationSource()

        assertTrue(!source.isOutdated(current = "8.41.0", latest = "8.41.0"))
    }

    @Test
    fun `isOutdated treats missing trailing components as zero`() {
        val source = SdkUpgradeRecommendationSource()

        assertTrue(!source.isOutdated(current = "8.41.0", latest = "8.41"))
    }

    @Test
    fun `generateRecommendations returns an upgrade recommendation when outdated`() = runBlocking {
        val source = SdkUpgradeRecommendationSource(httpClient = mockClient("8.41.0"))

        val recommendations = source.generateRecommendations(sampleRequest("io.sentry.android@8.40.0"), emptyList())

        assertEquals(1, recommendations.size)
        assertTrue(recommendations.single().title.contains("8.41.0"))
    }

    @Test
    fun `generateRecommendations returns nothing when already up to date`() = runBlocking {
        val source = SdkUpgradeRecommendationSource(httpClient = mockClient("8.40.0"))

        val recommendations = source.generateRecommendations(sampleRequest("io.sentry.android@8.40.0"), emptyList())

        assertEquals(emptyList(), recommendations)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.sentry.buddy.SdkUpgradeRecommendationSourceTest"`
Expected: FAIL — compilation error, `SdkUpgradeRecommendationSource.kt` doesn't exist yet.

- [ ] **Step 3: Write the implementation**

```kotlin
package io.sentry.buddy.tooling

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import io.sentry.buddy.flow.FlowAnalysisRequest
import io.sentry.buddy.flow.Recommendation
import io.sentry.buddy.flow.RecommendationEngine
import io.sentry.buddy.flow.SentryIssue
import io.sentry.buddy.flow.Severity
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
private data class GithubReleaseDto(val tag_name: String)

class SdkUpgradeRecommendationSource(
    private val httpClient: HttpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } },
    private val releasesUrl: String = "https://api.github.com/repos/getsentry/sentry-java/releases/latest"
) : RecommendationEngine {

    override suspend fun generateRecommendations(
        request: FlowAnalysisRequest,
        issues: List<SentryIssue>
    ): List<Recommendation> {
        val currentVersion = parseSdkVersion(request.sdk) ?: return emptyList()
        val latestVersion = fetchLatestReleaseVersion() ?: return emptyList()

        if (!isOutdated(current = currentVersion, latest = latestVersion)) return emptyList()

        return listOf(
            Recommendation(
                id = UUID.randomUUID().toString(),
                title = "Upgrade Sentry SDK to $latestVersion",
                description = "This flow used ${request.sdk}, but sentry-java $latestVersion is available. " +
                    "Newer SDK versions include bug fixes and performance improvements.",
                link = "https://github.com/getsentry/sentry-java/releases/tag/$latestVersion",
                severity = Severity.LOW
            )
        )
    }

    private suspend fun fetchLatestReleaseVersion(): String? = try {
        httpClient.get(releasesUrl) { header("Accept", "application/vnd.github+json") }
            .body<GithubReleaseDto>()
            .tag_name
            .removePrefix("v")
    } catch (e: Exception) {
        null
    }

    internal fun parseSdkVersion(sdk: String): String? =
        sdk.substringAfter("@", missingDelimiterValue = "").ifBlank { null }

    internal fun isOutdated(current: String, latest: String): Boolean {
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
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

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.sentry.buddy.SdkUpgradeRecommendationSourceTest"`
Expected: PASS (7 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/buddy/util/SdkUpgradeRecommendationSource.kt src/test/kotlin/buddy/SdkUpgradeRecommendationSourceTest.kt
git commit -m "feat(flow-analysis): add SDK upgrade recommendation source"
```

---

### Task 3: Wire it into `ConfigureFlowAnalysis.kt`

**Files:**
- Modify: `src/main/kotlin/buddy/flow/ConfigureFlowAnalysis.kt` (package `io.sentry.buddy.flow`)

**Interfaces:**
- Consumes: `CompositeRecommendationEngine` (Task 1, same package — no import needed),
  `SdkUpgradeRecommendationSource` (Task 2, package `io.sentry.buddy.tooling` — needs an import).

- [ ] **Step 1: Update the `recommendationEngine` wiring**

The current file (after the post-Plan-2 refactor and Plan 2's own wiring) looks like this — add
one import and one constructor argument, nothing else changes:

```kotlin
package io.sentry.buddy.flow

import io.ktor.server.application.Application
import io.sentry.buddy.tooling.ClaudeCliTitleGenerator
import io.sentry.buddy.tooling.SdkUpgradeRecommendationSource
import io.sentry.buddy.tooling.SentryApiClient
import java.io.File

fun Application.configureFlowAnalysis(
    flowAnalysisService: FlowAnalysisService = FlowAnalysisService(
        store = FlowAnalysisStore(
            File(environment.config.propertyOrNull("flowAnalysis.dataDir")?.getString() ?: "data/flow-analysis")
        ),
        issueFetcher = System.getenv("SENTRY_AUTH_TOKEN")
            ?.takeIf { it.isNotBlank() }
            ?.let { token -> SentryApiClient(authToken = token) }
            ?: NoOpIssueFetcher,
        recommendationEngine = CompositeRecommendationEngine(listOf(SdkUpgradeRecommendationSource())),
        titleGenerator = ClaudeCliTitleGenerator()
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
        "flow_id": "sdk-check-1",
        "trace_ids": [],
        "start_time_ms": 1000,
        "end_time_ms": 2000,
        "dsn": "https://key@o123.ingest.sentry.io/456",
        "user_annotation": "checking sdk version",
        "sdk": "io.sentry.android@1.0.0",
        "events": [{"type": "click", "timestamp": 1500, "data": {}}]
      }'

curl -s http://localhost:8080/v1/flow-analysis/sdk-check-1
```

Expected: once `status` is `COMPLETED`, `recommendations` contains an "Upgrade Sentry SDK to
..." entry, since `1.0.0` is far behind the real latest `sentry-java` release.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/buddy/flow/ConfigureFlowAnalysis.kt
git commit -m "feat(flow-analysis): wire the SDK upgrade recommendation source into the pipeline"
```
