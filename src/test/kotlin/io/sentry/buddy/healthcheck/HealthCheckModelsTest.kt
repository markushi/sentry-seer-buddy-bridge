package io.sentry.buddy.healthcheck

import io.sentry.buddy.appJson
import io.sentry.buddy.HealthCheckRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthCheckModelsTest {

    @Test
    fun `HealthCheckRequest reads the config through snake_case JSON keys`() {
        val json = """
            {
              "sdk": "io.sentry.android@8.40.0",
              "config": {
                "dsn_configured": true,
                "release": "com.example@1.0.0",
                "environment": "debug",
                "traces_sample_rate": 0.5,
                "has_traces_sampler": true,
                "session_replay_on_error_sample_rate": 1.0,
                "session_replay_mask_all_text": false,
                "anr_enabled": true,
                "attach_screenshot": false,
                "auto_activity_lifecycle_tracing_enabled": true,
                "performance_v2_enabled": true,
                "ndk_enabled": true,
                "attach_anr_thread_dump": false
              }
            }
        """.trimIndent()

        val request = appJson.decodeFromString(HealthCheckRequest.serializer(), json)

        assertEquals("io.sentry.android@8.40.0", request.sdk)
        assertTrue(request.config.dsnConfigured)
        assertEquals("com.example@1.0.0", request.config.release)
        assertEquals(0.5, request.config.tracesSampleRate)
        assertTrue(request.config.hasTracesSampler)
        assertEquals(1.0, request.config.sessionReplayOnErrorSampleRate)
        assertTrue(!request.config.sessionReplayMaskAllText)
        assertEquals(true, request.config.anrEnabled)
        assertEquals(false, request.config.attachScreenshot)
        assertEquals(true, request.config.autoActivityLifecycleTracingEnabled)
        assertEquals(true, request.config.performanceV2Enabled)
        assertEquals(true, request.config.ndkEnabled)
        assertEquals(false, request.config.attachAnrThreadDump)
    }

    @Test
    fun `HealthCheckRequest keeps the defaults when the config is absent`() {
        val request = appJson.decodeFromString(
            HealthCheckRequest.serializer(),
            """{"sdk": "io.sentry.android@8.40.0"}"""
        )

        assertTrue(!request.config.dsnConfigured)
        assertTrue(request.config.sessionReplayMaskAllText)
        assertEquals(null, request.config.anrEnabled)
    }

    @Test
    fun `HealthCheckRequest writes the config back with snake_case JSON keys`() {
        val request = appJson.decodeFromString(
            HealthCheckRequest.serializer(),
            """{"sdk": "io.sentry.android@8.40.0", "config": {"dsn_configured": true, "traces_sample_rate": 0.5}}"""
        )

        val encoded = appJson.encodeToString(HealthCheckRequest.serializer(), request)

        assertTrue(encoded.contains(""""dsn_configured":true"""))
        assertTrue(encoded.contains(""""traces_sample_rate":0.5"""))
        assertTrue(!encoded.contains("dsnConfigured"))
    }
}
