package com.moneymanager.remotestorage.googledrive

/**
 * Opens a URL in the user's system browser. The OAuth consent step runs from a coroutine (not Compose),
 * so it can't use Compose's `LocalUriHandler`; the platform supplies an implementation instead —
 * `Desktop.browse` on JVM, an `ACTION_VIEW` intent on Android.
 */
fun interface BrowserLauncher {
    fun open(url: String)
}
