package com.moneymanager.cli

import com.github.ajalt.clikt.core.UsageError
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.files.expandTilde
import com.moneymanager.remotestorage.sync.RemoteDatabaseBinding
import com.moneymanager.remotestorage.sync.RemoteDatabaseController
import com.moneymanager.session.DatabaseSession
import com.moneymanager.session.DatabaseSessions
import com.moneymanager.session.StartupTarget
import com.moneymanager.session.bindingFor
import com.moneymanager.session.closeDecision
import com.moneymanager.session.performClose
import com.moneymanager.terminal.Prompter
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** How a command opens and closes its database: the global options that shape it. */
internal data class SessionOptions(
    val db: String?,
    /** Download the cloud copy over the local one before running. */
    val hydrate: Boolean,
    /** Upload a changed cloud-backed database on exit; null asks (in a terminal) or leaves it local. */
    val push: Boolean?,
    /** Keep the local working copy on exit; null asks (in a terminal) or keeps it. */
    val keepLocal: Boolean?,
)

/**
 * Opens the database a command runs on, answering vault prompts while it runs, and closes it the way the
 * desktop app's close dialog does. Cloud-backed databases open from the kept local copy (no password, no
 * network) unless `--hydrate`; when the cloud password is known the remote is checked, so the edit gate
 * refuses writes while another device's changes are waiting.
 *
 * Passwords given up front come from [console]; everything asked goes through [prompter], which is [console]
 * on the command line and dialogs in `mm tui`. The TUI opens, switches and closes databases with [openStartup],
 * [open] and [close] while [answerVaultRequests] runs for its whole life; a command uses [run].
 */
internal class CliSession(
    private val services: CliServices,
    private val console: Console,
    private val options: SessionOptions,
    private val prompter: Prompter = console,
) {
    private val controller: RemoteDatabaseController? = services.remoteController
    private val sessions =
        DatabaseSessions(services.databaseManager, services.localSettings, services.credentialVault, controller)

    suspend fun <T> run(block: suspend (DatabaseSession) -> T): T =
        coroutineScope {
            val unlocker = launch { answerVaultRequests() }
            try {
                val session =
                    openStartup()
                        ?: usage("No database to open: pass --db <path>, or open one in the desktop app first")
                var succeeded = false
                try {
                    val result = block(session)
                    succeeded = true
                    close(session)
                    result
                } finally {
                    // The command's own failure wins, but still close as the person asks, so local changes stay safe.
                    if (!succeeded) runCatching { close(session) }
                }
            } finally {
                unlocker.cancel()
            }
        }

    /** Answers the credential vault's unlock requests until cancelled. */
    suspend fun answerVaultRequests() = VaultUnlocker(services.credentialVault, console, prompter).answerRequests()

    /** Opens `--db`, or what the desktop app would open at startup; null when there's nothing to open, so the person must choose. */
    suspend fun openStartup(): DatabaseSession? {
        val explicit = options.db?.let { DbLocation(expandTilde(it)) }
        if (explicit != null && !services.databaseManager.databaseExists(explicit)) {
            usage("No database at $explicit")
        }
        return when (val target = sessions.resolveStartup(explicit)) {
            is StartupTarget.Open ->
                prepare(
                    sessions.open(
                        target.location,
                        remoteCache =
                            target.remoteCache || isCloudCache(target.location),
                    ),
                )
            is StartupTarget.NeedsRemoteUnlock -> {
                val password =
                    remotePassword(target.binding.remoteName)
                        ?: usage(
                            "${target.binding.remoteName} has no local copy on this device; restoring it needs its password " +
                                "(set ${Console.REMOTE_PASSWORD_ENV} or run in a terminal)",
                        )
                prepare(sessions.restoreRemote(target.binding, password), restored = true)
            }
            StartupTarget.ChooseDatabase -> null
        }
    }

    /** Opens the database at [location], creating it when there's no file there yet. `--hydrate` only applies at startup. */
    suspend fun open(location: DbLocation): DatabaseSession =
        prepare(sessions.open(location, remoteCache = isCloudCache(location)), hydrate = false)

    /** Downloads the cloud copy over [session]'s working copy (closing it) and reopens it, as `--hydrate` does at startup. */
    suspend fun reload(session: DatabaseSession): DatabaseSession =
        prepare(sessions.reloadFromRemote(session), restored = true, hydrate = false)

    private suspend fun prepare(
        opened: DatabaseSession,
        restored: Boolean = false,
        hydrate: Boolean = options.hydrate,
    ): DatabaseSession {
        var session = opened
        val binding = cloudBinding(session)
        if (binding == null) {
            if (hydrate) {
                session.database.close()
                usage("--hydrate needs a cloud-backed database")
            }
            return session
        }
        val controller = checkNotNull(controller)
        if (!controller.hasActiveSession()) {
            val password = if (hydrate) remotePassword(binding.remoteName) else console.suppliedRemotePassword()
            if (password != null) {
                runCatching { controller.resume(password) }
                    .onSuccess { resumed -> if (!resumed) prompter.warn("The password for ${binding.remoteName} is wrong.") }
                    .onFailure { prompter.warn("Couldn't reach ${binding.remoteName}: ${it.message}") }
            }
        }
        if (hydrate && !restored) {
            if (!controller.hasActiveSession()) {
                session.database.close()
                usage("--hydrate needs the password for ${binding.remoteName}")
            }
            session = sessions.reloadFromRemote(session)
        }
        // Offline, the local copy still opens; the edit gate just can't know about other devices' changes.
        if (controller.hasActiveSession()) {
            runCatching { controller.checkRemote(session.database) }
                .onFailure { prompter.warn("Couldn't check ${binding.remoteName} for changes from other devices: ${it.message}") }
        }
        if (controller.syncState.value.editingLocked) {
            prompter.warn(
                "${binding.remoteName} has changes this device hasn't downloaded; writes are refused until you rerun with --hydrate.",
            )
        }
        return session
    }

    /** The cloud database password from [Console.REMOTE_PASSWORD_ENV], or asked for when there's a person to ask. */
    private suspend fun remotePassword(remoteName: String): String? =
        console.suppliedRemotePassword()
            ?: if (prompter.interactive) prompter.secret("Password for the cloud database $remoteName") else null

    private fun usage(message: String): Nothing = throw UsageError(message)

    private fun isCloudCache(location: DbLocation): Boolean = controller?.bindingFor(location) != null

    private fun cloudBinding(session: DatabaseSession): RemoteDatabaseBinding? = controller?.bindingFor(session.location)

    /** Closes [session], asking (or following `--push`/`--keep-local`) whether to upload a changed cloud-backed copy. */
    suspend fun close(session: DatabaseSession) {
        val controller = controller
        val decision = if (controller != null && cloudBinding(session) != null) controller.closeDecision(session.database) else null
        if (controller == null || decision == null) {
            session.database.close()
            return
        }
        // Like the desktop's close dialog, but only when there's something to decide: a read-only command
        // leaves an unchanged copy, and asking every time would make `mm` unusable in a loop.
        val ask = prompter.interactive && decision.localChanged
        val upload = options.push ?: (ask && prompter.confirm("Upload the changes to ${decision.remoteName}", default = true))
        val keepLocal = options.keepLocal ?: (!ask || prompter.confirm("Keep the local copy on this device", default = true))
        val password = if (upload && decision.needsPassword) remotePassword(decision.remoteName) else null
        val outcome =
            controller.performClose(session.database, upload, keepLocal, password, decision.localChanged)
        if (upload && !outcome.uploaded) {
            val reason =
                if (decision.needsPassword && password == null) {
                    "its password is needed (set ${Console.REMOTE_PASSWORD_ENV})"
                } else {
                    "the cloud copy changed since this device last synced, or the upload failed; rerun with --hydrate"
                }
            throw NeedsAttention("Didn't upload to ${decision.remoteName}: $reason. The local copy was kept.")
        }
        if (!upload && decision.localChanged) {
            prompter.warn("The local copy has changes not uploaded to ${decision.remoteName}; rerun with --push to upload them.")
        }
    }
}
