package io.sentry.buddy.openurl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenUrlValidatorTest {

    @Test
    fun `accepts an https sentry_io url`() {
        assertNull(validateOpenUrl("https://sentry.io/organizations/acme/issues/123/"))
    }

    @Test
    fun `rejects http scheme`() {
        assertEquals(
            "url must use https",
            validateOpenUrl("http://sentry.io/organizations/acme/issues/123/")
        )
    }

    @Test
    fun `rejects a different host`() {
        assertEquals(
            "url host must be sentry.io",
            validateOpenUrl("https://evil.com/organizations/acme/issues/123/")
        )
    }

    @Test
    fun `rejects a subdomain of sentry_io`() {
        assertEquals(
            "url host must be sentry.io",
            validateOpenUrl("https://sub.sentry.io/organizations/acme/issues/123/")
        )
    }

    @Test
    fun `rejects a malformed url`() {
        assertEquals("url is malformed", validateOpenUrl("not a url"))
    }
}
