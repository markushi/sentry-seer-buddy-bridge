package io.sentry.buddy.flow

import io.sentry.buddy.AnalysisStatus
import io.sentry.buddy.endpoints.flow.loadDemoResultTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DemoResultTemplateTest {

    @Test
    fun `the packaged template decodes into a completed result`() {
        val template = loadDemoResultTemplate()

        assertEquals(AnalysisStatus.COMPLETED, template("flow-1").status)
        assertTrue(template("flow-1").recommendations.isNotEmpty(), "expected the demo recommendations")
    }

    @Test
    fun `the template carries the flow id it is asked for`() {
        val template = loadDemoResultTemplate()

        assertEquals("flow-42", template("flow-42").flowId)
    }
}
