package io.sentry.buddy.openurl

import java.net.URI
import java.net.URISyntaxException

private const val ALLOWED_HOST = "sentry.io"

fun validateOpenUrl(url: String): String? {
    val uri = try {
        URI(url)
    } catch (e: URISyntaxException) {
        return "url is malformed"
    }

    if (uri.scheme != "https") return "url must use https"
    if (uri.host != ALLOWED_HOST) return "url host must be $ALLOWED_HOST"

    return null
}
