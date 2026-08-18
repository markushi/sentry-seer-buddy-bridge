package io.sentry.buddy.openurl

import io.sentry.buddy.endpoints.openurl.validateOpenUrl
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
    fun `accepts a subdomain of sentry_io because run links live on the org subdomain`() {
        assertNull(validateOpenUrl("https://sentry-sdks.sentry.io/issues/?statsPeriod=10m&explorerRunId=uuid"))
    }

    @Test
    fun `rejects a host that only ends with sentry_io as a suffix`() {
        assertEquals(
            "url host must be sentry.io",
            validateOpenUrl("https://evilsentry.io/organizations/acme/issues/123/")
        )
    }

    @Test
    fun `rejects a malformed url`() {
        assertEquals("url is malformed", validateOpenUrl("not a url"))
    }

    @Test
    fun `accepts a case-insensitive scheme and host`() {
        assertNull(validateOpenUrl("https://Sentry.IO/organizations/acme/issues/123/"))
    }

    @Test
    fun `rejects a userinfo trick where getHost returns evil_com`() {
        assertEquals(
            "url host must be sentry.io",
            validateOpenUrl("https://sentry.io@evil.com/x")
        )
    }

    @Test
    fun `rejects an underscore host that parses to a null host`() {
        assertEquals(
            "url host must be sentry.io",
            validateOpenUrl("https://sentry.io_evil.com/x")
        )
    }
}
