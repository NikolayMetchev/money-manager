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
import com.moneymanager.credentialvault.CredentialVaultLockedException
import com.moneymanager.importengineapi.EditingLockedException
import com.moneymanager.session.DatabaseSession
import com.moneymanager.tasks.TaskManager
import com.moneymanager.terminal.ProgressMode
import com.moneymanager.terminal.Prompter
import com.moneymanager.terminal.StderrRegion
import com.moneymanager.terminal.TaskProgressRenderer
import com.moneymanager.terminal.TasksInterruptedException
import kotlinx.coroutines.CancellationException

private val logger = Logger.withTag("MmCommand")

/** What every subcommand gets from the root: the parsed global options and the services behind them. */
internal class CliRuntime(
    val output: Output,
    val console: Console,
    private val sessionOptions: SessionOptions,
    private val progress: TaskProgressRenderer,
    /** Stops logging to stderr (the log file keeps everything), for a command that takes over the terminal. */
    val silenceStderrLogs: () -> Unit,
    servicesFactory: () -> CliServices,
) {
    val services: CliServices by lazy(servicesFactory)

    /** Runs [block] on the open database, then closes it (asking about a cloud upload when it changed). */
    suspend fun <T> withSession(block: suspend (DatabaseSession) -> T): T = session().run(block)

    /** How this run opens and closes databases, asking its questions through [prompter]. */
    fun session(prompter: Prompter = console): CliSession = CliSession(services, console, sessionOptions, prompter)

    /**
     * Runs [block] while showing the tasks it starts on [manager] on stderr: live bars in a terminal, plain
     * lines otherwise, nothing with `--quiet`. Ctrl-C cancels them and exits with [ExitCodes.INTERRUPTED].
     */
    suspend fun <T> withProgress(
        manager: TaskManager,
        block: suspend () -> T,
    ): T = progress.render(manager, block)
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
        subcommands(
            VersionCommand(),
            StatusCommand(),
            AccountsCommand(),
            BalancesCommand(),
            TransactionsCommand(),
            PeopleCommand(),
            StrategiesCommand(),
            ImportDirsCommand(),
            ImportCommand(),
            TuiCommand(),
        )
    }

    override fun help(context: Context): String =
        """
        Money Manager on the command line.

        Exit codes: 0 ok, 1 error, 2 usage, 3 partial failure or needs attention, 130 interrupted (Ctrl-C).
        """.trimIndent()

    override suspend fun run() {
        // One region owns stderr, so logs, messages and prompts all print around any live progress bars.
        val stderr = StderrRegion { terminal.rawPrint(it, stderr = true) }
        installCliLogging(stderr, verbose, quiet, platform.logFile)
        val console = Console(terminal, currentContext.readEnvvar, vaultPasswordStdin, quiet, stderr)
        val resolvedFormat = format ?: if (terminal.terminalInfo.outputInteractive) OutputFormat.TABLE else OutputFormat.JSON
        val progressMode = ProgressMode.of(quiet, platform.stderrIsTerminal(), currentContext.readEnvvar)
        currentContext.obj =
            CliRuntime(
                output = Output(terminal, resolvedFormat, console::warn),
                console = console,
                sessionOptions = SessionOptions(db, hydrate, push, keepLocal),
                progress =
                    TaskProgressRenderer(
                        mode = progressMode,
                        region = stderr,
                        width = { terminal.updateSize().width },
                    ),
                silenceStderrLogs = { installCliLogging(stderr, verbose = false, quiet = true, platform.logFile) },
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
        } catch (interrupted: TasksInterruptedException) {
            logger.i(interrupted) { "mm interrupted" }
            ExitCodes.INTERRUPTED
        } catch (locked: EditingLockedException) {
            report(locked, "The cloud copy has changes this device hasn't downloaded; rerun with --hydrate")
            ExitCodes.ERROR
        } catch (locked: CredentialVaultLockedException) {
            report(locked, "The credential vault is locked: ${locked.message}")
            ExitCodes.ERROR
        } catch (expected: Exception) {
            report(expected, expected.message ?: expected.toString())
            ExitCodes.ERROR
        }

    private fun report(
        failure: Exception,
        message: String,
    ) {
        logger.i(failure) { "mm failed" }
        terminal.rawPrint("Error: $message\n", stderr = true)
    }

    private companion object {
        // Clikt reports usage errors with status 1; mm documents 2.
        fun exitCodeFor(error: CliktError): Int =
            when (error) {
                is UsageError -> ExitCodes.USAGE
                is PrintHelpMessage -> if (error.error) ExitCodes.USAGE else error.statusCode
                else -> error.statusCode
            }
    }
}
