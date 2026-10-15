package com.moneymanager.cli

import com.github.ajalt.mordant.terminal.Terminal
import com.moneymanager.terminal.Prompter
import com.moneymanager.terminal.StderrRegion

/**
 * Talks to the person running `mm`. Prompts and messages go to stderr, so stdout stays the command's result
 * even when a prompt is answered; secrets come from the environment, stdin or a no-echo prompt, and are
 * never written anywhere. Everything goes through [stderr], so a message or prompt during a run with live
 * progress bars lands above them, or clears them while the person answers.
 */
internal class Console(
    private val terminal: Terminal,
    private val readEnvvar: (String) -> String?,
    /** `--vault-password-stdin`: the vault password is the first line of stdin, so stdin isn't the person. */
    val vaultPasswordFromStdin: Boolean,
    private val quiet: Boolean,
    private val stderr: StderrRegion = StderrRegion { terminal.rawPrint(it, stderr = true) },
) : Prompter {
    private var stdinPassword: String? = null
    private var stdinRead = false

    /** True when there's a person at a terminal to answer prompts. Reading the vault password from stdin rules that out. */
    override val interactive: Boolean get() = !vaultPasswordFromStdin && terminal.terminalInfo.inputInteractive

    /** A message the person must see, even with `--quiet`. */
    override fun error(message: String) = stderr.printAbove("$message\n")

    override fun warn(message: String) {
        if (!quiet) error(message)
    }

    /** The vault password given up front (`--vault-password-stdin`, then [VAULT_PASSWORD_ENV]), or null. */
    fun suppliedVaultPassword(): String? {
        if (vaultPasswordFromStdin) {
            if (!stdinRead) {
                stdinPassword =
                    stderr
                        .paused { terminal.readLineOrNull(hideInput = true) }
                        ?.trimEnd('\r', '\n')
                        ?.takeIf { it.isNotEmpty() }
                stdinRead = true
            }
            return stdinPassword
        }
        return readEnvvar(VAULT_PASSWORD_ENV)?.takeIf { it.isNotEmpty() }
    }

    /** The cloud database password from [REMOTE_PASSWORD_ENV] only, never asking. */
    fun suppliedRemotePassword(): String? = readEnvvar(REMOTE_PASSWORD_ENV)?.takeIf { it.isNotEmpty() }

    override suspend fun secret(label: String): String? = ask(label, hideInput = true)?.takeIf { it.isNotEmpty() }

    override suspend fun text(
        label: String,
        default: String,
    ): String? = ask("$label [$default]", hideInput = false)?.trim()?.ifEmpty { default }

    // An empty answer takes [default], and so does the end of input.
    override suspend fun confirm(
        label: String,
        default: Boolean,
    ): Boolean {
        repeat(MAX_ATTEMPTS) {
            val answer = ask("$label [${if (default) "Y/n" else "y/N"}]", hideInput = false)?.trim()?.lowercase() ?: return default
            when (answer) {
                "" -> return default
                "y", "yes" -> return true
                "n", "no" -> return false
            }
            error("Please answer y or n.")
        }
        return default
    }

    private fun ask(
        label: String,
        hideInput: Boolean,
    ): String? =
        stderr.paused {
            stderr.printAbove("$label: ")
            val line = terminal.readLineOrNull(hideInput)
            // A hidden read swallows the newline the person typed.
            if (hideInput) stderr.printAbove("\n")
            line
        }

    companion object {
        const val VAULT_PASSWORD_ENV = "MM_VAULT_PASSWORD"
        const val REMOTE_PASSWORD_ENV = "MM_REMOTE_PASSWORD"
        const val MAX_ATTEMPTS = 3
    }
}
