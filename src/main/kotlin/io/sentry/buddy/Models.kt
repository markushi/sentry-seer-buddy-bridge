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
    val status: RecommendationStatus = RecommendationStatus.OPEN
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

@Serializable
data class HealthCheckConfigSnapshot(
    val dsnConfigured: Boolean = false,
    val release: String? = null,
    val environment: String? = null,
    val dist: String? = null,
    val sampleRate: Double? = null,
    val tracesSampleRate: Double? = null,
    val hasTracesSampler: Boolean = false,
    val profilesSampleRate: Double? = null,
    val profilingEnabled: Boolean = false,
    val autoSessionTrackingEnabled: Boolean = false,
    val attachStacktrace: Boolean = false,
    val beforeSendConfigured: Boolean = false,
    val beforeSendTransactionConfigured: Boolean = false,
    val beforeBreadcrumbConfigured: Boolean = false,
    val sessionReplaySampleRate: Double? = null,
    val sessionReplayOnErrorSampleRate: Double? = null,
    val sessionReplayEnabled: Boolean = false,
    val sessionReplayOnErrorEnabled: Boolean = false,
    val sessionReplayMaskAllText: Boolean = true,
    val sessionReplayMaskAllImages: Boolean = true,
    val anrEnabled: Boolean? = null,
    val attachScreenshot: Boolean? = null,
    val attachViewHierarchy: Boolean? = null,
    val autoActivityLifecycleTracingEnabled: Boolean? = null,
    val activityLifecycleBreadcrumbsEnabled: Boolean? = null,
    val appLifecycleBreadcrumbsEnabled: Boolean? = null,
    val networkEventBreadcrumbsEnabled: Boolean? = null,
    val framesTrackingEnabled: Boolean? = null,
    val performanceV2Enabled: Boolean? = null,
    val ndkEnabled: Boolean? = null,
    val reportHistoricalAnrs: Boolean? = null,
    val attachAnrThreadDump: Boolean? = null,
)

@Serializable
data class HealthCheckRequest(
    val sdk: String,
    val config: HealthCheckConfigSnapshot = HealthCheckConfigSnapshot(),
)

@Serializable
data class HealthCheckFinding(
    val id: String,
    val title: String,
    val description: String,
    val severity: Severity,
    val currentValue: String? = null,
    val suggestedValue: String? = null,
    val link: String? = null,
)

@Serializable
data class HealthCheckResponse(
    val summary: String,
    val findings: List<HealthCheckFinding> = emptyList(),
)
