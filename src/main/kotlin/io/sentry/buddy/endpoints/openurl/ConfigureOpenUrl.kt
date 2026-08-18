package io.sentry.buddy.endpoints.openurl

import io.ktor.server.application.*

fun Application.configureOpenUrl(browserLauncher: BrowserLauncher = DesktopBrowserLauncher()) {
    openUrlRoutes(browserLauncher)
}
