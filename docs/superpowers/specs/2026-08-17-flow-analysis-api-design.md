# Flow Analysis API Design (Ktor)

This is a prototype, shortcuts are being made. E.g. the schema is not fully typed,
persitance relies on local files (no database). Processing pipeline has no safety nets.

## 1. Main API endpoints

```
POST   /v1/flow-analysis                                   -> 202 Accepted (status: PENDING)
Pattern: submit → 202 Accepted → poll.

GET    /v1/flow-analysis/{flowId}                          -> current status + result once COMPLETED
POST   /v1/flow-analysis/{flowId}/recommendations/{id}/resolve -> apply / acknowledge a recommendation
```

## 2. Data models

Quite open for now, so it makes moving fast easier.

```kotlin
@Serializable
data class FlowAnalysisEvent(
    val type: String,          // "click" | "scroll" | "network_request" | "db_query" | ...
    val time_ms: Long,         // unix time
    val data: JsonObject       // free-form per event type, validated per-type downstream
)

@Serializable
data class FlowAnalysisRequest(
    @SerialName("flow_id") val flowId: String, // unique id, for later reference
    @SerialName("trace_ids") val traceIds: List<String>, // all traces during the recording
    @SerialName("start_time_ms") val startTimeMs: Long, // unix timestamp
    @SerialName("end_time_ms") val endTimeMs: Long, // unix timestamp
    val dsn: String, // sentry dsn - used to derive org + project
    val user_annotation: String, // user description of the flow (e.g. dictated)
    val sdk_version: String,           // e.g. "io.sentry.android@8.40.0"
    val events: List<FlowAnalysisEvent>
)

enum class AnalysisStatus { PROCESSING, COMPLETED, FAILED }

enum class RecommendationStatus { OPEN, RESOLVED, DISMISSED, FAILED }
enum class Severity { LOW, MEDIUM, HIGH }

@Serializable
data class Recommendation(
    val id: String, // <uuid>
    val title: String,
    val description: String,
    val link: String? = null,
    val severity: Severity = Severity.MEDIUM,
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
    val title: String? = null,          // null until analysis completes
    val recommendations: List<Recommendation> = emptyList(),
    val issues: List<SentryIssue> = emptyList(),
    val error: String? = null
)
```

## 3. Routing

```kotlin
fun Application.flowAnalysisRoutes(flowAnalysisService: FlowAnalysisService) {
    routing {
        route("/v1/flow-analysis") {

            post {
                val request = call.receive<FlowAnalysisRequest>()
                requireValidFlow(request) // size limits, see §6

                // flow_id is the idempotency key — resubmitting the same
                // flow_id returns the existing job instead of double-processing
                val accepted = flowAnalysisService.submitOrGetExisting(request, projectContext(call))
                call.respond(HttpStatusCode.Accepted, accepted)
            }

            get("/{flowId}") {
                val flowId = call.parameters["flowId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest)

                val analysis = flowAnalysisService.get(flowId, projectContext(call))
                    ?: return@get call.respond(HttpStatusCode.NotFound)

                call.respond(analysis)
            }

            post("/{flowId}/recommendations/{recommendationId}/resolve") {
                val flowId = call.parameters["flowId"]!!
                val recommendationId = call.parameters["recommendationId"]!!

                val result = flowAnalysisService.resolveRecommendation(
                    flowId, recommendationId, projectContext(call)
                )
                call.respond(result)
            }
        }
    }
}
```

## 4. Processing pipeline (worker side)

```
1. Persist flow (KISS: event blob to disk for now)
2. Fetch related Sentry issues:
     - the sentry API for any issues linked to the trace_ids
     - rank by event count / level, cap at 10
4. Run the recommendation engine
5. Generate `title` via LLM call, grounded in:
     - the user's dictated annotation ("user_annotation" field)
     - the raw events
     - For now: Simply use `claude -p` to generate the description
6. Persist final result to disk, status = COMPLETED (or FAILED with error message)
```

## 5. Recommendation engine

The recommendation engine has different sources:

1. SDK Upgrade Recommendation
Is done by checking the https://github.com/getsentry/sentry-java/releases for the latest release

2. LLM Powered recommendations

Create a skill document on how to analyze the data, add the recommendation data schema to the skill output. Use `claude -p` to execute the skill, then parse the recommendations and persist them.

`POST /{flowId}/recommendations/{id}/resolve` looks up the recommendation,
sets its status to `RESOLVED`.

## 7. Non-functional stuff worth deciding upfront

- **Idempotency**: `flow_id` as the natural key — resubmitting returns the
  existing job rather than reprocessing.
- **Auth/scoping**: a flow always belongs to one org/project; reuse whatever
  scoping your other ingestion endpoints already use rather than inventing
  a new one.

---

## Decisions made during planning (2026-08-17)

These resolve ambiguities/gaps in the spec above; see the plan doc for how they're applied.

1. **Auth/scoping**: skipped entirely for this prototype. There are no other ingestion
   endpoints in this repo to reuse scoping from, and inventing a new auth scheme would
   violate "keep it minimal, don't build unrequested features." `flowId` is the sole
   lookup key; `dsn` is stored for reference only, not enforced.
2. **Sentry API access** (for fetching related issues by `trace_id`): use env var
   `SENTRY_AUTH_TOKEN`, calling the organization events endpoint
   `/api/0/organizations/{org}/events/` filtered by `trace:{trace_id}`, where `{org}`
   is derived from the DSN. This is an assumption about Sentry's API shape, not yet
   verified against real API docs — flagged for verification when that subsystem is built.
3. **Plan scope**: split into 4 separate plans, one per independent subsystem:
   - Plan 1 (this one): core API + file persistence + async pipeline skeleton, with
     issue-fetching and recommendation-generation stubbed as no-ops, but real LLM-based
     title generation via `claude -p`.
   - Plan 2: real Sentry issue fetching (`SentryIssuesClient`).
   - Plan 3: SDK-upgrade recommendation source (GitHub releases check).
   - Plan 4: LLM-powered recommendation source (skill document + `claude -p` + parsing).
4. **`AnalysisStatus`**: the spec's endpoint table says "202 Accepted (status: PENDING)"
   but the enum only defines `PROCESSING`/`COMPLETED`/`FAILED`. Using `PROCESSING` as the
   initial status, since the enum is the more concrete source of truth.
5. **Resolve endpoint response shape**: the spec's pseudocode passes an ambiguous
   `result` to `call.respond`. Plan 1 returns the full updated `FlowAnalysisResponse`,
   consistent with the GET endpoint's response shape.
