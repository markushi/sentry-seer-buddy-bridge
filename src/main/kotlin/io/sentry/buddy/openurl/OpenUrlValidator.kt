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

    if (!uri.scheme.equals("https", ignoreCase = true)) return "url must use https"
    if (!ALLOWED_HOST.equals(uri.host, ignoreCase = true)) return "url host must be $ALLOWED_HOST"

    return null
}
