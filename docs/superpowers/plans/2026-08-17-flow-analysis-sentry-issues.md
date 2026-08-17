# Flow Analysis — Sentry Issue Fetching Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the `NoOpIssueFetcher` stub with a real `IssueFetcher` that queries the Sentry API for issues related to a flow's trace ids, ranks them, and caps the result at 10.

**Architecture:** A new `SentryIssuesClient` implements the existing `IssueFetcher` interface (from Plan 1) using a Ktor `HttpClient`. It derives the org slug from the flow's DSN, queries the Sentry organization events API once per trace id, dedups the results by issue group, ranks by severity level then event count, and caps at 10. `ConfigureFlowAnalysis.kt` wires it in only when a `SENTRY_AUTH_TOKEN` environment variable is present, falling back to the existing no-op otherwise — so the server keeps working without secrets configured.

**Tech Stack:** Ktor client (CIO engine + content negotiation), kotlinx.serialization, `ktor-client-mock` for tests.

**Spec:** `docs/superpowers/specs/2026-08-17-flow-analysis-api-design.md` — see "Decisions made during planning," item 2, for the Sentry API assumption this plan implements.

**Depends on:** Plan 1 (`docs/superpowers/plans/2026-08-17-flow-analysis-api-skeleton.md`) — needs `FlowAnalysisRequest`, `SentryIssue`, `IssueFetcher`, `NoOpIssueFetcher`, and `ConfigureFlowAnalysis.kt` to already exist.

## Global Constraints

- Auth: `Authorization: Bearer $SENTRY_AUTH_TOKEN`, read from the `SENTRY_AUTH_TOKEN` env var.
  No token configured → `IssueFetcher` stays `NoOpIssueFetcher` (no error, no startup failure).
- Endpoint assumption (unverified against live Sentry API docs — flagged in the spec):
  `GET {baseUrl}/api/0/organizations/{org}/events/?query=trace:{traceId}`, one call per trace id.
- Org slug is derived from the DSN host, e.g. `https://key@o123.ingest.sentry.io/456` → `o123`.
  If the DSN can't be parsed as a URI, or has no host, return an empty issue list rather than
  throwing — an issue-fetch failure should not fail the whole flow (the pipeline already treats
  any thrown exception in Task 1's `FlowAnalysisService.runPipeline` as FAILED for the entire flow, which
  is too coarse for "we couldn't reach the issues API").
- Rank by severity level (`fatal` > `error` > `warning` > `info` > anything else), then by event
  count within the same level. Cap at 10.

---

## File Structure

- Modify: `build.gradle.kts` — add `ktor-client-core`, `ktor-client-cio`,
  `ktor-client-content-negotiation` (implementation) and `ktor-client-mock` (testImplementation).
- Create: `src/main/kotlin/buddy/SentryIssuesClient.kt` — the real `IssueFetcher`.
- Modify: `src/main/kotlin/buddy/ConfigureFlowAnalysis.kt` — wire it in behind the env var check.
- Test: `src/test/kotlin/buddy/SentryIssuesClientTest.kt`

---

### Task 1: Add the Ktor HTTP client dependencies

**Files:**
- Modify: `build.gradle.kts`

**Interfaces:** none — dependency setup only.

- [ ] **Step 1: Add the dependencies**

```kotlin
dependencies {
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(ktorLibs.server.config.yaml)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.client.core)
    implementation(ktorLibs.client.cio)
    implementation(ktorLibs.client.contentNegotiation)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.server.testHost)
    testImplementation(ktorLibs.client.mock)
}
```

- [ ] **Step 2: Verify the project still resolves and compiles**

Run: `./gradlew compileKotlin compileTestKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add build.gradle.kts
git commit -m "build: add ktor client dependencies for outbound HTTP calls"
```

---

### Task 2: `SentryIssuesClient`

**Files:**
- Create: `src/main/kotlin/buddy/SentryIssuesClient.kt`
- Test: `src/test/kotlin/buddy/SentryIssuesClientTest.kt`

**Interfaces:**
- Consumes: `FlowAnalysisRequest`, `SentryIssue`, `IssueFetcher` (Plan 1).
- Produces: `class SentryIssuesClient(authToken: String, httpClient: HttpClient = <default CIO client>, baseUrl: String = "https://sentry.io") : IssueFetcher`,
  with internal `organizationSlugFrom(dsn: String): String?` — used by `ConfigureFlowAnalysis.kt` (Task 3).

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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class SentryIssuesClientTest {

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

    @Test
    fun `organizationSlugFrom extracts the org from a standard ingest DSN`() {
        val client = SentryIssuesClient(authToken = "token")

        assertEquals("o123", client.organizationSlugFrom("https://examplekey@o123.ingest.sentry.io/456"))
    }

    @Test
    fun `organizationSlugFrom returns null for an unparseable dsn`() {
        val client = SentryIssuesClient(authToken = "token")

        assertEquals(null, client.organizationSlugFrom("not a uri"))
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
        val client = SentryIssuesClient(authToken = "token", httpClient = httpClient)

        val issues = client.fetchIssues(sampleRequest())

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
        val client = SentryIssuesClient(authToken = "token", httpClient = httpClient)

        val issues = client.fetchIssues(sampleRequest(dsn = "not a uri"))

        assertEquals(emptyList(), issues)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.sentry.buddy.SentryIssuesClientTest"`
Expected: FAIL — compilation error, `SentryIssuesClient.kt` doesn't exist yet.

- [ ] **Step 3: Write the implementation**

```kotlin
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.sentry.buddy.SentryIssuesClientTest"`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/buddy/SentryIssuesClient.kt src/test/kotlin/buddy/SentryIssuesClientTest.kt
git commit -m "feat(flow-analysis): fetch related Sentry issues by trace id"
```

---

### Task 3: Wire it into `ConfigureFlowAnalysis.kt`

**Files:**
- Modify: `src/main/kotlin/buddy/ConfigureFlowAnalysis.kt`

**Interfaces:**
- Consumes: `SentryIssuesClient` (Task 2), `NoOpIssueFetcher` (Plan 1).

- [ ] **Step 1: Read the current `ConfigureFlowAnalysis.kt` and update the `issueFetcher` wiring**

```kotlin
package io.sentry.buddy

import io.ktor.server.application.Application
import java.io.File

fun Application.configureFlowAnalysis(
    flowAnalysisService: FlowAnalysisService = FlowAnalysisService(
        store = FlowAnalysisStore(
            File(environment.config.propertyOrNull("flowAnalysis.dataDir")?.getString() ?: "data/flow-analysis")
        ),
        issueFetcher = System.getenv("SENTRY_AUTH_TOKEN")
            ?.let { token -> SentryIssuesClient(authToken = token) }
            ?: NoOpIssueFetcher,
        titleGenerator = ClaudeCliTitleGenerator()
    )
) {
    flowAnalysisRoutes(flowAnalysisService)
}
```

- [ ] **Step 2: Run the full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, all existing tests still pass (this change doesn't add new automated
coverage — it's exercised via `SentryIssuesClientTest` and manually below).

- [ ] **Step 3: Manually verify with a real token**

```bash
export SENTRY_AUTH_TOKEN=<a real Sentry auth token>
./gradlew run
```

In another terminal, submit a flow whose `dsn` and `trace_ids` correspond to real data in your
Sentry org, then poll it:

```bash
curl -s http://localhost:8080/v1/flow-analysis/<flow-id>
```

Expected: the `issues` array in the response is populated. If the assumed API shape (see Global
Constraints) doesn't match the real response, this is where it'll surface — check the server logs
for the actual JSON returned and adjust `SentryEventDto`/the query parameter accordingly.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/buddy/ConfigureFlowAnalysis.kt
git commit -m "feat(flow-analysis): use SentryIssuesClient when SENTRY_AUTH_TOKEN is configured"
```
