# Flow Analysis — LLM-Powered Recommendations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a second recommendation source that uses the `claude` CLI, grounded in a purpose-built skill document, to analyze the raw flow events + user annotation + related Sentry issues and produce free-form recommendations.

**Architecture:** A skill document at `.claude/skills/flow-analysis/SKILL.md` teaches `claude -p` how to analyze a flow and defines the exact JSON schema it must respond with. `LlmRecommendationSource` implements the existing `RecommendationEngine` interface (from Plan 1) by building a prompt from the `FlowAnalysisRequest` + `List<SentryIssue>`, shelling out to `claude -p`, and parsing the JSON response into `Recommendation`s (assigning fresh UUIDs and `OPEN` status itself — the model never controls those fields). Any failure (process error, malformed JSON) is caught inside the source and degrades to an empty list, so one bad LLM response can't fail the whole flow or blank out the SDK-upgrade recommendation from Plan 3.

**Tech Stack:** JDK `ProcessBuilder` (same pattern as Plan 1's `ClaudeCliTitleGenerator`), kotlinx.serialization for parsing the model's JSON output.

**Spec:** `docs/superpowers/specs/2026-08-17-flow-analysis-api-design.md`, section 5.2.

**Depends on:** Plan 1 (`FlowAnalysisRequest`, `SentryIssue`, `Recommendation`, `Severity`, `RecommendationStatus`,
`RecommendationEngine`) and Plan 3 (`CompositeRecommendationEngine`, and the existing
`SdkUpgradeRecommendationSource` wiring in `ConfigureFlowAnalysis.kt` this plan adds to).

## Global Constraints

- The model must respond with **only** a JSON array matching the schema in the skill doc — no
  markdown fences, no prose. Parsing failures are caught and treated as "no recommendations from
  this source," not a pipeline failure.
- `id` and `status` are never taken from the model's output — `id` is a fresh UUID, `status`
  always starts `OPEN` (the `Recommendation` model default), assigned in Kotlin after parsing.
- The `claude` CLI must be run from a working directory where `.claude/skills/flow-analysis/`
  is discoverable (the repo root — true for `./gradlew run` and for tests that don't invoke the
  real CLI).

---

## File Structure

- Create: `.claude/skills/flow-analysis/SKILL.md` — the skill document `claude -p` uses to analyze
  a flow and the `Recommendation` output schema.
- Create: `src/main/kotlin/buddy/LlmRecommendationSource.kt`
- Modify: `src/main/kotlin/buddy/ConfigureFlowAnalysis.kt` — add `LlmRecommendationSource()` to the
  `CompositeRecommendationEngine`'s source list.
- Test: `src/test/kotlin/buddy/LlmRecommendationSourceTest.kt`

---

### Task 1: Write the `flow-analysis` skill document

**Files:**
- Create: `.claude/skills/flow-analysis/SKILL.md`

**Interfaces:** none — this is a prompt/schema document consumed by `LlmRecommendationSource` (Task 2) at runtime via the `claude` CLI, not by any Kotlin code directly.

- [ ] **Step 1: Write the skill document**

```markdown
---
name: flow-analysis
description: Analyze a recorded user flow (dictated annotation + raw client-side events + related Sentry issues) and produce structured improvement recommendations.
---

# Flow Analysis

You are given a description of a single user flow recording: a short dictated description from
the user, a chronological list of raw client-side events (clicks, scrolls, network requests, db
queries, etc.), and any Sentry issues already found for the flow's trace ids.

## What to look for

- Redundant or duplicate user actions (e.g. the same button tapped multiple times in a short
  window) that suggest a confusing or unresponsive UI.
- Slow or failing network requests visible in the event log.
- Correlation between a user action and a nearby Sentry issue (same time window).
- Anything in the user's own description that describes a problem the events corroborate.

## Output format

Respond with **only** a JSON array (no markdown code fences, no prose before or after) of objects
matching this schema:

```json
[
  {
    "title": "string, short imperative summary, max 12 words",
    "description": "string, 1-3 sentences explaining the issue and the suggested fix",
    "link": "string or null, a URL if directly relevant (e.g. a docs page), otherwise null",
    "severity": "LOW | MEDIUM | HIGH",
    "resolvable": true
  }
]
```

- Return an empty array `[]` if you find nothing worth flagging — do not invent recommendations
  to fill space.
- Do not include `id` or `status` fields in your output — those are assigned by the calling system.
```

- [ ] **Step 2: Manually sanity-check the skill loads**

Run, from the repo root, with `claude` on `PATH`:

```bash
claude -p "Use the flow-analysis skill to analyze this flow. User annotation: tapped checkout twice, nothing happened. Events: click at t=100, click at t=250. Related Sentry issues: none."
```

Expected: a JSON array is printed (possibly empty `[]`, possibly one entry about the duplicate
tap), with no surrounding prose.

- [ ] **Step 3: Commit**

```bash
git add .claude/skills/flow-analysis/SKILL.md
git commit -m "docs: add flow-analysis skill for LLM-powered recommendations"
```

---

### Task 2: `LlmRecommendationSource`

**Files:**
- Create: `src/main/kotlin/buddy/LlmRecommendationSource.kt`
- Test: `src/test/kotlin/buddy/LlmRecommendationSourceTest.kt`

**Interfaces:**
- Consumes: `FlowAnalysisRequest`, `SentryIssue`, `Recommendation`, `Severity`, `RecommendationEngine`
  (Plan 1), the skill document (Task 1, referenced by name in the prompt).
- Produces: `internal fun parseRecommendations(output: String, json: Json): List<Recommendation>`,
  `class LlmRecommendationSource(json: Json = <default>, runClaude: (String) -> String = ::runClaudeCli) : RecommendationEngine` —
  used by `ConfigureFlowAnalysis.kt` (Task 3).

`runClaude` is constructor-injected (defaulting to the real `claude -p` process call) specifically
so tests can simulate the CLI failing without needing the real binary — same reasoning as Plan 1's
`TitleGenerator` interface.

- [ ] **Step 1: Write the failing tests**

```kotlin
package io.sentry.buddy

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LlmRecommendationSourceTest {

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
    fun `parseRecommendations returns an empty list for an empty array`() {
        assertEquals(emptyList(), parseRecommendations("[]", json))
    }

    @Test
    fun `generateRecommendations returns the parsed recommendations on success`() = runBlocking {
        val source = LlmRecommendationSource(
            json = json,
            runClaude = { """[{"title": "T", "description": "D", "severity": "LOW"}]""" }
        )

        val recommendations = source.generateRecommendations(sampleRequest(), emptyList())

        assertEquals(1, recommendations.size)
        assertEquals("T", recommendations.single().title)
    }

    @Test
    fun `generateRecommendations returns an empty list when the claude process fails`() = runBlocking {
        val source = LlmRecommendationSource(
            json = json,
            runClaude = { throw IllegalStateException("claude not installed") }
        )

        val recommendations = source.generateRecommendations(sampleRequest(), emptyList())

        assertEquals(emptyList(), recommendations)
    }

    @Test
    fun `generateRecommendations returns an empty list when the output is not valid JSON`() = runBlocking {
        val source = LlmRecommendationSource(
            json = json,
            runClaude = { "Sure, here are some recommendations: not json" }
        )

        val recommendations = source.generateRecommendations(sampleRequest(), emptyList())

        assertEquals(emptyList(), recommendations)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.sentry.buddy.LlmRecommendationSourceTest"`
Expected: FAIL — compilation error, `LlmRecommendationSource.kt` doesn't exist yet.

- [ ] **Step 3: Write the implementation**

```kotlin
package io.sentry.buddy

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
private data class LlmRecommendationDto(
    val title: String,
    val description: String,
    val link: String? = null,
    val severity: Severity = Severity.MEDIUM,
    val resolvable: Boolean = true
)

internal fun parseRecommendations(output: String, json: Json): List<Recommendation> {
    val dtos = json.decodeFromString(ListSerializer(LlmRecommendationDto.serializer()), output)
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

internal fun runClaudeCli(prompt: String): String {
    val process = ProcessBuilder("claude", "-p", prompt).start()
    val output = process.inputStream.bufferedReader().readText().trim()
    val exitCode = process.waitFor()
    if (exitCode != 0) {
        val stderr = process.errorStream.bufferedReader().readText().trim()
        throw IllegalStateException("claude -p exited with code $exitCode: $stderr")
    }
    return output
}

class LlmRecommendationSource(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val runClaude: (String) -> String = ::runClaudeCli
) : RecommendationEngine {

    override suspend fun generateRecommendations(
        request: FlowAnalysisRequest,
        issues: List<SentryIssue>
    ): List<Recommendation> = try {
        parseRecommendations(runClaude(buildPrompt(request, issues)), json)
    } catch (e: Exception) {
        emptyList()
    }

    private fun buildPrompt(request: FlowAnalysisRequest, issues: List<SentryIssue>): String = buildString {
        appendLine("Use the flow-analysis skill to analyze the following flow and return recommendations.")
        appendLine()
        appendLine("User annotation: ${request.userAnnotation}")
        appendLine("SDK: ${request.sdk}")
        appendLine("Events (${request.events.size}):")
        request.events.forEach { appendLine("- [${it.timestamp}] ${it.type}: ${it.data}") }
        appendLine("Related Sentry issues (${issues.size}):")
        issues.forEach { appendLine("- ${it.title} (${it.level}, count=${it.count}): ${it.permalink}") }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.sentry.buddy.LlmRecommendationSourceTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/buddy/LlmRecommendationSource.kt src/test/kotlin/buddy/LlmRecommendationSourceTest.kt
git commit -m "feat(flow-analysis): add LLM-powered recommendation source"
```

---

### Task 3: Wire it into `ConfigureFlowAnalysis.kt`

**Files:**
- Modify: `src/main/kotlin/buddy/ConfigureFlowAnalysis.kt`

**Interfaces:**
- Consumes: `LlmRecommendationSource` (Task 2), `CompositeRecommendationEngine`,
  `SdkUpgradeRecommendationSource` (Plan 3).

- [ ] **Step 1: Add `LlmRecommendationSource()` to the composite's source list**

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
        recommendationEngine = CompositeRecommendationEngine(
            listOf(SdkUpgradeRecommendationSource(), LlmRecommendationSource())
        ),
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

Expected: once `status` is `COMPLETED`, `recommendations` includes both the SDK-upgrade entry (if
the SDK version is outdated) and an LLM-generated entry about the double-tap, each with a distinct
`id` and `status: "OPEN"`.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/buddy/ConfigureFlowAnalysis.kt
git commit -m "feat(flow-analysis): wire the LLM-powered recommendation source into the pipeline"
```
