package io.sentry.buddy.enrichment

import io.sentry.buddy.FlowAnalysisRequest
import io.sentry.buddy.FlowAnalysisResponse
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.util.concurrent.TimeUnit

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

    return runTitleCommand(claudeTitleCommand(prompt))
}

/**
 * A title is one short sentence, so the run asks for the faster Haiku model instead of the
 * default one. The prompt goes on the command line, never on stdin.
 */
internal fun claudeTitleCommand(prompt: String): List<String> =
    listOf("claude", "--model", "haiku", "-p", prompt)

/**
 * A title is worth a few seconds, never a blocked enrichment pipeline. The measured runs take
 * between 4.5 and 10 seconds, most of it the start of the CLI and not the model, so the limit is
 * far above that: it must catch a hung command, not a merely slow one.
 */
private const val TITLE_TIMEOUT_MS = 30_000L

/**
 * Runs [command] and gives only its stdout. stderr is kept apart, because a tool can write a
 * warning there while it still succeeds, and such a warning must never become the title. stdin is
 * closed at once: the prompt goes on the command line, so a tool that waits for stdin would only
 * wait for nothing and then warn about it. A command that takes longer than [timeoutMs] is killed.
 */
internal fun runTitleCommand(command: List<String>, timeoutMs: Long = TITLE_TIMEOUT_MS): String {
    val process = ProcessBuilder(command).start()
    process.outputStream.close()

    // Both streams are read by their own thread. A full pipe would otherwise stop the command, and
    // reading them here would wait for the end of the command and thus defeat the timeout.
    val output = StringBuilder()
    val errorOutput = StringBuilder()
    val readers = listOf(
        readerThread(process.inputStream, output),
        readerThread(process.errorStream, errorOutput)
    )

    if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
        process.destroyForcibly()
        readers.forEach { it.join() }
        throw IllegalStateException("${command.first()} did not answer in $timeoutMs ms")
    }
    readers.forEach { it.join() }

    val exitCode = process.exitValue()
    if (exitCode != 0) {
        val detail = errorOutput.toString().trim().ifBlank { output.toString().trim() }
        throw IllegalStateException("${command.first()} exited with code $exitCode: $detail")
    }

    return output.toString().trim()
}

private fun readerThread(stream: InputStream, into: StringBuilder): Thread =
    Thread { stream.bufferedReader().forEachLine { into.appendLine(it) } }.also { it.start() }

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
