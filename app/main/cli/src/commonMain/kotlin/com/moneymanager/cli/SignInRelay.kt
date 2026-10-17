package com.moneymanager.cli

import co.touchlab.kermit.Logger
import com.moneymanager.remotestorage.googledrive.BrowserLauncher

private val logger = Logger.withTag("SignInRelay")

/**
 * The browser the app's services open a Google sign-in with. It's built once, with the services, but where the URL
 * should go depends on the command: a line command opens [default] (the system browser, or a printed URL with
 * `--no-browser`), while `mm tui`, which owns the screen, shows it with [during].
 */
internal class SignInRelay(
    private val default: BrowserLauncher,
    /** The system browser, or null with `--no-browser`. */
    private val system: BrowserLauncher?,
) : BrowserLauncher {
    private var shower: ((String) -> Unit)? = null

    override fun open(url: String) {
        val show = shower ?: return default.open(url)
        // A URL copied from a dialog can pick up its line breaks, so the log file keeps it whole.
        logger.i { "Google sign-in URL: $url" }
        show(url)
        system?.open(url)
    }

    /** Runs [block] with sign-in URLs handed to [show] (and the system browser, unless `--no-browser`). */
    suspend fun <T> during(
        show: (String) -> Unit,
        block: suspend () -> T,
    ): T {
        shower = show
        try {
            return block()
        } finally {
            shower = null
        }
    }
}
