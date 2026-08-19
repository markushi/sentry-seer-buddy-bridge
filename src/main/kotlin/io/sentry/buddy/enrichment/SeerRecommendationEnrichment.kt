package io.sentry.buddy.enrichment

import io.sentry.buddy.FlowAnalysisRequest
import io.sentry.buddy.FlowAnalysisResponse
import io.sentry.buddy.Recommendation
import io.sentry.buddy.RecommendationAction
import io.sentry.buddy.Severity
import io.sentry.buddy.seer.SeerClient
import io.sentry.buddy.seer.SeerPrompts
import io.sentry.buddy.seer.seerJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import org.slf4j.LoggerFactory
import java.util.UUID

private val logger = LoggerFactory.getLogger(SeerRecommendationEnrichment::class.java)

@Serializable
private data class SeerRecommendationDto(
    val title: String,
    val description: String,
    val link: String? = null,
    val severity: String? = null,
    /**
     * Kept as raw elements, so that one action the model wrote without a description does not
     * discard the recommendation it belongs to.
     */
    val actions: List<JsonElement> = emptyList()
)

@Serializable
private data class SeerActionDto(
    val label: String,
    val description: String,
    val link: String? = null
)

/** A model writes `high` as readily as `HIGH`, and sometimes a word that is neither. */
private fun severityOf(raw: String?): Severity =
    Severity.entries.find { it.name.equals(raw?.trim(), ignoreCase = true) } ?: Severity.MEDIUM

private val fencedJsonArray = Regex("```(?:json)?\\s*(\\[[\\s\\S]*?])\\s*```", RegexOption.IGNORE_CASE)

/** Takes the JSON array out of an answer that can have fences or text around it. */
internal fun extractJsonArray(output: String): String {
    fencedJsonArray.find(output)?.let { return it.groupValues[1] }
    val start = output.indexOf('[')
    val end = output.lastIndexOf(']')
    if (start < 0 || end <= start) throw IllegalStateException("No JSON array in the model answer")
    return output.substring(start, end + 1)
}

private fun parseActions(elements: List<JsonElement>, json: Json): List<RecommendationAction> =
    elements.mapNotNull { element ->
        val dto = try {
            json.decodeFromJsonElement(SeerActionDto.serializer(), element)
        } catch (e: Exception) {
            logger.warn("Skipped an action of the Seer answer that could not be decoded", e)
            return@mapNotNull null
        }
        RecommendationAction(
            id = UUID.randomUUID().toString(),
            actionLabel = dto.label,
            description = dto.description,
            link = dto.link
        )
    }

/**
 * Decodes each element on its own, so that one bad element (a severity the enum does not know, a
 * missing title) does not discard the whole answer. Only a completely unusable answer throws.
 */
internal fun parseRecommendations(output: String, json: Json): List<Recommendation> {
    val elements = try {
        json.parseToJsonElement(extractJsonArray(output)).jsonArray
    } catch (e: Exception) {
        throw IllegalStateException("Could not parse the recommendations from the Seer answer", e)
    }

    val recommendations = elements.mapNotNull { element ->
        val dto = try {
            json.decodeFromJsonElement(SeerRecommendationDto.serializer(), element)
        } catch (e: Exception) {
            logger.warn("Skipped a recommendation of the Seer answer that could not be decoded", e)
            return@mapNotNull null
        }
        Recommendation(
            id = UUID.randomUUID().toString(),
            title = dto.title,
            description = dto.description,
            link = dto.link,
            severity = severityOf(dto.severity),
            actions = parseActions(dto.actions, json)
        )
    }

    if (elements.isNotEmpty() && recommendations.isEmpty()) {
        throw IllegalStateException("No element of the Seer recommendation array could be decoded")
    }
    return recommendations
}

class SeerRecommendationEnrichment(
    private val seerClient: SeerClient,
    private val json: Json = seerJson
) : Enrichment {

    override suspend fun enrich(request: FlowAnalysisRequest, response: FlowAnalysisResponse): FlowAnalysisResponse {
        val run = seerClient.startRun(SeerPrompts.analysis(request, response.issues))
        val recommendations = parseRecommendations(seerClient.awaitAnswer(run.runId), json)

        return response.copy(recommendations = response.recommendations + recommendations)
    }
}
