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

enum class RecommendationStatus { OPEN, DISMISSED }

enum class ActionStatus { OPEN, EXECUTED }

enum class Severity { LOW, MEDIUM, HIGH }

@Serializable
data class Recommendation(
    val id: String,
    val title: String,
    val description: String,
    /**
     * a link to docs or additional resources.
     */
    val link: String? = null,
    val severity: Severity = Severity.MEDIUM,
    val status: RecommendationStatus = RecommendationStatus.OPEN,
    val actions: List<RecommendationAction> = emptyList(),
    /** How the span of the recommendation compares against production. Only spans have one. */
    @SerialName("performance_characteristics") val performanceCharacteristics: PerformanceCharacteristics? = null
)

/**
 * The duration of one span in the recording, next to the durations the same span op has in
 * production. Every field is optional, because the model cannot always query all of them.
 */
@Serializable
data class PerformanceCharacteristics(
    @SerialName("span_op") val spanOp: String? = null,
    /** an explore query on sentry.io that shows the production data. */
    val link: String? = null,
    /** the duration found in the recording. */
    val duration: String? = null,
    val avg: String? = null,
    val p50: String? = null,
    val p75: String? = null,
    val p90: String? = null,
    val p95: String? = null
)

/**
 * One thing that can be done about a recommendation. The app shows the label, and executing the
 * action starts the Seer run that carries out its description.
 */
@Serializable
data class RecommendationAction(
    val id: String,
    @SerialName("action_label") val actionLabel: String,
    @SerialName("actionable_for_seer") val actionableForSeer: Boolean = false,
    /** Detailed instructions on how the action is carried out. It goes into the Seer prompt. */
    val description: String = "",
    /** a link to an existing dashboard, a trace, or an explore query. */
    val link: String? = null,
    val status: ActionStatus = ActionStatus.OPEN,
    @SerialName("seer_run_url") val seerRunUrl: String? = null
)

/**
 * One thing that can be done about a completed flow analysis. The app shows the label, and actions
 * marked actionable for Seer can be executed through the flow-action endpoint.
 */
@Serializable
data class FlowAction(
    val id: String,
    @SerialName("action_label") val actionLabel: String,
    @SerialName("actionable_for_seer") val actionableForSeer: Boolean = false,
    /** Detailed instructions on how the action is carried out. It goes into the Seer prompt. */
    val description: String = "",
    /** a link to an existing dashboard, a trace, or an explore query. */
    val link: String? = null,
    val status: ActionStatus = ActionStatus.OPEN,
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
    @SerialName("enrichment_errors") val enrichmentErrors: List<String> = emptyList(),
    val actions: List<FlowAction> = emptyList()
)

/**
 * The SDK options the app reports. It mirrors `BuddySdkConfigSnapshot` of the client, so the
 * contract is visible in one place. Every field has a default, because an older client can send
 * fewer of them.
 */
@Serializable
data class SdkConfigSnapshot(
    @SerialName("dsn_configured") val dsnConfigured: Boolean = false,
    val release: String? = null,
    val environment: String? = null,
    val dist: String? = null,
    @SerialName("sample_rate") val sampleRate: Double? = null,
    @SerialName("traces_sample_rate") val tracesSampleRate: Double? = null,
    @SerialName("has_traces_sampler") val hasTracesSampler: Boolean = false,
    @SerialName("profiles_sample_rate") val profilesSampleRate: Double? = null,
    @SerialName("profiling_enabled") val profilingEnabled: Boolean = false,
    @SerialName("auto_session_tracking_enabled") val autoSessionTrackingEnabled: Boolean = false,
    @SerialName("attach_stacktrace") val attachStacktrace: Boolean = false,
    @SerialName("before_send_configured") val beforeSendConfigured: Boolean = false,
    @SerialName("before_send_transaction_configured") val beforeSendTransactionConfigured: Boolean = false,
    @SerialName("before_breadcrumb_configured") val beforeBreadcrumbConfigured: Boolean = false,
    @SerialName("session_replay_sample_rate") val sessionReplaySampleRate: Double? = null,
    @SerialName("session_replay_on_error_sample_rate") val sessionReplayOnErrorSampleRate: Double? = null,
    @SerialName("session_replay_enabled") val sessionReplayEnabled: Boolean = false,
    @SerialName("session_replay_on_error_enabled") val sessionReplayOnErrorEnabled: Boolean = false,
    @SerialName("session_replay_mask_all_text") val sessionReplayMaskAllText: Boolean = true,
    @SerialName("session_replay_mask_all_images") val sessionReplayMaskAllImages: Boolean = true,
    @SerialName("anr_enabled") val anrEnabled: Boolean? = null,
    @SerialName("attach_screenshot") val attachScreenshot: Boolean? = null,
    @SerialName("attach_view_hierarchy") val attachViewHierarchy: Boolean? = null,
    @SerialName("auto_activity_lifecycle_tracing_enabled") val autoActivityLifecycleTracingEnabled: Boolean? = null,
    @SerialName("activity_lifecycle_breadcrumbs_enabled") val activityLifecycleBreadcrumbsEnabled: Boolean? = null,
    @SerialName("app_lifecycle_breadcrumbs_enabled") val appLifecycleBreadcrumbsEnabled: Boolean? = null,
    @SerialName("network_event_breadcrumbs_enabled") val networkEventBreadcrumbsEnabled: Boolean? = null,
    @SerialName("frames_tracking_enabled") val framesTrackingEnabled: Boolean? = null,
    @SerialName("performance_v2_enabled") val performanceV2Enabled: Boolean? = null,
    @SerialName("ndk_enabled") val ndkEnabled: Boolean? = null,
    @SerialName("report_historical_anrs") val reportHistoricalAnrs: Boolean? = null,
    @SerialName("attach_anr_thread_dump") val attachAnrThreadDump: Boolean? = null
)

@Serializable
data class HealthCheckRequest(
    val sdk: String,
    val config: SdkConfigSnapshot = SdkConfigSnapshot()
)

@Serializable
data class HealthCheckResponse(
    val recommendations: List<Recommendation> = emptyList()
)

@Serializable
data class OpenUrlRequest(val url: String)
