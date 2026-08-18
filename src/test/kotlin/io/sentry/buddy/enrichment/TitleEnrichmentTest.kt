package io.sentry.buddy.enrichment

import io.sentry.buddy.AnalysisStatus
import io.sentry.buddy.FlowAnalysisEvent
import io.sentry.buddy.FlowAnalysisRequest
import io.sentry.buddy.FlowAnalysisResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TitleEnrichmentTest {

    private fun sampleRequest(userAnnotation: String = "Flow: Checkout") = FlowAnalysisRequest(
        flowId = "flow-1",
        traceIds = listOf("trace-1"),
        startTimeMs = 1000L,
        endTimeMs = 2000L,
        dsn = "https://key@sentry.io/1",
        userAnnotation = userAnnotation,
        sdk = "io.sentry.android@8.40.0",
        events = listOf(FlowAnalysisEvent(type = "click", timestamp = 1500L, data = JsonObject(emptyMap())))
    )

    private fun emptyResponse() = FlowAnalysisResponse(flowId = "flow-1", status = AnalysisStatus.PROCESSING)

    @Test
    fun `enrich uses generated title`() = runBlocking {
        val enrichment = TitleEnrichment { "Generated title" }

        val enriched = enrichment.enrich(sampleRequest(), emptyResponse())

        assertEquals("Generated title", enriched.title)
    }

    @Test
    fun `enrich falls back when title generation fails`() = runBlocking {
        val enrichment = TitleEnrichment { throw IllegalStateException("claude auth failed") }

        val enriched = enrichment.enrich(sampleRequest("Flow: Checkout availability"), emptyResponse())

        assertEquals("Checkout availability", enriched.title)
        assertEquals(AnalysisStatus.PROCESSING, enriched.status)
    }

    @Test
    fun `enrich falls back to untitled flow when annotation has no title`() = runBlocking {
        val enrichment = TitleEnrichment { "" }

        val enriched = enrichment.enrich(sampleRequest("Flow: \nFocus areas: Network timing"), emptyResponse())

        assertEquals("Untitled flow", enriched.title)
    }

    @Test
    fun `runTitleCommand gives only what the command writes to stdout`() {
        val title = runTitleCommand(listOf("sh", "-c", "echo 'a warning' >&2; echo 'the title'"))

        assertEquals("the title", title)
    }

    @Test
    fun `runTitleCommand does not wait for stdin`() {
        val title = runTitleCommand(listOf("sh", "-c", "cat; echo 'the title'"))

        assertEquals("the title", title)
    }

    @Test
    fun `runTitleCommand throws with the stderr of a command that fails`() {
        val error = assertFailsWith<IllegalStateException> {
            runTitleCommand(listOf("sh", "-c", "echo 'boom' >&2; exit 3"))
        }

        assertTrue(error.message!!.contains("3"), error.message!!)
        assertTrue(error.message!!.contains("boom"), error.message!!)
    }

    @Test
    fun `runTitleCommand stops a command that runs longer than the timeout`() {
        val started = System.currentTimeMillis()

        val error = assertFailsWith<IllegalStateException> {
            runTitleCommand(listOf("sh", "-c", "sleep 60; echo 'too late'"), timeoutMs = 200L)
        }

        assertTrue(error.message!!.contains("200 ms"), error.message!!)
        assertTrue(System.currentTimeMillis() - started < 10_000L, "it waited for the command to end")
    }

    @Test
    fun `claudeTitleCommand asks claude for the faster haiku model`() {
        assertEquals(listOf("claude", "--model", "haiku", "-p", "the prompt"), claudeTitleCommand("the prompt"))
    }
}
