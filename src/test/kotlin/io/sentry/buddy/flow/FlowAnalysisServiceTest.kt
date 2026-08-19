package io.sentry.buddy.flow

import io.sentry.buddy.ActionStatus
import io.sentry.buddy.AnalysisStatus
import io.sentry.buddy.FlowAction
import io.sentry.buddy.FlowAnalysisEvent
import io.sentry.buddy.FlowAnalysisRequest
import io.sentry.buddy.FlowAnalysisResponse
import io.sentry.buddy.Recommendation
import io.sentry.buddy.RecommendationAction
import io.sentry.buddy.RecommendationStatus
import io.sentry.buddy.endpoints.flow.FlowAnalysisService
import io.sentry.buddy.endpoints.flow.FlowAnalysisStore
import io.sentry.buddy.endpoints.flow.DismissOutcome
import io.sentry.buddy.endpoints.flow.ExecuteActionOutcome
import io.sentry.buddy.endpoints.flow.ExecuteFlowActionOutcome
import io.sentry.buddy.enrichment.Enrichment
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import io.sentry.buddy.seer.SeerClient
import io.sentry.buddy.seer.seerJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicInteger
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

    private val startRequestCount = AtomicInteger()

    private fun seerClientThatResponds(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        delayMs: Long = 0L
    ) = SeerClient(
        authToken = "token",
        org = "sentry-sdks",
        projectId = "5428559",
        httpClient = HttpClient(
            MockEngine { _ ->
                startRequestCount.incrementAndGet()
                if (delayMs > 0) delay(delayMs)
                respond(
                    content = ByteReadChannel(body),
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            }
        ) { install(ContentNegotiation) { json(seerJson) } },
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
        assertEquals(
            listOf("generate-dashboard", "generate-monitors", "share-recording-json"),
            result.actions.map { it.id }
        )
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
    fun `get backfills default actions on an old completed result`() {
        val store = FlowAnalysisStore(createTempDirectory("flow-service-old-result").toFile())
        store.saveResult(FlowAnalysisResponse(flowId = "flow-1", status = AnalysisStatus.COMPLETED))
        val service = newService(store = store)

        val result = service.get("flow-1")

        assertNotNull(result)
        assertEquals(
            listOf("generate-dashboard", "generate-monitors", "share-recording-json"),
            result.actions.map { it.id }
        )
        assertEquals(result.actions, store.loadResult("flow-1")!!.actions)
    }

    @Test
    fun `resubmitting backfills default actions on an old completed result`() {
        val store = FlowAnalysisStore(createTempDirectory("flow-service-old-resubmit").toFile())
        store.saveResult(FlowAnalysisResponse(flowId = "flow-1", status = AnalysisStatus.COMPLETED))
        val service = newService(store = store)

        val result = service.submitOrGetExisting(sampleRequest())

        assertEquals(
            listOf("generate-dashboard", "generate-monitors", "share-recording-json"),
            result.actions.map { it.id }
        )
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

    private fun recommendationWithOneAction(
        recommendationId: String = "rec-1",
        actionId: String = "act-1"
    ) = Recommendation(
        id = recommendationId,
        title = "T",
        description = "D",
        actions = listOf(
            RecommendationAction(id = actionId, actionLabel = "Open a PR", description = "Do it.")
        )
    )

    private fun flowActions() = listOf(
        FlowAction(
            id = "generate-dashboard",
            actionLabel = "Dashboard",
            actionableForSeer = true,
            description = "Draft a dashboard."
        ),
        FlowAction(
            id = "generate-monitors",
            actionLabel = "Monitors",
            actionableForSeer = true,
            description = "Draft monitors."
        ),
        FlowAction(
            id = "share-recording-json",
            actionLabel = "Share JSON",
            description = "Share the JSON."
        )
    )

    private fun storeWith(
        name: String,
        recommendations: List<Recommendation>,
        actions: List<FlowAction> = emptyList(),
        withRequest: Boolean = true
    ): FlowAnalysisStore {
        val store = FlowAnalysisStore(createTempDirectory(name).toFile())
        if (withRequest) store.saveRequest(sampleRequest())
        store.saveResult(
            FlowAnalysisResponse(
                flowId = "flow-1",
                status = AnalysisStatus.COMPLETED,
                recommendations = recommendations,
                actions = actions
            )
        )
        return store
    }

    private fun FlowAnalysisStore.storedAction(recommendationId: String = "rec-1", actionId: String = "act-1") =
        loadResult("flow-1")!!.recommendations.single { it.id == recommendationId }.actions.single { it.id == actionId }

    private fun FlowAnalysisStore.storedFlowAction(actionId: String = "generate-dashboard") =
        loadResult("flow-1")!!.actions.single { it.id == actionId }

    @Test
    fun `executeAction marks the action EXECUTED`() = runBlocking {
        val store = storeWith("flow-execute", listOf(recommendationWithOneAction()))
        val service = newService(store = store)

        val outcome = service.executeAction("flow-1", "rec-1", "act-1")

        assertTrue(outcome is ExecuteActionOutcome.Success)
        assertEquals(ActionStatus.EXECUTED, outcome.action.status)
        assertNull(outcome.action.seerRunUrl, "without a Seer client there is no run url")
        assertEquals(ActionStatus.EXECUTED, store.storedAction().status)
    }

    @Test
    fun `executeFlowAction marks the action EXECUTED`() = runBlocking {
        val store = storeWith("flow-execute-flow-action", emptyList(), actions = flowActions())
        val service = newService(store = store)

        val outcome = service.executeFlowAction("flow-1", "generate-dashboard")

        assertTrue(outcome is ExecuteFlowActionOutcome.Success)
        assertEquals(ActionStatus.EXECUTED, outcome.action.status)
        assertNull(outcome.action.seerRunUrl, "without a Seer client there is no run url")
        assertEquals(ActionStatus.EXECUTED, store.storedFlowAction().status)
        assertEquals(ActionStatus.OPEN, store.storedFlowAction("generate-monitors").status)
    }

    @Test
    fun `executeFlowAction backfills old completed results before executing`() = runBlocking {
        val store = FlowAnalysisStore(createTempDirectory("flow-execute-flow-action-old-result").toFile())
        store.saveResult(FlowAnalysisResponse(flowId = "flow-1", status = AnalysisStatus.COMPLETED))
        val service = newService(store = store)

        val outcome = service.executeFlowAction("flow-1", "generate-dashboard")

        assertTrue(outcome is ExecuteFlowActionOutcome.Success)
        assertEquals(ActionStatus.EXECUTED, outcome.action.status)
        assertEquals(ActionStatus.EXECUTED, store.storedFlowAction().status)
    }

    @Test
    fun `executeFlowAction starts a Seer run and stores the run url`() = runBlocking {
        val store = storeWith("flow-execute-flow-action-seer", emptyList(), actions = flowActions())
        val service = newService(
            store = store,
            seerClient = seerClientThatResponds("{\"run_id\": 77, \"sentry_run_id\": \"1ebfee71-uuid\"}")
        )

        val outcome = service.executeFlowAction("flow-1", "generate-dashboard")

        assertTrue(outcome is ExecuteFlowActionOutcome.Success)
        assertEquals(
            "https://sentry-sdks.sentry.io/issues/?project=5428559&statsPeriod=10m&explorerRunId=1ebfee71-uuid",
            outcome.action.seerRunUrl
        )
        assertEquals(ActionStatus.EXECUTED, outcome.action.status)
        assertEquals(outcome.action.seerRunUrl, store.storedFlowAction().seerRunUrl)
    }

    @Test
    fun `executing a flow action twice keeps the first run url and starts no second run`() = runBlocking {
        val store = storeWith("flow-execute-flow-action-twice", emptyList(), actions = flowActions())
        val service = newService(
            store = store,
            seerClient = seerClientThatResponds("{\"run_id\": 77, \"sentry_run_id\": \"1ebfee71-uuid\"}")
        )

        val first = service.executeFlowAction("flow-1", "generate-dashboard")
        val second = service.executeFlowAction("flow-1", "generate-dashboard")

        assertTrue(first is ExecuteFlowActionOutcome.Success)
        assertTrue(second is ExecuteFlowActionOutcome.Success)
        assertEquals(first.action.seerRunUrl, second.action.seerRunUrl)
        assertEquals(1, startRequestCount.get(), "the second execute must not start a second run")
    }

    @Test
    fun `executeFlowAction leaves the action OPEN when the Seer run cannot start`() = runBlocking {
        val store = storeWith("flow-execute-flow-action-seer-fail", emptyList(), actions = flowActions())
        val service = newService(
            store = store,
            seerClient = seerClientThatResponds("""{"detail": "no access"}""", HttpStatusCode.Forbidden)
        )

        val outcome = service.executeFlowAction("flow-1", "generate-dashboard")

        assertTrue(outcome is ExecuteFlowActionOutcome.SeerStartFailed)
        assertEquals(ActionStatus.OPEN, store.storedFlowAction().status)
        assertNull(store.storedFlowAction().seerRunUrl)
    }

    @Test
    fun `executeFlowAction fails when the Seer client has no stored request for the flow`() = runBlocking {
        val store = storeWith(
            "flow-execute-flow-action-no-request",
            emptyList(),
            actions = flowActions(),
            withRequest = false
        )
        val service = newService(
            store = store,
            seerClient = seerClientThatResponds("{\"run_id\": 77, \"sentry_run_id\": \"uuid\"}")
        )

        val outcome = service.executeFlowAction("flow-1", "generate-dashboard")

        assertTrue(outcome is ExecuteFlowActionOutcome.SeerStartFailed)
        assertTrue(outcome.message.contains("no stored request"))
        assertEquals(0, startRequestCount.get(), "no run is started without the flow data")
        assertEquals(ActionStatus.OPEN, store.storedFlowAction().status)
    }

    @Test
    fun `executeFlowAction returns ActionNotFound for an unknown action id`() = runBlocking {
        val service = newService(
            store = storeWith("flow-execute-flow-action-unknown", emptyList(), actions = flowActions())
        )

        assertEquals(ExecuteFlowActionOutcome.ActionNotFound, service.executeFlowAction("flow-1", "unknown"))
    }

    @Test
    fun `executeFlowAction returns ActionNotExecutable for a client action`() = runBlocking {
        val service = newService(
            store = storeWith("flow-execute-flow-action-client", emptyList(), actions = flowActions())
        )

        assertEquals(
            ExecuteFlowActionOutcome.ActionNotExecutable,
            service.executeFlowAction("flow-1", "share-recording-json")
        )
    }

    @Test
    fun `executeFlowAction returns FlowAnalysisNotFound for an unknown flow`() = runBlocking {
        val service = newService()

        assertEquals(
            ExecuteFlowActionOutcome.FlowAnalysisNotFound,
            service.executeFlowAction("unknown", "generate-dashboard")
        )
    }

    @Test
    fun `executeAction leaves the other actions of the recommendation OPEN`() = runBlocking {
        val store = storeWith(
            "flow-execute-one-of-two",
            listOf(
                recommendationWithOneAction().let {
                    it.copy(
                        actions = it.actions + RecommendationAction(
                            id = "act-2",
                            actionLabel = "Open dashboard",
                            description = "Look at it."
                        )
                    )
                }
            )
        )
        val service = newService(store = store)

        service.executeAction("flow-1", "rec-1", "act-1")

        assertEquals(ActionStatus.EXECUTED, store.storedAction(actionId = "act-1").status)
        assertEquals(ActionStatus.OPEN, store.storedAction(actionId = "act-2").status)
    }

    @Test
    fun `executeAction starts a Seer run and stores the run url`() = runBlocking {
        val store = storeWith("flow-execute-seer", listOf(recommendationWithOneAction()))
        val service = newService(
            store = store,
            seerClient = seerClientThatResponds("""{"run_id": 77, "sentry_run_id": "1ebfee71-uuid"}""")
        )

        val outcome = service.executeAction("flow-1", "rec-1", "act-1")

        assertTrue(outcome is ExecuteActionOutcome.Success)
        assertEquals(
            "https://sentry-sdks.sentry.io/issues/?project=5428559&statsPeriod=10m&explorerRunId=1ebfee71-uuid",
            outcome.action.seerRunUrl
        )
        assertEquals(ActionStatus.EXECUTED, outcome.action.status)
        assertEquals(outcome.action.seerRunUrl, store.storedAction().seerRunUrl)
    }

    @Test
    fun `executeAction leaves the action OPEN when the Seer run cannot start`() = runBlocking {
        val store = storeWith("flow-execute-seer-fail", listOf(recommendationWithOneAction()))
        val service = newService(
            store = store,
            seerClient = seerClientThatResponds("""{"detail": "no access"}""", HttpStatusCode.Forbidden)
        )

        val outcome = service.executeAction("flow-1", "rec-1", "act-1")

        assertTrue(outcome is ExecuteActionOutcome.SeerStartFailed)
        assertEquals(ActionStatus.OPEN, store.storedAction().status)
        assertNull(store.storedAction().seerRunUrl)
    }

    @Test
    fun `executing twice keeps the first run url and starts no second run`() = runBlocking {
        val store = storeWith("flow-execute-twice", listOf(recommendationWithOneAction()))
        val service = newService(
            store = store,
            seerClient = seerClientThatResponds("""{"run_id": 77, "sentry_run_id": "1ebfee71-uuid"}""")
        )

        val first = service.executeAction("flow-1", "rec-1", "act-1")
        val second = service.executeAction("flow-1", "rec-1", "act-1")

        assertTrue(first is ExecuteActionOutcome.Success)
        assertTrue(second is ExecuteActionOutcome.Success)
        assertEquals(first.action.seerRunUrl, second.action.seerRunUrl)
        assertEquals(1, startRequestCount.get(), "the second execute must not start a second run")
    }

    @Test
    fun `two concurrent executes of one flow both persist`() = runBlocking {
        val store = storeWith(
            "flow-execute-concurrent",
            listOf(
                recommendationWithOneAction(recommendationId = "rec-1", actionId = "act-1"),
                recommendationWithOneAction(recommendationId = "rec-2", actionId = "act-2")
            )
        )
        val service = newService(
            store = store,
            // The delay puts the network round-trip of one execute inside the other's load-save gap.
            seerClient = seerClientThatResponds("""{"run_id": 77, "sentry_run_id": "1ebfee71-uuid"}""", delayMs = 50)
        )

        listOf(
            async { service.executeAction("flow-1", "rec-1", "act-1") },
            async { service.executeAction("flow-1", "rec-2", "act-2") }
        ).awaitAll()

        assertEquals(
            listOf(ActionStatus.EXECUTED, ActionStatus.EXECUTED),
            store.loadResult("flow-1")!!.recommendations.map { it.actions.single().status },
            "neither execute may erase the other"
        )
    }

    @Test
    fun `executeAction fails when the Seer client has no stored request for the flow`() = runBlocking {
        val store = storeWith(
            "flow-execute-no-request",
            listOf(recommendationWithOneAction()),
            withRequest = false
        )
        val service = newService(
            store = store,
            seerClient = seerClientThatResponds("""{"run_id": 77, "sentry_run_id": "uuid"}""")
        )

        val outcome = service.executeAction("flow-1", "rec-1", "act-1")

        assertTrue(outcome is ExecuteActionOutcome.SeerStartFailed)
        assertTrue(outcome.message.contains("no stored request"))
        assertEquals(0, startRequestCount.get(), "no run is started without the flow data")
        assertEquals(ActionStatus.OPEN, store.storedAction().status)
    }

    @Test
    fun `executeAction returns ActionNotFound for an unknown action id`() = runBlocking {
        val service = newService(store = storeWith("flow-execute-unknown-action", listOf(recommendationWithOneAction())))

        assertEquals(
            ExecuteActionOutcome.ActionNotFound,
            service.executeAction("flow-1", "rec-1", "unknown")
        )
    }

    @Test
    fun `executeAction returns RecommendationDismissed for a dismissed recommendation`() = runBlocking {
        val store = storeWith("flow-execute-dismissed", listOf(recommendationWithOneAction()))
        val service = newService(store = store)
        service.dismissRecommendation("flow-1", "rec-1")

        assertEquals(
            ExecuteActionOutcome.RecommendationDismissed,
            service.executeAction("flow-1", "rec-1", "act-1")
        )
        assertEquals(ActionStatus.OPEN, store.storedAction().status)
    }

    @Test
    fun `executeAction returns FlowAnalysisNotFound for an unknown flow`() = runBlocking {
        val service = newService()

        assertEquals(
            ExecuteActionOutcome.FlowAnalysisNotFound,
            service.executeAction("unknown", "rec-1", "act-1")
        )
    }

    @Test
    fun `executeAction returns RecommendationNotFound for an unknown recommendation id`() = runBlocking {
        val service = newService(store = storeWith("flow-execute-unknown-rec", emptyList()))

        assertEquals(
            ExecuteActionOutcome.RecommendationNotFound,
            service.executeAction("flow-1", "unknown", "act-1")
        )
    }

    @Test
    fun `dismissRecommendation marks the recommendation DISMISSED and keeps its actions`() = runBlocking {
        val store = storeWith("flow-dismiss", listOf(recommendationWithOneAction()))
        val service = newService(store = store)

        val outcome = service.dismissRecommendation("flow-1", "rec-1")

        assertTrue(outcome is DismissOutcome.Success)
        assertEquals(RecommendationStatus.DISMISSED, outcome.recommendation.status)
        val stored = store.loadResult("flow-1")!!.recommendations.single()
        assertEquals(RecommendationStatus.DISMISSED, stored.status)
        assertEquals(1, stored.actions.size)
    }

    @Test
    fun `dismissRecommendation starts no Seer run`() = runBlocking {
        val store = storeWith("flow-dismiss-no-run", listOf(recommendationWithOneAction()))
        val service = newService(
            store = store,
            seerClient = seerClientThatResponds("""{"run_id": 77, "sentry_run_id": "uuid"}""")
        )

        service.dismissRecommendation("flow-1", "rec-1")

        assertEquals(0, startRequestCount.get(), "a dismiss is a local state change only")
    }

    @Test
    fun `dismissRecommendation returns FlowAnalysisNotFound for an unknown flow`() = runBlocking {
        val service = newService()

        assertEquals(DismissOutcome.FlowAnalysisNotFound, service.dismissRecommendation("unknown", "rec-1"))
    }

    @Test
    fun `dismissRecommendation returns RecommendationNotFound for an unknown recommendation id`() = runBlocking {
        val service = newService(store = storeWith("flow-dismiss-unknown", emptyList()))

        assertEquals(DismissOutcome.RecommendationNotFound, service.dismissRecommendation("flow-1", "unknown"))
    }
}
