package io.sentry.buddy.openurl

import kotlinx.serialization.Serializable

@Serializable
data class OpenUrlRequest(val url: String)
