package com.moneymanager.cli

import com.moneymanager.localsettings.KEY_LAST_DATABASE
import com.moneymanager.query.Envelope
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.deleteTestDatabase
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MmCommandTest {
    @Test
    fun printsTheVersionAsJsonWhenStdoutIsRedirected() =
        runTest {
            val run = TestCli().run("version")

            assertEquals(ExitCodes.OK, run.exitCode)
            assertEquals(VersionJson("9.9.9"), Json.decodeFromString<Envelope<VersionJson>>(run.stdout).data)
        }

    @Test
    fun printsATableInATerminal() =
        runTest {
            val run = TestCli().run("version", interactive = true)

            assertEquals(ExitCodes.OK, run.exitCode)
            assertContains(run.stdout, "│ Version │ 9.9.9 ")
        }

    @Test
    fun usageErrorsExitWithTwo() =
        runTest {
            val cli = TestCli()

            assertEquals(ExitCodes.USAGE, cli.run("--bogus", "version").exitCode)
            assertEquals(ExitCodes.USAGE, cli.run("--format", "xml", "version").exitCode)
            assertEquals(ExitCodes.USAGE, cli.run().exitCode)
            assertEquals(ExitCodes.OK, cli.run("--help").exitCode)
        }

    @Test
    fun refusesToCreateADatabaseFromATypo() =
        runTest {
            val missing = createTestDatabaseLocation()
            val run = TestCli().run("--db", missing.path, "status")

            assertEquals(ExitCodes.USAGE, run.exitCode)
            assertContains(run.stderr, "No database at ${missing.path}")
        }

    @Test
    fun asksForADatabaseWhenNoneIsRemembered() =
        runTest {
            val run = TestCli().run("status")

            assertEquals(ExitCodes.USAGE, run.exitCode)
            assertContains(run.stderr, "pass --db")
        }

    @Test
    fun statusReportsTheDatabaseAndRemembersIt() =
        runTest {
            val cli = TestCli()
            val location = createTestDatabaseLocation()
            try {
                cli.databaseManager.openDatabase(location).close()

                val run = cli.run("--db", location.path, "status")

                assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
                val status = Json.decodeFromString<Envelope<StatusJson>>(run.stdout).data
                assertEquals(location.path, status.database)
                assertNull(status.cloud)
                assertEquals("no_file", status.vault.state)
                assertEquals(location.path, cli.localSettings.getString(KEY_LAST_DATABASE))

                // The next run opens the remembered database without --db.
                val again = cli.run("status")
                assertEquals(ExitCodes.OK, again.exitCode, again.stderr)
                assertEquals(location.path, Json.decodeFromString<Envelope<StatusJson>>(again.stdout).data.database)
            } finally {
                deleteTestDatabase(location)
            }
        }

    @Test
    fun tuiNeedsATerminal() =
        runTest {
            val run = TestCli().run("tui")

            assertEquals(ExitCodes.USAGE, run.exitCode)
            assertContains(run.stderr, "mm tui needs a terminal")
        }

    @Test
    fun tuiRefusesTheVaultPasswordOnStdin() =
        runTest {
            val run = TestCli().run("--vault-password-stdin", "tui")

            assertEquals(ExitCodes.USAGE, run.exitCode)
            assertContains(run.stderr, Console.VAULT_PASSWORD_ENV)
        }
}
