package io.sentry.buddy.seer

import io.sentry.buddy.FlowAnalysisEvent
import io.sentry.buddy.FlowAnalysisRequest
import io.sentry.buddy.Recommendation
import io.sentry.buddy.SentryIssue
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertTrue

class SeerPromptsTest {

    private fun request(eventCount: Int = 1) = FlowAnalysisRequest(
        flowId = "flow-1",
        traceIds = listOf("trace-1"),
        startTimeMs = 1000L,
        endTimeMs = 2000L,
        dsn = "https://key@o123.ingest.sentry.io/456",
        userAnnotation = "tapped checkout twice",
        sdk = "io.sentry.android@8.40.0",
        events = (1..eventCount).map {
            FlowAnalysisEvent(type = "click", timestamp = 1000L + it, data = JsonObject(emptyMap()))
        }
    )

    private val issue = SentryIssue(
        id = "g1",
        title = "NPE in checkout",
        count = 3,
        level = "error",
        permalink = "https://sentry.io/g1"
    )

    private val recommendation = Recommendation(
        id = "rec-1",
        title = "Debounce the checkout button",
        description = "It was tapped twice within 200ms."
    )

    @Test
    fun `analysis carries the instructions and the flow context`() {
        val prompt = SeerPrompts.analysis(request(), listOf(issue))

        assertTrue(
            prompt.contains("Analyze the flow and make improvement recommendations."),
            "the analysis instructions are missing"
        )
        assertTrue(prompt.contains("tapped checkout twice"))
        assertTrue(prompt.contains("io.sentry.android@8.40.0"))
        assertTrue(prompt.contains("NPE in checkout"))
    }

    @Test
    fun `implement carries the recommendation and the flow context`() {
        val prompt = SeerPrompts.implement(request(), listOf(issue), recommendation)

        assertTrue(prompt.contains("Debounce the checkout button"))
        assertTrue(prompt.contains("It was tapped twice within 200ms."))
        assertTrue(prompt.contains("tapped checkout twice"), "the flow context is missing")
        assertTrue(prompt.contains("NPE in checkout"))
    }

    @Test
    fun `the flow context caps the number of events and says how many are left out`() {
        val prompt = SeerPrompts.analysis(request(eventCount = 250), emptyList())

        assertTrue(prompt.contains("Events (250):"))
        assertTrue(prompt.contains("50 more events not shown"))
    }
}
