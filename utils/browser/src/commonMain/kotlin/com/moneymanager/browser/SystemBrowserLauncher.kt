package com.moneymanager.browser

/**
 * Opens the OAuth consent URL in the OS default browser. The one [BrowserLauncher] for the desktop app and
 * the CLI on every platform; only [openInSystemBrowser], the part that starts a process or calls the OS,
 * differs per target.
 */
class SystemBrowserLauncher(
    private val opener: (String) -> Boolean = ::openInSystemBrowser,
) : BrowserLauncher {
    override fun open(url: String) {
        if (!opener(url)) {
            throw BrowserLaunchException("Couldn't open a browser automatically. Open this URL to finish signing in: $url")
        }
    }
}

/** Asks the OS to open [url] in its default browser. Returns false if that couldn't be started. */
internal expect fun openInSystemBrowser(url: String): Boolean
