package com.moneymanager.tui

import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.Terminal
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.moneymanager.credentialvault.testing.InMemoryLocalSettings
import com.moneymanager.credentialvault.testing.inMemoryCredentialVault
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.importfilesource.ImportFileSource
import com.moneymanager.importfilesource.ImportFileSourceFactory
import com.moneymanager.importfilesource.localfolder.LocalFolderImportFileSource
import com.moneymanager.session.DatabaseSession
import com.moneymanager.session.DatabaseSessions
import com.moneymanager.session.SessionImports
import com.moneymanager.test.database.createTestDatabaseManager
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// Real time, not runTest's virtual clock: databases open on real dispatchers while the test waits for frames.

internal class FakeHost(
    private val startup: DbLocation?,
    /** Closing this database fails, like a refused cloud upload. */
    private val refuseClosing: DbLocation? = null,
) : TuiSessionHost {
    val vault = inMemoryCredentialVault()
    private val sessions = DatabaseSessions(createTestDatabaseManager(), InMemoryLocalSettings(), vault)
    val closed = mutableListOf<DbLocation>()

    override suspend fun openStartup(): DatabaseSession? = startup?.let { sessions.open(it) }

    override suspend fun open(location: DbLocation): DatabaseSession = sessions.open(location)

    override suspend fun close(session: DatabaseSession) {
        session.database.close()
        closed += session.location
        if (session.location == refuseClosing) throw IllegalStateException("Didn't upload")
    }

    override fun imports(session: DatabaseSession): SessionImports = SessionImports(session.services, vault, LocalFolders)

    override suspend fun answerVaultRequests() = awaitCancellation()

    override fun suggestedDatabase(): String? = null
}

/** Opens local import folders, the only kind a test adds. */
internal object LocalFolders : ImportFileSourceFactory {
    override suspend fun create(directory: ImportDirectory): ImportFileSource = LocalFolderImportFileSource(directory.folderRef)
}

internal fun tui(
    startup: DbLocation?,
    screens: List<TuiScreen>,
    test: suspend TestMosaic<String>.(FakeHost, TuiController) -> Unit,
) = runBlocking {
    val scope = this
    val host = FakeHost(startup)
    val controller = TuiController(host)
    runMosaicTest {
        // Wide enough for a whole test database path (native ones are long).
        state.size.value = Terminal.Size(columns = 240, rows = 24)
        val running = scope.launch { controller.run() }
        setContentAndSnapshot { TuiApp(controller, screens, host.vault) }
        try {
            test(host, controller)
        } finally {
            running.cancel()
        }
    }
}

internal suspend fun TestMosaic<String>.press(key: String) {
    sendKeyEvent(keyboardEvent(key))
    screen()
}

/** Sends [keys] before the screen redraws, as a fast typist or a paste does, so they all reach one composition. */
internal suspend fun TestMosaic<String>.pressTogether(vararg keys: String) {
    keys.forEach { sendKeyEvent(keyboardEvent(it)) }
    screen()
}

private fun keyboardEvent(key: String): KeyboardEvent {
    val codepoint =
        when (key) {
            "Enter" -> 13
            "Escape" -> 27
            "Tab" -> 9
            "Backspace" -> 127
            "ArrowUp" -> KeyboardEvent.Up
            "ArrowDown" -> KeyboardEvent.Down
            "PageUp" -> KeyboardEvent.PageUp
            "PageDown" -> KeyboardEvent.PageDown
            "Home" -> KeyboardEvent.Home
            "End" -> KeyboardEvent.End
            else -> key.single().code
        }
    return KeyboardEvent(codepoint)
}

internal suspend fun TestMosaic<String>.pressCtrl(key: Char) {
    sendKeyEvent(KeyboardEvent(key.code, modifiers = KeyboardEvent.ModifierCtrl))
    screen()
}

internal suspend fun TestMosaic<String>.type(text: String) = text.forEach { press(it.toString()) }

/**
 * The screen once it matches [until]; fails with the last screen after a while. Generous, since creating a test
 * database on a loaded CI runner (Windows native especially) can take over ten seconds, and a pass returns at once.
 */
internal suspend fun TestMosaic<String>.screen(until: (String) -> Boolean = { true }): String {
    val deadline = TimeSource.Monotonic.markNow() + 30.seconds
    var last =
        try {
            awaitSnapshot(200.milliseconds)
        } catch (_: TimeoutCancellationException) {
            draw().render(AnsiLevel.NONE, false)
        }
    while (!until(last)) {
        check(deadline.hasNotPassedNow()) { "Timed out waiting for the screen. It shows:\n$last" }
        last =
            try {
                awaitSnapshot(200.milliseconds)
            } catch (_: TimeoutCancellationException) {
                last
            }
    }
    return last
}
