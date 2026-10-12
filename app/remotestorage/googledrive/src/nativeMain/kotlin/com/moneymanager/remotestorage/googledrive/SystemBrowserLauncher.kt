package com.moneymanager.remotestorage.googledrive

import com.moneymanager.remotestorage.RemoteAuthException

/** Opens the OAuth consent URL in the OS default browser (`xdg-open` on Linux, `open` on macOS, the shell on Windows). */
class SystemBrowserLauncher : BrowserLauncher {
    override fun open(url: String) {
        if (!openInSystemBrowser(url)) {
            throw RemoteAuthException("Couldn't open a browser automatically. Open this URL to finish signing in: $url")
        }
    }
}

/** Asks the OS to open [url] in its default browser. Returns false if that couldn't be started. */
internal expect fun openInSystemBrowser(url: String): Boolean
