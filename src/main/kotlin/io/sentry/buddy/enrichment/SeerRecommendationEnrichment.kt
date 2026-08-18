package io.sentry.buddy.enrichment

import io.sentry.buddy.FlowAnalysisRequest
import io.sentry.buddy.FlowAnalysisResponse
import io.sentry.buddy.Recommendation
import io.sentry.buddy.Severity
import io.sentry.buddy.seer.SeerClient
import io.sentry.buddy.seer.SeerPrompts
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
private data class SeerRecommendationDto(
    val title: String,
    val description: String,
    val link: String? = null,
    val severity: Severity = Severity.MEDIUM,
    val resolvable: Boolean = true
)

/** Takes the JSON array out of an answer that can have fences or text around it. */
internal fun extractJsonArray(output: String): String {
    val start = output.indexOf('[')
    val end = output.lastIndexOf(']')
    if (start < 0 || end <= start) throw IllegalStateException("No JSON array in the model answer")
    return output.substring(start, end + 1)
}

internal fun parseRecommendations(output: String, json: Json): List<Recommendation> {
    val dtos = try {
        json.decodeFromString(ListSerializer(SeerRecommendationDto.serializer()), extractJsonArray(output))
    } catch (e: Exception) {
        throw IllegalStateException("Could not parse the recommendations from the Seer answer", e)
    }
    return dtos.map {
        Recommendation(
            id = UUID.randomUUID().toString(),
            title = it.title,
            description = it.description,
            link = it.link,
            severity = it.severity,
            resolvable = it.resolvable
        )
    }
}

class SeerRecommendationEnrichment(
    private val seerClient: SeerClient,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : Enrichment {

    override suspend fun enrich(request: FlowAnalysisRequest, response: FlowAnalysisResponse): FlowAnalysisResponse {
        val run = seerClient.startRun(SeerPrompts.analysis(request, response.issues))
        val recommendations = parseRecommendations(seerClient.awaitAnswer(run.runId), json)

        if (recommendations.isEmpty()) return response
        return response.copy(recommendations = response.recommendations + recommendations)
    }
}
