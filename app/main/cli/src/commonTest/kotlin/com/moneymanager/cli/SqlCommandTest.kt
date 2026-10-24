package com.moneymanager.cli

import com.moneymanager.credentialvault.testing.InMemoryLocalSettings
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.query.Envelope
import com.moneymanager.query.SqlResultJson
import com.moneymanager.remotestorage.sync.RemoteDatabaseController
import com.moneymanager.remotestorage.sync.RemoteDatabaseSyncService
import com.moneymanager.remotestorage.testing.InMemoryStorageProvider
import com.moneymanager.remotestorage.testing.SingleProviderFactory
import com.moneymanager.session.DatabaseSessions
import com.moneymanager.session.performClose
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.createTestDatabaseManager
import com.moneymanager.test.database.deleteTestDatabase
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class SqlCommandTest {
    private val cli = TestCli()

    private fun withFixture(block: suspend (DbLocation) -> Unit): TestResult =
        runTest(timeout = 2.minutes) {
            val db = cli.seedQueryFixture(installStrategies = false)
            try {
                block(db)
            } finally {
                deleteTestDatabase(db)
            }
        }

    private suspend fun DbLocation.mm(
        vararg argv: String,
        interactive: Boolean = false,
        input: List<String> = emptyList(),
    ): MmRun = cli.run("--db", path, *argv, interactive = interactive, input = input)

    private suspend fun DbLocation.sql(
        vararg argv: String,
        input: List<String> = emptyList(),
    ): SqlResultJson {
        val run = mm("sql", *argv, input = input)
        assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
        return Json.decodeFromString<Envelope<SqlResultJson>>(run.stdout).data
    }

    private suspend fun DbLocation.personCount(): Long =
        (sql("SELECT COUNT(*) AS n FROM person").rows.single().single() as JsonPrimitive).content.toLong()

    @Test
    fun sqlGolden() =
        withFixture { db ->
            val run = db.mm("sql", "SELECT id, first_name, last_name FROM person WHERE id <= 2 ORDER BY id")

            assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
            assertEquals(SQL_GOLDEN, run.stdout.trim())
        }

    @Test
    fun valuesKeepTheirTypes() =
        withFixture { db ->
            val result =
                db.sql(
                    "WITH t(a) AS (VALUES (1)) " +
                        "SELECT 9223372036854775807 AS big, 1.5 AS real, NULL AS missing, x'00ff' AS bytes, 'a;b' AS text, a, a FROM t",
                )

            assertEquals(listOf("big", "real", "missing", "bytes", "text", "a", "a:1"), result.columns)
            assertEquals(
                listOf(
                    JsonPrimitive(Long.MAX_VALUE),
                    JsonPrimitive(1.5),
                    JsonNull,
                    JsonPrimitive("00FF"),
                    JsonPrimitive("a;b"),
                    JsonPrimitive(1L),
                    JsonPrimitive(1L),
                ),
                result.rows.single(),
            )
            assertFalse(result.truncated)
        }

    @Test
    fun limitTruncatesAndSaysSo() =
        withFixture { db ->
            val values = "VALUES (1), (2), (3)"
            val limited = db.sql(values, "--limit", "2")
            assertEquals(listOf(listOf(JsonPrimitive(1L)), listOf(JsonPrimitive(2L))), limited.rows)
            assertTrue(limited.truncated)

            assertEquals(3, db.sql(values, "--limit", "0").rows.size)
            assertFalse(db.sql(values, "--limit", "3").truncated)

            val table = db.mm("sql", values, "--limit", "1", interactive = true)
            assertEquals(ExitCodes.OK, table.exitCode, table.stderr)
            assertContains(table.stderr, "Stopped at 1 rows")
        }

    @Test
    fun theStatementCanComeFromStdin() =
        withFixture { db ->
            val result = db.sql(input = listOf("SELECT first_name", "FROM person", "WHERE id = 1;"))
            assertEquals(listOf(listOf(JsonPrimitive("Alice"))), result.rows)
            assertEquals(result, db.sql("-", input = listOf("SELECT first_name FROM person WHERE id = 1")))
        }

    @Test
    fun anythingButOneSelectIsAUsageErrorAndChangesNothing() =
        withFixture { db ->
            val before = db.personCount()
            listOf(
                "DELETE FROM person",
                "UPDATE person SET first_name = 'x'",
                "INSERT INTO person (first_name) VALUES ('x')",
                "DROP TABLE person",
                "PRAGMA user_version = 5",
                "ATTACH DATABASE ':memory:' AS other",
                "SELECT 1; DELETE FROM person",
                "COMMIT",
                "SELECT * FROM no_such_table",
            ).forEach { statement ->
                val run = db.mm("sql", statement)
                assertEquals(ExitCodes.USAGE, run.exitCode, "$statement: ${run.stderr}")
            }
            assertContains(db.mm("sql", "SELECT 1; DELETE FROM person").stderr, "Only one statement")
            assertContains(db.mm("sql", "DELETE FROM person").stderr, "only a single SELECT is allowed")

            assertEquals(before, db.personCount())
        }

    @Test
    fun aCloudDatabaseRestoredForTheQueryIsDroppedAgain() =
        runTest(timeout = 2.minutes) {
            val manager = createTestDatabaseManager()
            val settings = InMemoryLocalSettings()
            val provider = InMemoryStorageProvider()
            val controller = RemoteDatabaseController(RemoteDatabaseSyncService(manager, settings), SingleProviderFactory(provider))
            val cloud = TestCli(manager, settings, remoteController = controller)
            val location = createTestDatabaseLocation()
            try {
                // A cloud database whose local copy was dropped on close: only the encrypted archive is left.
                val session = DatabaseSessions(manager, settings, cloud.vault, controller).open(location)
                controller.createRemote(provider.id, null, "db.mmdb", location, session.database, "pw")
                controller.performClose(session.database, upload = false, keepLocal = false, password = null, localChanged = false)
                assertFalse(manager.databaseExists(location))
                val env = mapOf(Console.REMOTE_PASSWORD_ENV to "pw")

                val run = cloud.run("sql", "SELECT COUNT(*) FROM person", env = env)
                assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
                assertFalse(manager.databaseExists(location))

                val kept = cloud.run("--keep-local", "sql", "SELECT 1", env = env)
                assertEquals(ExitCodes.OK, kept.exitCode, kept.stderr)
                assertTrue(manager.databaseExists(location))
            } finally {
                deleteTestDatabase(location)
            }
        }

    @Test
    fun tableForPeople() =
        withFixture { db ->
            val run = db.mm("sql", "SELECT id, last_name FROM person WHERE id = 2", interactive = true)

            assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
            assertContains(run.stdout, "│ 2  │ NULL      │")
        }
}

private val SQL_GOLDEN =
    """
    {
        "schemaVersion": 1,
        "data": {
            "columns": [
                "id",
                "first_name",
                "last_name"
            ],
            "rows": [
                [
                    1,
                    "Alice",
                    "Smith"
                ],
                [
                    2,
                    "Bob",
                    null
                ]
            ],
            "truncated": false
        }
    }
    """.trimIndent()
