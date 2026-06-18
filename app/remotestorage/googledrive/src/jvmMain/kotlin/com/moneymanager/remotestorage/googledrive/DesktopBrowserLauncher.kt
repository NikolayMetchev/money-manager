package com.moneymanager.remotestorage.googledrive

import com.moneymanager.remotestorage.RemoteAuthException
import java.awt.Desktop
import java.net.URI

/**
 * Opens the OAuth consent URL in the desktop's default browser. Tries AWT [Desktop] first, then falls
 * back to the OS "open a URL" command (`xdg-open` on Linux, `open` on macOS, `rundll32` on Windows) —
 * `Desktop.browse` is frequently unsupported on Linux, so the fallback is what makes sign-in work there.
 */
class DesktopBrowserLauncher : BrowserLauncher {
    override fun open(url: String) {
        val uri = URI(url)
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri)
                return
            }
        }

        val osName = System.getProperty("os.name").orEmpty().lowercase()
        val command =
            when {
                "win" in osName -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
                "mac" in osName -> listOf("open", url)
                else -> listOf("xdg-open", url)
            }
        runCatching {
            ProcessBuilder(command).start()
            return
        }

        throw RemoteAuthException("Couldn't open a browser automatically. Open this URL to finish signing in: $url")
    }
}
