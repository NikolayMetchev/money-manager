package com.moneymanager.cli

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.requireObject
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.localsettings.KEY_LAST_DATABASE
import com.moneymanager.remotestorage.sync.RemoteDatabaseController
import com.moneymanager.session.DatabaseSession
import com.moneymanager.session.SessionImports
import com.moneymanager.tui.DialogPrompter
import com.moneymanager.tui.NotATerminalException
import com.moneymanager.tui.TuiController
import com.moneymanager.tui.TuiSessionHost

/**
 * `mm tui`: the full-screen terminal UI. It opens databases exactly as the other commands do (the global
 * `--db`, `--hydrate`, `--push`/`--keep-local`, the vault and cloud password variables), but asks its questions
 * in dialogs, since it owns the terminal while it runs.
 */
internal class TuiCommand : SuspendingCliktCommand(name = "tui") {
    private val runtime by requireObject<CliRuntime>()

    override fun help(context: Context): String = "Open the full-screen terminal UI (needs a terminal)"

    override suspend fun run() {
        if (runtime.console.vaultPasswordFromStdin) {
            throw UsageError(
                "mm tui reads keys from the terminal, so it can't take --vault-password-stdin; set ${Console.VAULT_PASSWORD_ENV}",
            )
        }
        val prompter = DialogPrompter()
        val services = runtime.services
        val controller = TuiController(CliTuiHost(runtime.session(prompter), services), prompter)
        // A log line on stderr would land in the middle of the screen; the log file still gets everything.
        runtime.silenceStderrLogs()
        try {
            runtime.signIn.during(show = { url ->
                prompter.inform("Sign in to Google", "Open this URL in a browser to finish signing in:\n$url")
            }) {
                runtime.runTui(controller, services.credentialVault)
            }
        } catch (notATerminal: NotATerminalException) {
            throw UsageError("mm tui needs a terminal: run it without redirecting stdin or stdout")
        }
    }
}

private class CliTuiHost(
    private val session: CliSession,
    private val services: CliServices,
) : TuiSessionHost {
    override suspend fun openStartup(): DatabaseSession? = session.openStartup()

    override suspend fun open(location: DbLocation): DatabaseSession = session.open(location)

    override suspend fun close(session: DatabaseSession) = this.session.close(session)

    override suspend fun reload(session: DatabaseSession): DatabaseSession = this.session.reload(session)

    override val remote: RemoteDatabaseController? = services.remoteController

    override val appVersion: AppVersion = services.appVersion

    override fun imports(session: DatabaseSession): SessionImports =
        SessionImports(session.services, services.credentialVault, services.importFileSources, services.httpEngine)

    override suspend fun answerVaultRequests() = session.answerVaultRequests()

    override fun suggestedDatabase(): String =
        services.localSettings.getString(KEY_LAST_DATABASE) ?: services.databaseManager.getDefaultLocation().toString()
}
