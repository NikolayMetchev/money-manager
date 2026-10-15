package com.moneymanager.tui

import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.Terminal
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Text
import com.moneymanager.credentialvault.testing.InMemoryLocalSettings
import com.moneymanager.credentialvault.testing.inMemoryCredentialVault
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.session.DatabaseSession
import com.moneymanager.session.DatabaseSessions
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.createTestDatabaseManager
import com.moneymanager.test.database.deleteTestDatabase
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// Real time, not runTest's virtual clock: databases open on real dispatchers while the test waits for frames.
class TuiAppTest {
    private class FakeHost(
        private val startup: DbLocation?,
    ) : TuiSessionHost {
        val vault = inMemoryCredentialVault()
        private val sessions = DatabaseSessions(createTestDatabaseManager(), InMemoryLocalSettings(), vault)
        val closed = mutableListOf<DbLocation>()

        override suspend fun openStartup(): DatabaseSession? = startup?.let { sessions.open(it) }

        override suspend fun open(location: DbLocation): DatabaseSession = sessions.open(location)

        override suspend fun close(session: DatabaseSession) {
            session.database.close()
            closed += session.location
        }

        override suspend fun answerVaultRequests() = awaitCancellation()

        override fun suggestedDatabase(): String? = null
    }

    private fun tui(
        startup: DbLocation?,
        test: suspend TestMosaic<String>.(FakeHost, TuiController) -> Unit,
    ) = runBlocking {
        val host = FakeHost(startup)
        val controller = TuiController(host)
        runMosaicTest {
            // Wide enough for a whole test database path (native ones are long).
            state.size.value = Terminal.Size(columns = 240, rows = 24)
            val running = launch { controller.run() }
            setContentAndSnapshot { TuiApp(controller, DEFAULT_SCREENS + PlaceholderScreen, host.vault) }
            try {
                test(host, controller)
            } finally {
                running.cancel()
            }
        }
    }

    private suspend fun TestMosaic<String>.press(key: String) {
        val codepoint =
            when (key) {
                "Enter" -> 13
                "Escape" -> 27
                "Tab" -> 9
                else -> key.single().code
            }
        sendKeyEvent(KeyboardEvent(codepoint))
        screen()
    }

    private suspend fun TestMosaic<String>.type(text: String) = text.forEach { press(it.toString()) }

    /** The screen once it matches [until]; fails with the last screen after a while. */
    private suspend fun TestMosaic<String>.screen(until: (String) -> Boolean = { true }): String {
        val deadline = TimeSource.Monotonic.markNow() + 10.seconds
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

    @Test
    fun opensTheStartupDatabaseWithTabsAndAFooter() {
        val location = createTestDatabaseLocation()
        try {
            tui(location) { _, _ ->
                val screen = screen(::databaseOpen)

                assertContains(screen, "1 Database")
                assertContains(screen, "2 Other")
                assertContains(screen, location.path)
                assertContains(screen, "? help · q quit")
            }
        } finally {
            deleteTestDatabase(location)
        }
    }

    @Test
    fun switchesTabsAndTogglesTheHelpOverlay() {
        val location = createTestDatabaseLocation()
        try {
            tui(location) { _, _ ->
                screen(::databaseOpen)

                press("2")
                assertContains(screen { it.contains("Placeholder body") }, "Placeholder body")
                press("Tab")
                assertContains(screen { it.contains("Press o to open") }, "Press o to open")

                press("?")
                val help = screen { it.contains("Keys") }
                assertContains(help, "Go to a tab")
                assertContains(help, "Open or create another database")
                press("Escape")
                assertFalse(screen { !it.contains("Go to a tab") }.contains("Go to a tab"))
            }
        } finally {
            deleteTestDatabase(location)
        }
    }

    @Test
    fun withNoDatabaseToOpenTheChooserOpensTheTypedPath() {
        val location = createTestDatabaseLocation()
        try {
            tui(startup = null) { host, controller ->
                assertContains(screen { it.contains("Open a database") }, "Ctrl-C to quit")

                type(location.path)
                press("Enter")

                assertContains(screen(::databaseOpen), location.path)
                assertEquals(location, controller.session?.location)
                assertTrue(host.closed.isEmpty())
            }
        } finally {
            deleteTestDatabase(location)
        }
    }

    @Test
    fun quittingClosesTheOpenDatabaseAndEndsTheRun() {
        val location = createTestDatabaseLocation()
        try {
            runBlocking {
                val host = FakeHost(location)
                val controller = TuiController(host)
                runMosaicTest {
                    val running = launch { controller.run() }
                    setContentAndSnapshot { TuiApp(controller, DEFAULT_SCREENS, host.vault) }
                    screen(::databaseOpen)

                    press("q")

                    withTimeout(10.seconds) { running.join() }
                    assertEquals(listOf(location), host.closed)
                }
            }
        } finally {
            deleteTestDatabase(location)
        }
    }

    @Test
    fun aNarrowTerminalStillFitsEveryLine() {
        val location = createTestDatabaseLocation()
        try {
            tui(location) { _, _ ->
                screen(::databaseOpen)

                state.size.value = Terminal.Size(columns = 30, rows = 10)
                val narrow = screen { it.lines().size <= 10 }

                assertTrue(narrow.lines().all { it.length <= 30 }, narrow)
                press("?")
                val help = screen { it.contains("Keys") }
                assertTrue(help.lines().all { it.length <= 30 }, help)
            }
        } finally {
            deleteTestDatabase(location)
        }
    }

    private companion object {
        /** The Database tab, which only shows once the database has opened. */
        fun databaseOpen(screen: String) = screen.contains("Press o to open")

        val PlaceholderScreen =
            TuiScreen(title = "Other") { Text("Placeholder body") }
    }
}
