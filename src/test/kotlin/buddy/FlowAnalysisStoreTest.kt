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
