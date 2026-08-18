package io.sentry.buddy.flow

import io.sentry.buddy.AnalysisStatus
import io.sentry.buddy.FlowAnalysisEvent
import io.sentry.buddy.FlowAnalysisRequest
import io.sentry.buddy.FlowAnalysisResponse
import io.sentry.buddy.Recommendation
import io.sentry.buddy.RecommendationStatus
import io.sentry.buddy.endpoints.flow.FlowAnalysisService
import io.sentry.buddy.endpoints.flow.FlowAnalysisStore
import io.sentry.buddy.endpoints.flow.ResolveOutcome
import io.sentry.buddy.enrichment.Enrichment
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import io.sentry.buddy.seer.SeerClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlowAnalysisServiceTest {

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
    fun `a failing enrichment is recorded as an enrichment error but does not fail the flow`() {
        val service = newService(
            enrichments = listOf(
                Enrichment { _, _ -> throw IllegalStateException("boom") },
                Enrichment { _, response -> response.copy(title = "Recovered") }
            )
        )

        service.submitOrGetExisting(sampleRequest())

        val result = service.get("flow-1")
        assertNotNull(result)
        assertEquals(AnalysisStatus.COMPLETED, result.status)
        assertEquals("Recovered", result.title)
        assertEquals(1, result.enrichmentErrors.size)
        assertTrue(result.enrichmentErrors.single().contains("boom"))
    }

    @Test
    fun `successful enrichments leave enrichmentErrors empty`() {
        val service = newService()

        service.submitOrGetExisting(sampleRequest())

        val result = service.get("flow-1")
        assertNotNull(result)
        assertEquals(emptyList(), result.enrichmentErrors)
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
}
