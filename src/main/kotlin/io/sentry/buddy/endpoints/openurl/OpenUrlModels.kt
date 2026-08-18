package io.sentry.buddy.endpoints.openurl

import kotlinx.serialization.Serializable

@Serializable
data class OpenUrlRequest(val url: String)
