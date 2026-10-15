package com.moneymanager.terminal

/**
 * How a terminal front end asks the person something. The CLI reads a line at a time from stderr's terminal;
 * the TUI owns the screen in raw mode, so it answers the same questions with dialogs. Code that only needs
 * answers (the vault unlocker, the cloud close prompts) takes a [Prompter] and works under both.
 */
interface Prompter {
    /** True when there's a person to answer prompts; otherwise every prompt returns null or its default. */
    val interactive: Boolean

    /** A message the person must see. */
    fun error(message: String)

    /** A message the person can be spared (`--quiet`). */
    fun warn(message: String)

    /** Asks for a secret without echoing it; null when the person declined or answered with nothing. */
    suspend fun secret(label: String): String?

    /** Asks for a line of text, offering [default] for an empty answer; null when the person declined. */
    suspend fun text(
        label: String,
        default: String,
    ): String?

    /** Asks a yes/no question; declining takes [default]. */
    suspend fun confirm(
        label: String,
        default: Boolean,
    ): Boolean
}
