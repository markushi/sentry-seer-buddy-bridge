package io.sentry.buddy.endpoints.flow

import io.sentry.buddy.FlowAnalysisResponse
import kotlinx.serialization.json.Json

private const val DEMO_RESULT_TEMPLATE = "/demo_result_template.json"

/**
 * Reads the packaged demo result and gives a function that stamps the flow id of the moment onto
 * it. Decoding happens once, at startup, so a broken template fails loudly instead of during a demo.
 */
fun loadDemoResultTemplate(): (String) -> FlowAnalysisResponse {
    val text = checkNotNull(object {}.javaClass.getResourceAsStream(DEMO_RESULT_TEMPLATE)) {
        "$DEMO_RESULT_TEMPLATE is not on the classpath"
    }.bufferedReader().use { it.readText() }
    val template = Json { ignoreUnknownKeys = true }
        .decodeFromString(FlowAnalysisResponse.serializer(), text)

    return { flowId -> template.copy(flowId = flowId) }
}
