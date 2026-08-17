package io.sentry.buddy

fun interface TitleGenerator {
    suspend fun generateTitle(request: FlowAnalysisRequest): String
}

class ClaudeCliTitleGenerator : TitleGenerator {

    override suspend fun generateTitle(request: FlowAnalysisRequest): String {
        val prompt = buildString {
            appendLine("In one short sentence (max 12 words), summarize what happened in this user")
            appendLine("session, based on the user's own description and the raw event log. Respond")
            appendLine("with only the sentence, no quotes, no preamble.")
            appendLine()
            appendLine("User description: ${request.userAnnotation}")
            appendLine("Event types observed: ${request.events.map { it.type }.distinct().joinToString(", ")}")
        }

        val process = ProcessBuilder("claude", "-p", prompt).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            val stderr = process.errorStream.bufferedReader().readText().trim()
            throw IllegalStateException("claude -p exited with code $exitCode: $stderr")
        }

        return output.ifBlank { "Untitled flow" }
    }
}
