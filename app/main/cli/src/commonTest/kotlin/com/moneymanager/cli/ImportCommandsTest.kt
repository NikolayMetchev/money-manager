package com.moneymanager.cli

import com.moneymanager.credentialvault.CredentialBundle
import com.moneymanager.credentialvault.StoredApiCredential
import com.moneymanager.credentialvault.testing.TEST_VAULT_PASSWORD
import com.moneymanager.credentialvault.testing.inMemoryCredentialVault
import com.moneymanager.database.di.DatabaseComponent
import com.moneymanager.database.di.createImportEngine
import com.moneymanager.database.di.toAppServices
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.domain.model.ImportDirectoryId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.importdirectory.ImportDirectoryProvider
import com.moneymanager.files.absolutePath
import com.moneymanager.files.writeText
import com.moneymanager.importengineapi.EditGate
import com.moneymanager.query.AccountJson
import com.moneymanager.query.Envelope
import com.moneymanager.session.apiImportService
import com.moneymanager.session.importDirectoryService
import com.moneymanager.tasks.TaskManager
import com.moneymanager.tasks.TaskScope
import com.moneymanager.test.database.MonzoApiFixtures
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.deleteTestDatabase
import com.moneymanager.test.database.installBuiltInApiStrategies
import com.moneymanager.test.database.installBuiltInCsvStrategies
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val MONZO_CSV =
    """
    Transaction ID,Date,Time,Type,Name,Emoji,Category,Amount,Currency,Local amount,Local currency,Notes and #tags,Address,Receipt,Description,Category split,Money Out,Money In
    tx_csv_1,19/11/2023,21:15:00,Faster payment,Some Merchant,,Transfers,-25.00,GBP,-25.00,GBP,,,,To Some Merchant,,,
    """.trimIndent()

private const val UNKNOWN_CSV = "Foo,Bar\n1,2"

/**
 * `mm import dirs|apis|all` end to end: a real local import folder (a Monzo export plus a file no strategy
 * matches) and a mocked Monzo API. `import all` must leave the database exactly as the services the
 * desktop app's buttons run leave it for the same data.
 */
class ImportCommandsTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val monzoApi =
        MockEngine { request ->
            val url = request.url.toString()
            respond(
                content =
                    when {
                        "/accounts" in url -> MonzoApiFixtures.ACCOUNTS_JSON
                        "before=" in url -> MonzoApiFixtures.EMPTY_TRANSACTIONS_JSON
                        else -> MonzoApiFixtures.TRANSACTIONS_JSON
                    },
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

    private class Fixture(
        val cli: TestCli,
        val db: DbLocation,
        val folder: Path,
    ) {
        suspend fun mm(vararg argv: String): MmRun =
            cli.run("--db", db.path, *argv, env = mapOf(Console.VAULT_PASSWORD_ENV to TEST_VAULT_PASSWORD))
    }

    private fun withFixture(block: suspend (Fixture) -> Unit): TestResult =
        runTest(timeout = 3.minutes) {
            val fixture = fixture()
            try {
                block(fixture)
            } finally {
                deleteTestDatabase(fixture.db)
                deleteFolder(fixture.folder)
            }
        }

    // A database with the built-in strategies, a Monzo account whose import folder holds two files, a Monzo
    // API connection, and a vault holding its token.
    private suspend fun fixture(): Fixture {
        val vault = inMemoryCredentialVault()
        val cli = TestCli(vault = vault, httpEngine = monzoApi)
        val folder = absolutePath(Path(SystemTemporaryDirectory, "mm-import-${Uuid.random()}"))
        writeText(Path(folder, "monzo.csv"), MONZO_CSV)
        writeText(Path(folder, "unknown.csv"), UNKNOWN_CSV)
        val db = createTestDatabaseLocation()
        val database = cli.databaseManager.openDatabase(db)
        try {
            val component = DatabaseComponent.create(database)
            component.installBuiltInCsvStrategies()
            component.installBuiltInApiStrategies()
            val account =
                component.accountRepository.createAccount(
                    Account(AccountId(0), name = "Monzo Main", openingDate = Instant.parse("2023-01-01T00:00:00Z")),
                    Source.Manual,
                )
            component.importDirectoryRepository.createDirectory(
                ImportDirectory(
                    id = ImportDirectoryId(Uuid.random()),
                    name = "Monzo",
                    provider = ImportDirectoryProvider.LOCAL,
                    folderRef = folder.toString(),
                    deviceId = component.deviceId,
                    accountId = account,
                    createdAt = Instant.parse("2024-01-01T00:00:00Z"),
                    updatedAt = Instant.parse("2024-01-01T00:00:00Z"),
                ),
                Source.Manual,
            )
            val monzo =
                component.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .single { it.name == "Monzo" }
            component.apiSessionRepository.ensureCredential(monzo.id, Instant.parse("2024-01-01T00:00:00Z"))
        } finally {
            database.close()
        }
        vault.bindToDatabase(db.toString())
        vault.create(checkNotNull(vault.defaultPath()), TEST_VAULT_PASSWORD)
        vault.update("test setup") {
            CredentialBundle(apiCredentials = listOf(StoredApiCredential("Monzo", token = "monzo-token", createdAtEpochMillis = 1)))
        }
        return Fixture(cli, db, folder)
    }

    private fun deleteFolder(folder: Path) {
        SystemFileSystem.list(folder).forEach { SystemFileSystem.delete(it) }
        SystemFileSystem.delete(folder)
    }

    private fun MmRun.summary(): ImportJson = json.decodeFromString<Envelope<ImportJson>>(stdout).data

    // Every account's name and balances, which is what an import must agree on (ids aren't).
    private suspend fun Fixture.balances(): List<String> {
        val run = mm("--format", "json", "accounts")
        assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
        return json
            .decodeFromString<Envelope<List<AccountJson>>>(run.stdout)
            .data
            .map { account -> "${account.name}: ${account.balances.joinToString { "${it.amount} ${it.asset}" }}" }
            .sorted()
    }

    @Test
    fun importAllImportsBothAndListsWhatNeedsAPerson() =
        withFixture { fixture ->
            val run = fixture.mm("--format", "json", "import", "all")

            assertEquals(ExitCodes.NEEDS_ATTENTION, run.exitCode, run.stderr)
            val summary = run.summary()
            val files = checkNotNull(summary.directories).files
            assertEquals(listOf("monzo.csv" to "imported"), files.map { it.file to it.status })
            assertEquals(1, files.single().transfers)
            assertEquals(listOf("noStrategy" to "unknown.csv"), summary.needsAttention.map { it.kind to it.subject })
            val apis = checkNotNull(summary.apis)
            assertEquals(listOf("Monzo" to "downloaded"), apis.connections.map { it.strategy to it.status })
            assertEquals(listOf("imported" to 1), apis.sessions.map { it.status to it.transactions })
            assertContains(run.stderr, "need attention")
        }

    @Test
    fun importAllLeavesTheSameBalancesAsTheDesktopServices() =
        withFixture { cliRun ->
            cliRun.mm("import", "all")
            val viaCli = cliRun.balances()
            assertContains(viaCli, "Monzo Main: -25 GBP", "the folder's export landed on its account: $viaCli")

            val desktop = fixture()
            try {
                runDesktopImports(desktop)
                assertEquals(viaCli, desktop.balances())
            } finally {
                deleteTestDatabase(desktop.db)
                deleteFolder(desktop.folder)
            }
        }

    // What the desktop app's "Download all" + "Import all" and "Download & import all" buttons run.
    private suspend fun runDesktopImports(fixture: Fixture) {
        val database = fixture.cli.databaseManager.openDatabase(fixture.db)
        try {
            val component = DatabaseComponent.create(database)
            val services = component.toAppServices(component.createImportEngine(EditGate.AlwaysWritable))
            val writeLock = Mutex()
            val sources = TestCli.fileSources(fixture.cli.vault)
            runTask { services.importDirectoryService(sources, writeLock).importAll(this) }
            runTask {
                services
                    .apiImportService(fixture.cli.vault, writeLock, monzoApi)
                    .downloadAndImportAll(this, services.imports.apiSessionRepository.getAllCredentials())
            }
        } finally {
            database.close()
        }
    }

    private suspend fun <T> runTask(block: suspend TaskScope.() -> T): T =
        coroutineScope {
            runTask(this, block)
        }

    private suspend fun <T> runTask(
        scope: CoroutineScope,
        block: suspend TaskScope.() -> T,
    ): T {
        val manager = TaskManager(scope)
        val result = CompletableDeferred<T>()
        manager.start("test", "Test", "") {
            try {
                block().also { result.complete(it) }.toString()
            } catch (expected: Exception) {
                result.completeExceptionally(expected)
                throw expected
            }
        }
        return result.await().also { manager.tasks.first { tasks -> tasks.none { it.isRunning } } }
    }

    @Test
    fun importDirsGolden() =
        withFixture { fixture ->
            val run = fixture.mm("--format", "json", "import", "dirs")

            assertEquals(ExitCodes.NEEDS_ATTENTION, run.exitCode, run.stderr)
            val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
            assertEquals(IMPORT_DIRS_GOLDEN, run.stdout.trim().replace(uuid, "<uuid>"))
        }

    @Test
    fun importDirsDownloadOnlyStagesWithoutImporting() =
        withFixture { fixture ->
            val run = fixture.mm("--format", "json", "import", "dirs", "--download-only")

            assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
            val dirs = checkNotNull(run.summary().directories)
            assertEquals(listOf(2), dirs.folders.map { it.downloaded })
            assertEquals(emptyList(), dirs.files)
            assertTrue(fixture.balances().none { it.startsWith("Monzo Main: -") }, "nothing was imported")
        }

    @Test
    fun importDirsWithAnUnknownFolderIsAUsageError() =
        withFixture { fixture ->
            val run = fixture.mm("import", "dirs", "--dir", "Nope")

            assertEquals(ExitCodes.USAGE, run.exitCode)
            assertContains(run.stderr, "No import directory is named 'Nope'. Known: Monzo")
        }

    @Test
    fun importApisImportsTheNewSessions() =
        withFixture { fixture ->
            val run = fixture.mm("--format", "json", "import", "apis", "--strategy", "monzo")

            assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
            val summary = run.summary()
            assertEquals(null, summary.directories)
            assertEquals(listOf("imported"), checkNotNull(summary.apis).sessions.map { it.status })
        }
}
