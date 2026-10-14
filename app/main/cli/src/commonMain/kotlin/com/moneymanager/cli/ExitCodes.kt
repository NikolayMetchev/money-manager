package com.moneymanager.cli

import com.github.ajalt.clikt.core.CliktError

/** The process exit codes `mm` documents. */
internal object ExitCodes {
    const val OK = 0
    const val ERROR = 1
    const val USAGE = 2

    /** Some of the work failed, or something needs a person (a refused upload, files no strategy matched). */
    const val NEEDS_ATTENTION = 3
}

/** The command finished, but part of it failed or needs a person; exits with [ExitCodes.NEEDS_ATTENTION]. */
internal class NeedsAttention(
    message: String,
) : CliktError(message, statusCode = ExitCodes.NEEDS_ATTENTION)
