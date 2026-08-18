package io.sentry.buddy.seer

import io.sentry.buddy.FlowAnalysisRequest
import io.sentry.buddy.Recommendation
import io.sentry.buddy.SentryIssue

private const val MAX_EVENTS_IN_PROMPT = 200

/** Builds the prompts that go into the `query` field of a Seer explorer run. */
object SeerPrompts {

    fun analysis(request: FlowAnalysisRequest, issues: List<SentryIssue>): String = buildString {
        appendLine(resource("/flow-analysis-prompt.md"))
        appendLine()
        append(flowContext(request, issues))
    }

    fun implement(
        request: FlowAnalysisRequest,
        issues: List<SentryIssue>,
        recommendation: Recommendation
    ): String = buildString {
        appendLine(resource("/flow-implement-prompt.md"))
        appendLine()
        appendLine("## Recommendation to implement")
        appendLine()
        appendLine("Title: ${recommendation.title}")
        appendLine("Description: ${recommendation.description}")
        recommendation.link?.let { appendLine("Link: $it") }
        appendLine("Severity: ${recommendation.severity}")
        appendLine()
        append(flowContext(request, issues))
    }

    private fun flowContext(request: FlowAnalysisRequest, issues: List<SentryIssue>): String = buildString {
        appendLine("## Flow data")
        appendLine()
        appendLine("User annotation: ${request.userAnnotation}")
        appendLine("SDK: ${request.sdk}")
        appendLine("Events (${request.events.size}):")
        request.events.take(MAX_EVENTS_IN_PROMPT).forEach { appendLine("- [${it.timestamp}] ${it.type}: ${it.data}") }
        if (request.events.size > MAX_EVENTS_IN_PROMPT) {
            appendLine("- ... ${request.events.size - MAX_EVENTS_IN_PROMPT} more events not shown")
        }
        appendLine("Related Sentry issues (${issues.size}):")
        issues.forEach { appendLine("- ${it.title} (${it.level}, count=${it.count}): ${it.permalink}") }
    }

    private fun resource(path: String): String =
        SeerPrompts::class.java.getResource(path)?.readText()
            ?: throw IllegalStateException("$path is not on the classpath")
}
