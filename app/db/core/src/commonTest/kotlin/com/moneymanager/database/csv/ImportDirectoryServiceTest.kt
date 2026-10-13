@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.csv

import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.model.ImportDirectoryId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.importdirectory.ImportDirectoryProvider
import com.moneymanager.importdirectory.ImportDirectoryService
import com.moneymanager.importdirectory.NeedsAttentionReason
import com.moneymanager.importdirectory.folderDownloadTaskKey
import com.moneymanager.importdirectory.subfolderSearchTaskKey
import com.moneymanager.importengineapi.createAccount
import com.moneymanager.importengineapi.createImportDirectory
import com.moneymanager.importfilesource.ImportFileEntry
import com.moneymanager.importfilesource.ImportFileSource
import com.moneymanager.importfilesource.ImportFileSourceFactory
import com.moneymanager.importfilesource.ImportSubfolder
import com.moneymanager.tasks.TaskManager
import com.moneymanager.tasks.TaskScope
import com.moneymanager.tasks.TaskState
import com.moneymanager.tasks.childrenOf
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.upsertCurrencyByCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.uuid.Uuid

/** One folder of an in-memory tree: its files (name to content) and its subfolders' refs. */
private class FakeFolder(
    val files: Map<String, String> = emptyMap(),
    val subfolders: List<String> = emptyList(),
)

/** Serves [folders] by folder ref; a ref in [failing] throws when opened. */
private class FakeFileSourceFactory(
    private val folders: Map<String, FakeFolder>,
    private val failing: Set<String> = emptySet(),
) : ImportFileSourceFactory {
    override suspend fun create(directory: ImportDirectory): ImportFileSource {
        val ref = directory.folderRef
        if (ref in failing) error("folder $ref is unreachable")
        val folder = folders.getValue(ref)
        return object : ImportFileSource {
            override suspend fun list(): List<ImportFileEntry> =
                folder.files.keys.map { ImportFileEntry(ref = it, name = it, lastModifiedEpochMs = 1_000) }

            override suspend fun listSubfolders(): List<ImportSubfolder> =
                folder.subfolders.map { ImportSubfolder(ref = it, name = it.substringAfterLast('/')) }

            override suspend fun download(fileRef: String): ByteArray = folder.files.getValue(fileRef).encodeToByteArray()
        }
    }
}

/**
 * [ImportDirectoryService] against a real database: "Download all" with subfolder discovery and failure
 * isolation, and the CSV "import all" plan that tells files needing a person from the rest.
 */
class ImportDirectoryServiceTest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    private val now = Clock.System.now()

    private val maintenance =
        object : Maintenance {
            override suspend fun reindex(): Duration = Duration.ZERO

            override suspend fun vacuum(): Duration = Duration.ZERO

            override suspend fun analyze(): Duration = Duration.ZERO

            override suspend fun refreshMaterializedViews(): Duration = Duration.ZERO

            override suspend fun fullRefreshMaterializedViews(): Duration = Duration.ZERO
        }

    private val plainCsv =
        """
        Date,Description,Amount
        01/01/2024,Coffee,3.50
        """.trimIndent()

    private fun monzoCsv(transactionId: String): String =
        """
        Transaction ID,Date,Time,Type,Name,Emoji,Category,Amount,Currency,Local amount,Local currency,Notes and #tags,Address,Receipt,Description,Category split,Money Out,Money In
        $transactionId,19/11/2023,21:15:00,Faster payment,Some Merchant,,Transfers,-25.00,GBP,-25.00,GBP,,,,To Some Merchant,,,
        """.trimIndent()

    private fun service(
        factory: ImportFileSourceFactory?,
        deviceId: DeviceId = repositories.deviceId,
    ) = ImportDirectoryService(
        importDirectoryRepository = repositories.importDirectoryRepository,
        csvImportRepository = repositories.csvImportRepository,
        qifImportRepository = repositories.qifImportRepository,
        csvImportStrategyRepository = repositories.csvImportStrategyRepository,
        accountMappingRepository = repositories.accountMappingRepository,
        accountRepository = repositories.accountRepository,
        accountAttributeRepository = repositories.accountAttributeRepository,
        currencyRepository = repositories.currencyRepository,
        cryptoRepository = repositories.cryptoRepository,
        tradeRepository = repositories.tradeRepository,
        passThroughAccountRepository = repositories.passThroughAccountRepository,
        settingsRepository = repositories.settingsRepository,
        transactionRepository = repositories.transactionRepository,
        transferRelationshipRepository = repositories.transferRelationshipRepository,
        transferSourceRepository = repositories.transferSourceRepository,
        maintenance = maintenance,
        importEngine = repositories.importEngine,
        fileSourceFactory = factory,
        deviceId = deviceId,
    )

    private suspend fun directory(
        name: String,
        folderRef: String = "/fake/$name",
        accountId: AccountId? = null,
    ): ImportDirectory {
        val id =
            repositories.importEngine.createImportDirectory(
                ImportDirectory(
                    id = ImportDirectoryId(Uuid.random()),
                    name = name,
                    provider = ImportDirectoryProvider.LOCAL,
                    folderRef = folderRef,
                    deviceId = repositories.deviceId,
                    accountId = accountId,
                    createdAt = now,
                    updatedAt = now,
                    source = Source.Manual,
                ),
            )
        return repositories.importDirectoryRepository.getDirectoryById(id).first()!!
    }

    /** Runs [block] as a real top-level task and returns its value plus every task the run left behind. */
    private suspend fun <T> CoroutineScope.runTask(block: suspend TaskScope.() -> T): Pair<T, List<TaskState>> {
        val manager = TaskManager(this)
        val result = CompletableDeferred<T>()
        manager.start("test", "Test", "") {
            try {
                block().also { result.complete(it) }.toString()
            } catch (expected: Exception) {
                result.completeExceptionally(expected)
                throw expected
            }
        }
        val value = result.await()
        // The task's own state settles just after its block returns.
        manager.tasks.first { tasks -> tasks.none { it.isRunning } }
        return value to manager.tasks.value
    }

    @Test
    fun `download all downloads every folder and the subfolders it discovers`() =
        runTest {
            val root = directory("root")
            val factory =
                FakeFileSourceFactory(
                    mapOf(
                        "/fake/root" to FakeFolder(files = mapOf("a.csv" to plainCsv), subfolders = listOf("/fake/root/sub")),
                        "/fake/root/sub" to FakeFolder(files = mapOf("b.csv" to plainCsv.replace("Coffee", "Tea"))),
                    ),
                )

            val (result, tasks) = backgroundScope.runTask { service(factory).downloadAll(this) }

            assertEquals(1, result.created, "the subfolder with a CSV becomes a directory")
            assertEquals(2, result.filesDownloaded, "both the root's and the discovered subfolder's file are staged")
            val sub =
                repositories.importDirectoryRepository
                    .getAllDirectories()
                    .first()
                    .single { it.folderRef == "/fake/root/sub" }
            assertEquals(root.id, sub.parentId)
            assertFalse(sub.topLevel)
            val childKeys = tasks.childrenOf(tasks.single { it.parentId == null }.id).map { it.key }.toSet()
            assertEquals(
                setOf(folderDownloadTaskKey(root.id), folderDownloadTaskKey(sub.id), subfolderSearchTaskKey(root.id)),
                childKeys,
                "one child task per folder download and per subfolder search",
            )
        }

    @Test
    fun `download all keeps going when one folder fails`() =
        runTest {
            directory("good")
            directory("bad")
            val factory =
                FakeFileSourceFactory(
                    folders = mapOf("/fake/good" to FakeFolder(files = mapOf("a.csv" to plainCsv))),
                    failing = setOf("/fake/bad"),
                )

            val (result, tasks) = backgroundScope.runTask { service(factory).downloadAll(this) }

            assertEquals(1, result.filesDownloaded, "the reachable folder still downloads")
            assertTrue(
                result.folderFailures.isNotEmpty() && result.folderFailures.all { it.startsWith("bad — ") },
                result.folderFailures.toString(),
            )
            val run = tasks.single { it.parentId == null }
            assertEquals(result.issues, run.issues, "every failure is reported on the run")
        }

    @Test
    fun `a failed subfolder search does not stop a folder downloading its own files`() =
        runTest {
            val root = directory("root")
            val factory =
                FakeFileSourceFactory(
                    folders = mapOf("/fake/root" to FakeFolder(files = mapOf("a.csv" to plainCsv), subfolders = listOf("/fake/root/sub"))),
                    failing = setOf("/fake/root/sub"),
                )

            val (result, tasks) = backgroundScope.runTask { service(factory).downloadDirectory(this, root) }

            assertEquals(1, result.scan.filesDownloaded, "the folder's own file still downloads")
            assertEquals(null, result.createdSubfolders, "the failed search created nothing")
            assertTrue(
                tasks
                    .single { it.parentId == null }
                    .issues
                    .single()
                    .startsWith("root — couldn't search its subfolders: "),
            )
        }

    @Test
    fun `local folders only scan on the device that added them`() =
        runTest {
            val dir = directory("local")
            val factory = FakeFileSourceFactory(emptyMap())
            assertTrue(service(factory).isScannable(dir))
            assertFalse(service(factory, deviceId = DeviceId(dir.deviceId!!.id + 1)).isScannable(dir))
            assertFalse(service(factory = null).isScannable(dir), "no file access means nothing is scannable")
        }

    @Test
    fun `import all plan flags files a person must resolve`() =
        runTest {
            val account = monzoAccount()
            val withAccount = directory("main", accountId = account)
            val other = directory("other")
            val factory =
                FakeFileSourceFactory(
                    mapOf(
                        withAccount.folderRef to FakeFolder(files = mapOf("main.csv" to monzoCsv("tx_1"))),
                        other.folderRef to FakeFolder(files = mapOf("unknown.csv" to "Foo,Bar\n1,2")),
                    ),
                )
            backgroundScope.runTask { service(factory).downloadAll(this) }
            stageLooseMonzoFile()

            val plan = service(factory).planCsvImportAll()

            val reasons = plan.needsAttention.associate { it.fileName to it.reason }
            assertEquals(
                mapOf(
                    "loose-monzo.csv" to NeedsAttentionReason.NEEDS_SOURCE_ACCOUNT,
                    "unknown.csv" to NeedsAttentionReason.NO_STRATEGY,
                ),
                reasons,
                "a Monzo file from no folder needs a source account; the folder's file has one",
            )
            assertTrue(plan.needsSourceAccount)
            assertEquals(listOf("main.csv"), plan.unattended().files.map { it.originalFileName })
        }

    @Test
    fun `import all applies what needs no decision and reports the rest`() =
        runTest {
            val account = monzoAccount()
            val withAccount = directory("main", accountId = account)
            val factory =
                FakeFileSourceFactory(mapOf(withAccount.folderRef to FakeFolder(files = mapOf("main.csv" to monzoCsv("tx_1")))))
            stageLooseMonzoFile()

            val (result, tasks) = backgroundScope.runTask { service(factory).importAll(this) }

            assertEquals(1, result.csv.filesImported, "the folder's file imports onto its account")
            assertEquals(listOf("loose-monzo.csv"), result.needsAttention.map { it.fileName })
            val stillUnimported =
                repositories.csvImportRepository
                    .getAllImports()
                    .first()
                    .filter { it.lastAppliedAt == null }
                    .map { it.originalFileName }
            assertEquals(listOf("loose-monzo.csv"), stillUnimported, "the file needing a person stays staged")
            assertTrue(
                tasks
                    .single { it.parentId == null }
                    .issues
                    .single()
                    .startsWith("loose-monzo.csv: "),
            )
        }

    private suspend fun monzoAccount(): AccountId {
        repositories.currencyRepository.upsertCurrencyByCode("GBP", "British Pound")
        return repositories.importEngine.createAccount(Account(id = AccountId(0), name = "Monzo Main", openingDate = now), Source.Manual)
    }

    // A Monzo export staged with no import directory, so nothing says which account it belongs to.
    private suspend fun stageLooseMonzoFile() {
        val lines = monzoCsv("tx_loose").lines()
        repositories.csvImportRepository.createImport(
            fileName = "loose-monzo.csv",
            headers = lines.first().split(","),
            rows = lines.drop(1).map { it.split(",") },
            fileChecksum = "checksum-loose",
            fileLastModified = now,
        )
    }
}
