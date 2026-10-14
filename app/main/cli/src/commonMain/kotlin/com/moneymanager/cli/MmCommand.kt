package com.moneymanager.cli

import co.touchlab.kermit.Logger
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.parse
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.obj
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.nullableFlag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.mordant.terminal.Terminal
import com.moneymanager.credentialvault.CredentialVaultLockedException
import com.moneymanager.importengineapi.EditingLockedException
import com.moneymanager.session.DatabaseSession
import kotlinx.coroutines.CancellationException

private val logger = Logger.withTag("MmCommand")

/** What every subcommand gets from the root: the parsed global options and the services behind them. */
internal class CliRuntime(
    val terminal: Terminal,
    val output: Output,
    val console: Console,
    private val sessionOptions: SessionOptions,
    servicesFactory: () -> CliServices,
) {
    val services: CliServices by lazy(servicesFactory)

    /** Runs [block] on the open database, then closes it (asking about a cloud upload when it changed). */
    suspend fun <T> withSession(block: suspend (DatabaseSession) -> T): T = CliSession(services, console, sessionOptions).run(block)
}

/** `mm`: global options, then a subcommand. */
internal class MmCommand(
    private val platform: CliPlatform = CliPlatform.system(),
) : SuspendingCliktCommand(name = "mm") {
    private val db by option("--db", metavar = "PATH", help = "Database file (default: the last one the desktop app or mm opened)")
    private val format by option(
        "--format",
        help = "Output format (default: table in a terminal, json when stdout is redirected)",
    ).enum<OutputFormat> { it.name.lowercase() }
    private val quiet by option("--quiet", "-q", help = "Print only the result and errors").flag()
    private val verbose by option("--verbose", "-v", help = "Also print debug logs to stderr").flag()
    private val vaultPasswordStdin by option(
        "--vault-password-stdin",
        help = "Read the credential vault password from the first line of stdin (or set ${Console.VAULT_PASSWORD_ENV})",
    ).flag()
    private val noBrowser by option("--no-browser", help = "Print the Google sign-in URL instead of opening a browser").flag()
    private val hydrate by option("--hydrate", help = "Cloud database: download the cloud copy before running").flag()
    private val push by option("--push", help = "Cloud database: upload changes on exit (default: ask in a terminal)")
        .nullableFlag("--no-push")
    private val keepLocal by option("--keep-local", help = "Cloud database: keep the local working copy on exit (default)")
        .nullableFlag("--drop-local")

    init {
        subcommands(VersionCommand(), StatusCommand())
    }

    override fun help(context: Context): String =
        """
        Money Manager on the command line.

        Exit codes: 0 ok, 1 error, 2 usage, 3 partial failure or needs attention.
        """.trimIndent()

    override suspend fun run() {
        installCliLogging(terminal, verbose, quiet, platform.logFile)
        val console = Console(terminal, currentContext.readEnvvar, vaultPasswordStdin, quiet)
        val resolvedFormat = format ?: if (terminal.terminalInfo.outputInteractive) OutputFormat.TABLE else OutputFormat.JSON
        currentContext.obj =
            CliRuntime(
                terminal = terminal,
                output = Output(terminal, resolvedFormat),
                console = console,
                sessionOptions = SessionOptions(db, hydrate, push, keepLocal),
                servicesFactory = { platform.services(platform.browser(noBrowser, console::error)) },
            )
    }

    /** Parses [argv], runs the command and returns the exit code (see [ExitCodes]); errors are printed to stderr. */
    suspend fun execute(argv: List<String>): Int =
        try {
            parse(argv)
            ExitCodes.OK
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: CliktError) {
            echoFormattedHelp(error)
            exitCodeFor(error)
        } catch (locked: EditingLockedException) {
            fail(locked, "The cloud copy has changes this device hasn't downloaded; rerun with --hydrate")
        } catch (locked: CredentialVaultLockedException) {
            fail(locked, "The credential vault is locked: ${locked.message}")
        } catch (expected: Exception) {
            fail(expected, expected.message ?: expected.toString())
        }

    private fun fail(
        failure: Exception,
        message: String,
    ): Int {
        logger.i(failure) { "mm failed" }
        terminal.rawPrint("Error: $message\n", stderr = true)
        return ExitCodes.ERROR
    }

    private companion object {
        // Clikt reports usage errors with status 1; mm documents 2.
        fun exitCodeFor(error: CliktError): Int =
            when {
                error is UsageError -> ExitCodes.USAGE
                error is PrintHelpMessage && error.error -> ExitCodes.USAGE
                else -> error.statusCode
            }
    }
}
