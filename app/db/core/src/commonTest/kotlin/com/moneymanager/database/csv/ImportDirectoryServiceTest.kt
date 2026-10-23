package com.moneymanager.database.csv

import com.moneymanager.csvimporter.BulkFileStatus
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    private fun qif(payee: String): String =
        """
        !Type:Bank
        D01/02/2024
        T-12.50
        P$payee
        ^
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
        runDbTest {
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
        runDbTest {
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
        runDbTest {
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
        runDbTest {
            val dir = directory("local")
            val factory = FakeFileSourceFactory(emptyMap())
            assertTrue(service(factory).isScannable(dir))
            assertFalse(service(factory, deviceId = DeviceId(dir.deviceId!!.id + 1)).isScannable(dir))
            assertFalse(service(factory = null).isScannable(dir), "no file access means nothing is scannable")
        }

    @Test
    fun `import all plan flags files a person must resolve`() =
        runDbTest {
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

            val reasons = plan.needsAttention.associate { it.subject to it.reason }
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
        runDbTest {
            val account = monzoAccount()
            val withAccount = directory("main", accountId = account)
            val factory =
                FakeFileSourceFactory(mapOf(withAccount.folderRef to FakeFolder(files = mapOf("main.csv" to monzoCsv("tx_1")))))
            stageLooseMonzoFile()

            val (result, tasks) = backgroundScope.runTask { service(factory).importAll(this) }

            assertEquals(1, result.csv.filesImported, "the folder's file imports onto its account")
            val file = result.csv.files.single()
            assertEquals(BulkFileStatus.IMPORTED, file.status)
            assertEquals("main.csv", file.fileName)
            assertEquals(1, file.transfersCreated)
            assertEquals(listOf("loose-monzo.csv"), result.needsAttention.map { it.subject })
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

    @Test
    fun `files from a folder without an account import onto an account named after that folder`() =
        runDbTest {
            repositories.currencyRepository.upsertCurrencyByCode("GBP", "British Pound")
            val root = directory("Statements")
            val factory =
                FakeFileSourceFactory(
                    mapOf(
                        root.folderRef to FakeFolder(subfolders = listOf("${root.folderRef}/Santander", "${root.folderRef}/Amex")),
                        "${root.folderRef}/Santander" to FakeFolder(files = mapOf("santander.qif" to qif("Coffee"))),
                        "${root.folderRef}/Amex" to
                            FakeFolder(files = mapOf("amex.qif" to qif("Lunch"), "monzo.csv" to monzoCsv("tx_amex"))),
                    ),
                )

            val (result, _) = backgroundScope.runTask { service(factory).importAll(this) }

            assertEquals(emptyList(), result.needsAttention, "every file's folder names its account")
            assertEquals(2, result.qif.filesImported)
            assertEquals(1, result.csv.filesImported)
            val accounts = repositories.accountRepository.getAllAccounts().first()
            val accountNames = accounts.map { it.name }
            assertTrue("Santander" in accountNames && "Amex" in accountNames, accountNames.toString())
            assertFalse("Statements" in accountNames, "the top-level folder holds no files, so it names no account")
            val directories = repositories.importDirectoryRepository.getAllDirectories().first()
            assertNull(directories.single { it.id == root.id }.accountId)
            for (folder in listOf("Santander", "Amex")) {
                val accountId = accounts.single { it.name == folder }.id
                assertEquals(accountId, directories.single { it.folderRef.endsWith("/$folder") }.accountId, "kept on the $folder folder")
            }
            val amexId = accounts.single { it.name == "Amex" }.id
            val amexPayees =
                repositories.transactionRepository
                    .getTransactionsByAccount(amexId)
                    .first()
                    .map { transfer -> accounts.single { it.id == transfer.targetAccountId }.name }
            assertEquals(setOf("Lunch", "Some Merchant"), amexPayees.toSet(), "the folder's QIF and CSV both land on its account")
        }

    @Test
    fun `a folder reuses an existing account of its name`() =
        runDbTest {
            val existing = monzoAccount()
            val dir = directory("Monzo Main")
            val factory = FakeFileSourceFactory(mapOf(dir.folderRef to FakeFolder(files = mapOf("nov.csv" to monzoCsv("tx_1")))))

            val (result, _) = backgroundScope.runTask { service(factory).importAll(this) }

            assertEquals(1, result.csv.filesImported)
            assertEquals(
                existing,
                repositories.importDirectoryRepository
                    .getDirectoryById(dir.id)
                    .first()
                    ?.accountId,
            )
            assertEquals(
                1,
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .count { it.name == "Monzo Main" },
            )
        }

    @Test
    fun `a subfolder still inherits the account set on its top-level folder`() =
        runDbTest {
            val account = monzoAccount()
            val root = directory("root", accountId = account)
            val factory =
                FakeFileSourceFactory(
                    mapOf(
                        root.folderRef to FakeFolder(subfolders = listOf("${root.folderRef}/2023")),
                        "${root.folderRef}/2023" to FakeFolder(files = mapOf("nov.qif" to qif("Coffee"))),
                    ),
                )

            val (result, _) = backgroundScope.runTask { service(factory).importAll(this) }

            assertEquals(1, result.qif.filesImported)
            assertEquals(
                listOf("Monzo Main"),
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .filter { it.name == "Monzo Main" || it.name == "2023" }
                    .map { it.name },
                "the set account wins over the folder's name",
            )
        }

    @Test
    fun `import all limited to some folders leaves the others alone`() =
        runDbTest {
            val account = monzoAccount()
            val picked = directory("picked", accountId = account)
            val other = directory("other", accountId = account)
            val factory =
                FakeFileSourceFactory(
                    mapOf(
                        picked.folderRef to FakeFolder(files = mapOf("picked.csv" to monzoCsv("tx_1"))),
                        other.folderRef to FakeFolder(files = mapOf("other.csv" to monzoCsv("tx_2"))),
                    ),
                )
            stageLooseMonzoFile()

            val (result, _) = backgroundScope.runTask { service(factory).importAll(this, directories = listOf(picked)) }

            assertEquals(listOf(picked.id), result.download.results.map { it.first.id }, "only the picked folder downloads")
            assertEquals(listOf("picked.csv"), result.csv.files.map { it.fileName })
            assertEquals(emptyList(), result.needsAttention, "a file from no picked folder isn't this run's concern")
        }

    @Test
    fun `import all can stop after downloading`() =
        runDbTest {
            val account = monzoAccount()
            val dir = directory("main", accountId = account)
            val factory = FakeFileSourceFactory(mapOf(dir.folderRef to FakeFolder(files = mapOf("main.csv" to monzoCsv("tx_1")))))

            val (result, _) = backgroundScope.runTask { service(factory).importAll(this, apply = false) }

            assertEquals(1, result.download.filesDownloaded)
            assertEquals(emptyList(), result.csv.files)
            assertNull(
                repositories.csvImportRepository
                    .getAllImports()
                    .first()
                    .single()
                    .lastAppliedAt,
                "the file is staged, not imported",
            )
        }

    @Test
    fun `a re-download that would need an unimport is left for a person`() =
        runDbTest {
            val account = monzoAccount()
            val dir = directory("main", accountId = account)
            val first = FakeFileSourceFactory(mapOf(dir.folderRef to FakeFolder(files = mapOf("main.csv" to monzoCsv("tx_1")))))
            backgroundScope.runTask { service(first).importAll(this) }
            // The new copy no longer has the imported row.
            val changed = FakeFileSourceFactory(mapOf(dir.folderRef to FakeFolder(files = mapOf("main.csv" to monzoCsv("tx_2")))))

            val (result, _) = backgroundScope.runTask { service(changed).importAll(this, redownloadFirst = true) }

            assertEquals(listOf("main.csv"), result.redownload?.blocked)
            assertEquals(
                listOf("main.csv" to NeedsAttentionReason.REDOWNLOAD_BLOCKED),
                result.needsAttention.map { it.subject to it.reason },
            )
        }

    @Test
    fun `import all lists card references no account owns`() =
        runDbTest {
            val dir = directory("curve")
            val curveCsv =
                """
                ,Created Date,Merchant Name,Funding Card Last 4 Digits,Merchant MCC Code,Txn Currency,Txn Amount
                1,2023-11-19,ALDI,1142,5999,GBP,31.25
                """.trimIndent()
            repositories.currencyRepository.upsertCurrencyByCode("GBP", "British Pound")
            val factory =
                FakeFileSourceFactory(mapOf(dir.folderRef to FakeFolder(files = mapOf("Transaction History 2023-11-19.csv" to curveCsv))))

            val (result, _) = backgroundScope.runTask { service(factory).importAll(this) }

            assertEquals(
                BulkFileStatus.IMPORTED,
                result.csv.files
                    .single()
                    .status,
            )
            assertEquals(listOf("1142" to NeedsAttentionReason.UNASSIGNED_CARD), result.needsAttention.map { it.subject to it.reason })
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
