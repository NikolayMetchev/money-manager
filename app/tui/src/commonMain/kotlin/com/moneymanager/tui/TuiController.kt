package com.moneymanager.tui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import co.touchlab.kermit.Logger
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.remotestorage.sync.RemoteDatabaseController
import com.moneymanager.session.DatabaseSession
import com.moneymanager.session.SessionImports
import com.moneymanager.tasks.TaskManager
import com.moneymanager.terminal.TasksInterruptedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

private val logger = Logger.withTag("TuiController")

/**
 * How the TUI opens and closes databases. The CLI supplies it, so `mm tui` follows the same rules as every
 * other `mm` command: `--db`, the last database, cloud copies, `--push`/`--keep-local`.
 */
interface TuiSessionHost {
    /** Opens the database to start with, or returns null when there's none, so the person must choose. */
    suspend fun openStartup(): DatabaseSession?

    /** Opens the database at [location], creating it when there's no file there. */
    suspend fun open(location: DbLocation): DatabaseSession

    /** Closes [session], asking what to do with a changed cloud-backed copy. */
    suspend fun close(session: DatabaseSession)

    /** Downloads the cloud copy over [session]'s working copy, closing it first, and opens the result. */
    suspend fun reload(session: DatabaseSession): DatabaseSession

    /** The cloud sync of a cloud-backed database, or null where there's no cloud storage. */
    val remote: RemoteDatabaseController?

    val appVersion: AppVersion

    /** The imports [session] runs: its directory and API services, sharing one write lock. */
    fun imports(session: DatabaseSession): SessionImports

    /** Answers the credential vault's unlock requests until cancelled. */
    suspend fun answerVaultRequests()

    /** The database a new choice starts from: the last one opened, if any. */
    fun suggestedDatabase(): String?
}

/**
 * What runs in the background on an open database: its [imports] and the [tasks] they report, on a scope that's
 * cancelled (and waited for) before the database closes, so nothing writes to a closing database.
 */
class SessionWork(
    val imports: SessionImports,
    val scope: CoroutineScope,
) {
    val tasks = TaskManager(scope)

    suspend fun stop() = scope.coroutineContext.job.cancelAndJoin()
}

/** The database chooser: a path to type, and whether Escape can go back to an open database. */
class DatabaseChoice(
    suggested: String,
    val cancellable: Boolean,
) {
    val editor = LineEditor(suggested)
}

/**
 * The TUI's lifecycle, apart from drawing: opens the startup database (or asks for one), switches databases,
 * and closes the open one on quit. [run] owns it; the shell only sends it commands. Failures show as a
 * notice and leave the person in the chooser rather than ending the TUI.
 */
class TuiController(
    private val host: TuiSessionHost,
    val prompter: DialogPrompter = DialogPrompter(),
) {
    private sealed interface Command {
        data object Quit : Command

        data object Choose : Command

        data object CancelChoice : Command

        data object Reload : Command

        data class Open(
            val location: DbLocation,
        ) : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)

    /** The first close that failed while switching databases (a refused cloud upload), rethrown when [run] ends. */
    private var switchCloseFailure: Exception? = null
    private var main: Job? = null
    private var forced = false

    /** The open database, or null while there's none. */
    var session: DatabaseSession? by mutableStateOf(null)
        private set

    /** The open database's background work, or null while there's none. */
    var work: SessionWork? by mutableStateOf(null)
        private set

    /** What the TUI is busy doing (opening, closing), or null. */
    var busy: String? by mutableStateOf(null)
        private set

    /** The database chooser, while it's open. */
    var choice: DatabaseChoice? by mutableStateOf(null)
        private set

    /** True once the person asked to quit. */
    var quitting: Boolean by mutableStateOf(false)
        private set

    /** Asks to quit: the open database closes first, which may ask about a cloud upload. */
    fun quit() {
        quitting = true
        prompter.dismiss()
        commands.trySend(Command.Quit)
    }

    /** Ctrl-C: quits, and a second one stops at once without closing properly. */
    fun interrupt() {
        if (!quitting) {
            quit()
        } else {
            forced = true
            main?.cancel()
        }
    }

    fun chooseDatabase() {
        commands.trySend(Command.Choose)
    }

    fun cancelChoice() {
        commands.trySend(Command.CancelChoice)
    }

    fun open(location: DbLocation) {
        commands.trySend(Command.Open(location))
    }

    /** Replaces the open cloud-backed database with the cloud copy, discarding local changes not uploaded. */
    fun reloadFromRemote() {
        commands.trySend(Command.Reload)
    }

    val remote: RemoteDatabaseController? get() = host.remote

    val appVersion: AppVersion get() = host.appVersion

    /**
     * Runs until the person quits, then closes the open database. A failed close (a refused cloud upload),
     * whether on quitting or earlier when switching databases, is rethrown once everything is shut, so `mm`
     * exits with its usual code; a forced stop throws [TasksInterruptedException].
     */
    suspend fun run() =
        coroutineScope {
            val unlocker = launch { host.answerVaultRequests() }
            val loop = async { loop() }
            main = loop
            try {
                loop.await()
            } catch (cancelled: CancellationException) {
                if (forced) throw TasksInterruptedException()
                throw cancelled
            } finally {
                unlocker.cancel()
            }
        }

    private suspend fun loop() {
        use(attempt("Opening the database") { host.openStartup() })
        if (session == null) showChoice()
        for (command in commands) {
            when (command) {
                Command.Quit -> break
                Command.Choose -> showChoice()
                Command.CancelChoice -> if (session != null) choice = null
                is Command.Open -> switchTo(command.location)
                Command.Reload -> reload()
            }
        }
        session?.let { open ->
            busy = "Closing ${open.location}"
            try {
                work?.stop()
                host.close(open)
            } finally {
                use(null)
                busy = null
            }
        }
        switchCloseFailure?.let { throw it }
    }

    private suspend fun switchTo(location: DbLocation) {
        choice = null
        session?.let { current ->
            if (current.location == location) return
            attempt("Closing ${current.location}", onFailure = { if (switchCloseFailure == null) switchCloseFailure = it }) {
                work?.stop()
                host.close(current)
            }
            use(null)
        }
        use(attempt("Opening $location") { host.open(location) })
        if (session == null) showChoice()
    }

    private suspend fun reload() {
        val current = session ?: return
        work?.stop()
        // The download closes the old handle first, so a failure leaves no database open.
        use(attempt("Downloading the cloud copy") { host.reload(current) })
        if (session == null) showChoice()
    }

    // The work first, so a screen never sees an open database without it.
    private suspend fun use(opened: DatabaseSession?) {
        work =
            opened?.let {
                val context = currentCoroutineContext()
                SessionWork(host.imports(it), CoroutineScope(context + SupervisorJob(context.job)))
            }
        session = opened
    }

    private fun showChoice() {
        val suggested = session?.location?.toString() ?: host.suggestedDatabase().orEmpty()
        choice = DatabaseChoice(suggested, cancellable = session != null)
    }

    private suspend fun <T> attempt(
        label: String,
        onFailure: (Exception) -> Unit = {},
        block: suspend () -> T,
    ): T? {
        busy = label
        return try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (expected: Exception) {
            // Whatever went wrong, the person is told and can choose again; the TUI keeps running.
            logger.w(expected) { "$label failed" }
            prompter.error("$label failed: ${expected.message ?: expected}")
            onFailure(expected)
            null
        } finally {
            busy = null
        }
    }
}
