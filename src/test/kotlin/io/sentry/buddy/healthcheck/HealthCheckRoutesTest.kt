package io.sentry.buddy.healthcheck

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import io.sentry.buddy.Recommendation
import io.sentry.buddy.RecommendationAction
import io.sentry.buddy.Severity
import io.sentry.buddy.appJson
import io.sentry.buddy.HealthCheckResponse
import io.sentry.buddy.endpoints.healthcheck.healthCheckRoutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthCheckRoutesTest {

    private fun upgradeRecommendation() = Recommendation(
        id = "rec-1",
        title = "Upgrade Sentry SDK to 8.41.0",
        description = "Version io.sentry.android@8.40.0 detected, but sentry-java 8.41.0 is available.",
        link = "https://github.com/getsentry/sentry-java/releases/tag/8.41.0",
        severity = Severity.LOW,
        actions = listOf(
            RecommendationAction(
                id = "act-1",
                actionLabel = "Open a PR",
                description = "Raise the Sentry SDK dependency to 8.41.0."
            )
        )
    )

    /** The payload the client sends, with every field of `BuddySdkConfigSnapshot` filled in. */
    private val fullClientPayload = """
        {
          "sdk": "io.sentry.android@8.40.0",
          "config": {
            "dsn_configured": true,
            "release": "com.example@1.0.0",
            "environment": "debug",
            "dist": "1",
            "sample_rate": 1.0,
            "traces_sample_rate": 0.5,
            "has_traces_sampler": false,
            "profiles_sample_rate": 0.1,
            "profiling_enabled": true,
            "auto_session_tracking_enabled": true,
            "attach_stacktrace": true,
            "before_send_configured": false,
            "before_send_transaction_configured": false,
            "before_breadcrumb_configured": false,
            "session_replay_sample_rate": 0.0,
            "session_replay_on_error_sample_rate": 1.0,
            "session_replay_enabled": false,
            "session_replay_on_error_enabled": true,
            "session_replay_mask_all_text": true,
            "session_replay_mask_all_images": true,
            "anr_enabled": true,
            "attach_screenshot": false,
            "attach_view_hierarchy": false,
            "auto_activity_lifecycle_tracing_enabled": true,
            "activity_lifecycle_breadcrumbs_enabled": true,
            "app_lifecycle_breadcrumbs_enabled": true,
            "network_event_breadcrumbs_enabled": true,
            "frames_tracking_enabled": true,
            "performance_v2_enabled": true,
            "ndk_enabled": true,
            "report_historical_anrs": false,
            "attach_anr_thread_dump": false
          }
        }
    """.trimIndent()

    @Test
    fun `POST v1 health-check answers with the upgrade recommendation for an outdated sdk`() = testApplication {
        var receivedSdk: String? = null
        application {
            install(ContentNegotiation) { json(appJson) }
            healthCheckRoutes { sdk, _ ->
                receivedSdk = sdk
                listOf(upgradeRecommendation())
            }
        }

        val response = client.post("/v1/health-check") {
            contentType(ContentType.Application.Json)
            setBody(fullClientPayload)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("io.sentry.android@8.40.0", receivedSdk)
        val body = appJson.decodeFromString<HealthCheckResponse>(response.bodyAsText())
        assertEquals(listOf(upgradeRecommendation()), body.recommendations)
    }

    @Test
    fun `POST v1 health-check answers with no recommendations for a current sdk`() = testApplication {
        application {
            install(ContentNegotiation) { json(appJson) }
            healthCheckRoutes { _, _ -> emptyList() }
        }

        val response = client.post("/v1/health-check") {
            contentType(ContentType.Application.Json)
            setBody("""{"sdk": "io.sentry.android@8.41.0", "config": {"dsn_configured": true}}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = appJson.decodeFromString<HealthCheckResponse>(response.bodyAsText())
        assertTrue(body.recommendations.isEmpty())
    }

    @Test
    fun `POST v1 health-check rejects a body without an sdk`() = testApplication {
        application {
            install(ContentNegotiation) { json(appJson) }
            healthCheckRoutes { _, _ -> listOf(upgradeRecommendation()) }
        }

        val response = client.post("/v1/health-check") {
            contentType(ContentType.Application.Json)
            setBody("""{"config": {"dsn_configured": true}}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
