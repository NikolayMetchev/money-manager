package com.moneymanager.tui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import co.touchlab.kermit.Logger
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.session.DatabaseSession
import com.moneymanager.terminal.TasksInterruptedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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

    /** Answers the credential vault's unlock requests until cancelled. */
    suspend fun answerVaultRequests()

    /** The database a new choice starts from: the last one opened, if any. */
    fun suggestedDatabase(): String?
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

        data class Open(
            val location: DbLocation,
        ) : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private var main: Job? = null
    private var forced = false

    /** The open database, or null while there's none. */
    var session: DatabaseSession? by mutableStateOf(null)
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

    /**
     * Runs until the person quits, then closes the open database. A failed close (a refused cloud upload)
     * is rethrown once everything is shut, so `mm` exits with its usual code; a forced stop throws
     * [TasksInterruptedException].
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
        session = attempt("Opening the database") { host.openStartup() }
        if (session == null) showChoice()
        for (command in commands) {
            when (command) {
                Command.Quit -> break
                Command.Choose -> showChoice()
                Command.CancelChoice -> if (session != null) choice = null
                is Command.Open -> switchTo(command.location)
            }
        }
        val open = session ?: return
        busy = "Closing ${open.location}"
        try {
            host.close(open)
        } finally {
            session = null
            busy = null
        }
    }

    private suspend fun switchTo(location: DbLocation) {
        choice = null
        session?.let { current ->
            if (current.location == location) return
            attempt("Closing ${current.location}") { host.close(current) }
            session = null
        }
        session = attempt("Opening $location") { host.open(location) }
        if (session == null) showChoice()
    }

    private fun showChoice() {
        val suggested = session?.location?.toString() ?: host.suggestedDatabase().orEmpty()
        choice = DatabaseChoice(suggested, cancellable = session != null)
    }

    private suspend fun <T> attempt(
        label: String,
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
            null
        } finally {
            busy = null
        }
    }
}
