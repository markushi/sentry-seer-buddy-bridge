package io.sentry.buddy.enrichment

import io.sentry.buddy.flow.FlowAnalysisRequest
import io.sentry.buddy.flow.FlowAnalysisResponse

class TitleEnrichment : Enrichment {

    override suspend fun enrich(request: FlowAnalysisRequest, response: FlowAnalysisResponse): FlowAnalysisResponse =
        response.copy(title = generateTitle(request))

    private fun generateTitle(request: FlowAnalysisRequest): String {
        val prompt = buildString {
            appendLine("In one short sentence (max 12 words), summarize what happened in this user")
            appendLine("session, based on the user's own description and the raw event log. Respond")
            appendLine("with only the sentence, no quotes, no preamble.")
            appendLine()
            appendLine("User description: ${request.userAnnotation}")
            appendLine("Event types observed: ${request.events.map { it.type }.distinct().joinToString(", ")}")
        }

        val process = ProcessBuilder("claude", "-p", prompt).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw IllegalStateException("claude -p exited with code $exitCode: $output")
        }

        return output.ifBlank { "Untitled flow" }
    }
}
