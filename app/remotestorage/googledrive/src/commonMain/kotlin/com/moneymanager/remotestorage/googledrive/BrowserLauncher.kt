package com.moneymanager.remotestorage.googledrive

/**
 * Opens a URL in the user's system browser. The OAuth consent step runs from a coroutine (not Compose),
 * so it can't use Compose's `LocalUriHandler`; the platform supplies an implementation instead —
 * `Desktop.browse` on JVM, an `ACTION_VIEW` intent on Android, `SystemBrowserLauncher` on native, or
 * [PrintUrlBrowserLauncher] where there is no browser to open (headless or over SSH).
 */
fun interface BrowserLauncher {
    fun open(url: String)
}

/**
 * Hands the URL to [print] for the user to open themselves, for a headless machine or an SSH session. The
 * loopback redirect still lands on this machine, so the browser must be able to reach it (e.g. through a
 * forwarded port).
 */
class PrintUrlBrowserLauncher(
    private val print: (String) -> Unit,
) : BrowserLauncher {
    override fun open(url: String) {
        print("Open this URL in a browser to finish signing in: $url")
    }
}
