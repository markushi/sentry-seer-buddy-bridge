package io.sentry.buddy.enrichment

import io.sentry.buddy.FlowAnalysisRequest
import io.sentry.buddy.FlowAnalysisResponse
import org.slf4j.LoggerFactory

class TitleEnrichment(
    private val titleGenerator: (FlowAnalysisRequest) -> String = ::generateTitleWithClaude
) : Enrichment {

    private val logger = LoggerFactory.getLogger(TitleEnrichment::class.java)

    override suspend fun enrich(request: FlowAnalysisRequest, response: FlowAnalysisResponse): FlowAnalysisResponse {
        val title = try {
            titleGenerator(request).ifBlank { fallbackTitle(request) }
        } catch (e: Exception) {
            logger.warn("Failed to generate flow title via LLM; using fallback title", e)
            fallbackTitle(request)
        }
        return response.copy(title = title)
    }
}

private fun generateTitleWithClaude(request: FlowAnalysisRequest): String {
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

    return output
}

internal fun fallbackTitle(request: FlowAnalysisRequest): String =
    request.userAnnotation.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("Flow:") }
        ?.removePrefix("Flow:")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.take(80)
        ?: request.userAnnotation.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("Flow:") && !it.startsWith("Focus areas:") }
            ?.take(80)
        ?: "Untitled flow"
