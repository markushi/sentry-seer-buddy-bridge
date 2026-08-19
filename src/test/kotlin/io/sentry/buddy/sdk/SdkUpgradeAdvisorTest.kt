package io.sentry.buddy.sdk

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.sentry.buddy.Severity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SdkUpgradeAdvisorTest {

    private fun mockClient(tagName: String): HttpClient {
        val mockEngine = MockEngine {
            respond(
                content = """{"tag_name": "$tagName"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        return HttpClient(mockEngine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
    }

    private fun failingClient(): HttpClient {
        val mockEngine = MockEngine { respond(content = "boom", status = HttpStatusCode.InternalServerError) }
        return HttpClient(mockEngine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
    }

    @Test
    fun `parseSdkVersion extracts the version after the @`() {
        assertEquals("8.40.0", SdkUpgradeAdvisor().parseSdkVersion("io.sentry.android@8.40.0"))
    }

    @Test
    fun `parseSdkVersion returns null when there is no @`() {
        assertNull(SdkUpgradeAdvisor().parseSdkVersion("io.sentry.android"))
    }

    @Test
    fun `isOutdated is true when the latest release has a higher version`() {
        assertTrue(SdkUpgradeAdvisor().isOutdated(current = "8.40.0", latest = "8.41.0"))
    }

    @Test
    fun `isOutdated is false when current already matches latest`() {
        assertTrue(!SdkUpgradeAdvisor().isOutdated(current = "8.41.0", latest = "8.41.0"))
    }

    @Test
    fun `isOutdated treats missing trailing components as zero`() {
        assertTrue(!SdkUpgradeAdvisor().isOutdated(current = "8.41.0", latest = "8.41"))
    }

    @Test
    fun `upgradeRecommendation describes the upgrade when the sdk is outdated`() = runBlocking {
        val advisor = SdkUpgradeAdvisor(httpClient = mockClient("8.41.0"))

        val recommendation = advisor.upgradeRecommendation("io.sentry.android@8.40.0")

        assertEquals("Upgrade Sentry SDK to 8.41.0", recommendation?.title)
        assertEquals("https://github.com/getsentry/sentry-java/releases/tag/8.41.0", recommendation?.link)
        assertEquals(Severity.LOW, recommendation?.severity)
        assertTrue(recommendation!!.description.contains("io.sentry.android@8.40.0"))
        val (prAction, changelogAction) = recommendation.actions
        assertEquals("Open a PR", prAction.actionLabel)
        assertTrue(prAction.description.contains("8.41.0"), "the action says which version to go to")
        assertEquals("Show Changelog", changelogAction.actionLabel)
        assertEquals(
            "https://github.com/getsentry/sentry-java/releases/tag/8.41.0",
            changelogAction.link,
            "the changelog action points at the release notes"
        )
    }

    @Test
    fun `upgradeRecommendation is null when the sdk is already current`() = runBlocking {
        val advisor = SdkUpgradeAdvisor(httpClient = mockClient("8.40.0"))

        assertNull(advisor.upgradeRecommendation("io.sentry.android@8.40.0"))
    }

    @Test
    fun `upgradeRecommendation is null when the sdk version does not parse`() = runBlocking {
        val advisor = SdkUpgradeAdvisor(httpClient = mockClient("8.41.0"))

        assertNull(advisor.upgradeRecommendation("io.sentry.android"))
    }

    @Test
    fun `upgradeRecommendation is null when the latest release cannot be fetched`() = runBlocking {
        val advisor = SdkUpgradeAdvisor(httpClient = failingClient())

        assertNull(advisor.upgradeRecommendation("io.sentry.android@8.40.0"))
    }
}
