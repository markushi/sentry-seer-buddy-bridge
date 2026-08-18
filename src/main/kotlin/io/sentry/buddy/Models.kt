package io.sentry.buddy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class FlowAnalysisEvent(
    val type: String,
    val timestamp: Long,
    val data: JsonObject
)

@Serializable
data class FlowAnalysisRequest(
    @SerialName("flow_id") val flowId: String,
    @SerialName("trace_ids") val traceIds: List<String>,
    @SerialName("start_time_ms") val startTimeMs: Long,
    @SerialName("end_time_ms") val endTimeMs: Long,
    val dsn: String,
    @SerialName("user_annotation") val userAnnotation: String,
    val sdk: String,
    val events: List<FlowAnalysisEvent>
)

enum class AnalysisStatus { PROCESSING, COMPLETED, FAILED }

enum class RecommendationStatus { OPEN, RESOLVED, DISMISSED, FAILED }

enum class Severity { LOW, MEDIUM, HIGH }

@Serializable
data class Recommendation(
    val id: String,
    val title: String,
    val description: String,
    val link: String? = null,
    val severity: Severity = Severity.MEDIUM,
    val resolvable: Boolean = true,
    val status: RecommendationStatus = RecommendationStatus.OPEN,
    @SerialName("seer_run_url") val seerRunUrl: String? = null
)

@Serializable
data class SentryIssue(
    val id: String,
    val title: String,
    val culprit: String? = null,
    val count: Int,
    val level: String,
    val permalink: String
)

@Serializable
data class FlowAnalysisResponse(
    @SerialName("flow_id") val flowId: String,
    val status: AnalysisStatus,
    val title: String? = null,
    val recommendations: List<Recommendation> = emptyList(),
    val issues: List<SentryIssue> = emptyList(),
    val error: String? = null,
    @SerialName("enrichment_errors") val enrichmentErrors: List<String> = emptyList()
)
