package com.moneymanager.cli

import com.github.ajalt.clikt.core.UsageError
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.files.expandTilde
import com.moneymanager.remotestorage.sync.RemoteDatabaseBinding
import com.moneymanager.remotestorage.sync.RemoteDatabaseController
import com.moneymanager.session.DatabaseSession
import com.moneymanager.session.DatabaseSessions
import com.moneymanager.session.StartupTarget
import com.moneymanager.session.closeDecision
import com.moneymanager.session.performClose
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
 */
internal class CliSession(
    private val services: CliServices,
    private val console: Console,
    private val options: SessionOptions,
) {
    private val controller: RemoteDatabaseController? = services.remoteController
    private val sessions =
        DatabaseSessions(services.databaseManager, services.localSettings, services.credentialVault, controller)

    suspend fun <T> run(block: suspend (DatabaseSession) -> T): T =
        coroutineScope {
            val unlocker = launch { VaultUnlocker(services.credentialVault, console).answerRequests() }
            try {
                val session = open()
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

    private suspend fun open(): DatabaseSession {
        val explicit = options.db?.let { DbLocation(expandTilde(it)) }
        if (explicit != null && !services.databaseManager.databaseExists(explicit)) {
            usage("No database at $explicit")
        }
        var restored = false
        var session =
            when (val target = sessions.resolveStartup(explicit)) {
                is StartupTarget.Open -> sessions.open(target.location, remoteCache = target.remoteCache || isCloudCache(target.location))
                is StartupTarget.NeedsRemoteUnlock -> {
                    val password =
                        console.remotePassword(target.binding.remoteName)
                            ?: usage(
                                "${target.binding.remoteName} has no local copy on this device; restoring it needs its password " +
                                    "(set ${Console.REMOTE_PASSWORD_ENV} or run in a terminal)",
                            )
                    restored = true
                    sessions.restoreRemote(target.binding, password)
                }
                StartupTarget.ChooseDatabase ->
                    usage("No database to open: pass --db <path>, or open one in the desktop app first")
            }
        val binding = cloudBinding(session)
        if (binding == null) {
            if (options.hydrate) {
                session.database.close()
                usage("--hydrate needs a cloud-backed database")
            }
            return session
        }
        val controller = checkNotNull(controller)
        if (!controller.hasActiveSession()) {
            val password = if (options.hydrate) console.remotePassword(binding.remoteName) else console.suppliedRemotePassword()
            if (password != null) {
                runCatching { controller.resume(password) }
                    .onSuccess { resumed -> if (!resumed) console.warn("The password for ${binding.remoteName} is wrong.") }
                    .onFailure { console.warn("Couldn't reach ${binding.remoteName}: ${it.message}") }
            }
        }
        if (options.hydrate && !restored) {
            if (!controller.hasActiveSession()) {
                session.database.close()
                usage("--hydrate needs the password for ${binding.remoteName}")
            }
            session = sessions.reloadFromRemote(session)
        }
        // Offline, the local copy still opens; the edit gate just can't know about other devices' changes.
        if (controller.hasActiveSession()) {
            runCatching { controller.checkRemote(session.database) }
                .onFailure { console.warn("Couldn't check ${binding.remoteName} for changes from other devices: ${it.message}") }
        }
        if (controller.syncState.value.editingLocked) {
            console.warn(
                "${binding.remoteName} has changes this device hasn't downloaded; writes are refused until you rerun with --hydrate.",
            )
        }
        return session
    }

    private fun usage(message: String): Nothing = throw UsageError(message)

    private fun isCloudCache(location: DbLocation): Boolean = controller?.activeBinding()?.localCachePath == location.path

    /** The cloud binding [session] is the working copy of; a remembered binding for another file doesn't count. */
    private fun cloudBinding(session: DatabaseSession): RemoteDatabaseBinding? =
        controller?.activeBinding()?.takeIf { it.localCachePath == session.location.path }

    private suspend fun close(session: DatabaseSession) {
        val controller = controller
        val decision = if (controller != null && cloudBinding(session) != null) controller.closeDecision(session.database) else null
        if (controller == null || decision == null) {
            session.database.close()
            return
        }
        // Like the desktop's close dialog, but only when there's something to decide: a read-only command
        // leaves an unchanged copy, and asking every time would make `mm` unusable in a loop.
        val ask = console.interactive && decision.localChanged
        val upload = options.push ?: (ask && console.confirm("Upload the changes to ${decision.remoteName}", default = true))
        val keepLocal = options.keepLocal ?: (!ask || console.confirm("Keep the local copy on this device", default = true))
        val password = if (upload && decision.needsPassword) console.remotePassword(decision.remoteName) else null
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
            console.warn("The local copy has changes not uploaded to ${decision.remoteName}; rerun with --push to upload them.")
        }
    }
}
