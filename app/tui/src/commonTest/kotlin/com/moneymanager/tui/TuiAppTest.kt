package com.moneymanager.tui

import com.jakewharton.mosaic.terminal.Terminal
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Text
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.deleteTestDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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

class TuiAppTest {
    private fun tui(
        startup: DbLocation?,
        test: suspend TestMosaic<String>.(FakeHost, TuiController) -> Unit,
    ) = tui(startup, listOf(DatabaseScreen, PlaceholderScreen), test)

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
                val scope = this
                val host = FakeHost(location)
                val controller = TuiController(host)
                runMosaicTest {
                    val running = scope.launch { controller.run() }
                    setContentAndSnapshot { TuiApp(controller, DEFAULT_SCREENS, host.vault) }
                    screen(::databaseOpen)

                    press("q")

                    withTimeout(30.seconds) { running.join() }
                    assertEquals(listOf(location), host.closed)
                }
            }
        } finally {
            deleteTestDatabase(location)
        }
    }

    @Test
    fun aCloseThatFailedWhileSwitchingFailsTheRunAfterTheLastClose() {
        val first = createTestDatabaseLocation()
        val second = createTestDatabaseLocation()
        try {
            runBlocking {
                val host = FakeHost(first, refuseClosing = first)
                val controller = TuiController(host)
                val running = async { runCatching { controller.run() } }
                withTimeout(30.seconds) { while (controller.session?.location != first) delay(10.milliseconds) }

                controller.open(second)
                withTimeout(30.seconds) { while (controller.session?.location != second) delay(10.milliseconds) }
                controller.quit()

                val failure = withTimeout(30.seconds) { running.await() }.exceptionOrNull()
                // The last close succeeded, yet the run still fails with the switch's refused upload.
                assertEquals("Didn't upload", failure?.message)
                assertEquals(listOf(first, second), host.closed)
            }
        } finally {
            deleteTestDatabase(first)
            deleteTestDatabase(second)
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
