package io.sentry.buddy.endpoints.openurl

import java.awt.Desktop
import java.net.URI

interface BrowserLauncher {
    fun open(uri: URI)
}

class DesktopBrowserLauncher : BrowserLauncher {
    override fun open(uri: URI) {
        check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            "Desktop browse action is not supported on this platform"
        }
        Desktop.getDesktop().browse(uri)
    }
}
