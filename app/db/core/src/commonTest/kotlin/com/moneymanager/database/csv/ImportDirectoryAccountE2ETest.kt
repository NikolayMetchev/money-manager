@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.csv

import com.moneymanager.csvimporter.AttributeAccountMatcher
import com.moneymanager.csvimporter.CsvBulkResult
import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.csvimporter.scanImportDirectory
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.ImportDirectoryId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.importdirectory.ImportDirectoryProvider
import com.moneymanager.importengineapi.createAccount
import com.moneymanager.importengineapi.createImportDirectory
import com.moneymanager.importfilesource.ImportFileEntry
import com.moneymanager.importfilesource.ImportFileSource
import com.moneymanager.importfilesource.ImportSubfolder
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.upsertCurrencyByCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.uuid.Uuid

/** A fixed-content [ImportFileSource] serving one file, for staging a directory's downloaded CSV. */
private class SingleFileSource(
    private val fileName: String,
    private val content: String,
) : ImportFileSource {
    override suspend fun list(): List<ImportFileEntry> =
        listOf(ImportFileEntry(ref = fileName, name = fileName, lastModifiedEpochMs = 1_000))

    override suspend fun listSubfolders(): List<ImportSubfolder> = emptyList()

    override suspend fun download(fileRef: String): ByteArray = content.encodeToByteArray()
}

/**
 * End-to-end test for [ImportDirectory.accountId]: two folders of identically-formatted Monzo exports
 * (one per account) must each land its file's transfers on its own configured account, without any
 * user-chosen shared source override — the ambiguity the joint-account balance bug traced back to.
 * Also covers auto-resolving a top-level directory's account by name (existing or newly created) when
 * the user never picked one explicitly.
 */
class ImportDirectoryAccountE2ETest : DbTest() {
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

    // Transaction ID varies per call: Monzo issues a distinct id per account's own export, and a shared
    // id here would make the second file's row look like a same-source re-import of the first (a
    // Phase 2 concern, not what this directory-account resolution test is checking).
    private fun monzoCsv(transactionId: String): String =
        """
        Transaction ID,Date,Time,Type,Name,Emoji,Category,Amount,Currency,Local amount,Local currency,Notes and #tags,Address,Receipt,Description,Category split,Money Out,Money In
        $transactionId,19/11/2023,21:15:00,Faster payment,Some Merchant,,Transfers,-25.00,GBP,-25.00,GBP,,,,To Some Merchant,,,
        """.trimIndent()

    private suspend fun directory(
        name: String,
        accountId: AccountId?,
        parentId: ImportDirectoryId? = null,
        topLevel: Boolean = true,
    ): ImportDirectory {
        val id =
            repositories.importEngine.createImportDirectory(
                ImportDirectory(
                    id = ImportDirectoryId(Uuid.random()),
                    name = name,
                    provider = ImportDirectoryProvider.LOCAL,
                    folderRef = "/fake/$name",
                    deviceId = repositories.deviceId,
                    accountId = accountId,
                    topLevel = topLevel,
                    parentId = parentId,
                    createdAt = now,
                    updatedAt = now,
                    source = Source.Manual,
                ),
            )
        return repositories.importDirectoryRepository.getDirectoryById(id).first()!!
    }

    private suspend fun stageInto(
        dir: ImportDirectory,
        fileName: String,
        content: String,
    ) {
        val result =
            scanImportDirectory(
                directory = dir,
                fileSource = SingleFileSource(fileName, content),
                importDirectoryRepository = repositories.importDirectoryRepository,
                csvImportRepository = repositories.csvImportRepository,
                qifImportRepository = repositories.qifImportRepository,
                importEngine = repositories.importEngine,
            )
        assertEquals(1, result.filesDownloaded, "the file should be staged")
    }

    /** Stages a CSV import with no import-directory link at all — the "file from no directory" case. */
    private suspend fun stage(
        fileName: String,
        content: String,
    ): CsvImport {
        val lines = content.trim().lines()
        val id =
            repositories.csvImportRepository.createImport(
                fileName = fileName,
                headers = lines.first().split(","),
                rows = lines.drop(1).map { it.split(",") },
                fileChecksum = "checksum-$fileName",
                fileLastModified = now,
            )
        return repositories.csvImportRepository.getImport(id).first()!!
    }

    private suspend fun applyAllUnimported(): CsvBulkResult {
        val unimported =
            repositories.csvImportRepository
                .getAllImports()
                .first()
                .filter { it.lastAppliedAt == null }
        val directoryAccounts = repositories.importDirectoryRepository.csvImportSourceAccounts()
        val attributeMatchers = AttributeAccountMatcher.registry(repositories.accountAttributeRepository.getAll().first())
        return bulkApplyCsv(
            imports = unimported,
            sourceAccountOverride = null,
            strategies = repositories.csvImportStrategyRepository.getAllStrategies().first(),
            currencies = repositories.currencyRepository.getAllCurrencies().first(),
            accountMappingRepository = repositories.accountMappingRepository,
            accountRepository = repositories.accountRepository,
            csvImportRepository = repositories.csvImportRepository,
            maintenance = maintenance,
            importEngine = repositories.importEngine,
            onProgress = {},
            cryptoRepository = repositories.cryptoRepository,
            attributeAccountMatchers = attributeMatchers,
            directoryAccounts = directoryAccounts,
        )
    }

    @Test
    fun twoDirectoriesWithDifferentAccounts_eachFileLandsOnItsOwnAccount() =
        runTest {
            repositories.currencyRepository.upsertCurrencyByCode("GBP", "British Pound")
            val personalId =
                repositories.importEngine.createAccount(
                    Account(id = AccountId(0), name = "Monzo", openingDate = now),
                    Source.Manual,
                )
            val jointId =
                repositories.importEngine.createAccount(Account(id = AccountId(0), name = "Monzo Joint", openingDate = now), Source.Manual)

            val personalDir = directory("Monzo personal exports", accountId = personalId)
            val jointDir = directory("Monzo joint exports", accountId = jointId)

            stageInto(personalDir, "personal.csv", monzoCsv("tx_personal"))
            stageInto(jointDir, "joint.csv", monzoCsv("tx_joint"))

            val result = applyAllUnimported()
            assertEquals(2, result.filesImported)
            assertEquals(0, result.filesSkippedNoStrategy, "no run-wide source account is needed when directories supply one")

            val merchantId =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .first { it.name == "Some Merchant" }
                    .id
            val personalTransfers = repositories.transactionRepository.getTransactionsByAccount(personalId).first()
            val jointTransfers = repositories.transactionRepository.getTransactionsByAccount(jointId).first()
            assertEquals(1, personalTransfers.count { it.sourceAccountId == personalId && it.targetAccountId == merchantId })
            assertEquals(1, jointTransfers.count { it.sourceAccountId == jointId && it.targetAccountId == merchantId })
        }

    @Test
    fun discoveredSubfolderWithNoAccount_inheritsItsParents() =
        runTest {
            repositories.currencyRepository.upsertCurrencyByCode("GBP", "British Pound")
            val jointId =
                repositories.importEngine.createAccount(Account(id = AccountId(0), name = "Monzo Joint", openingDate = now), Source.Manual)

            val parent = directory("Monzo joint exports (root)", accountId = jointId)
            val child = directory("2023", accountId = null, parentId = parent.id, topLevel = false)

            stageInto(child, "nov.csv", monzoCsv("tx_child"))

            val result = applyAllUnimported()
            assertEquals(1, result.filesImported)
            assertEquals(0, result.filesSkippedNoStrategy, "the subfolder inherits its parent's account")

            val merchantId =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .first { it.name == "Some Merchant" }
                    .id
            val jointTransfers = repositories.transactionRepository.getTransactionsByAccount(jointId).first()
            assertEquals(1, jointTransfers.count { it.sourceAccountId == jointId && it.targetAccountId == merchantId })
        }

    @Test
    fun fileWithNoDirectoryAtAll_fallsBackToTheSharedOverride() =
        runTest {
            repositories.currencyRepository.upsertCurrencyByCode("GBP", "British Pound")
            val personalId =
                repositories.importEngine.createAccount(
                    Account(id = AccountId(0), name = "Monzo", openingDate = now),
                    Source.Manual,
                )
            val unimported = listOf(stage("feb.csv", monzoCsv("tx_feb")))
            val attributeMatchers = AttributeAccountMatcher.registry(repositories.accountAttributeRepository.getAll().first())
            val result =
                bulkApplyCsv(
                    imports = unimported,
                    sourceAccountOverride = personalId,
                    strategies = repositories.csvImportStrategyRepository.getAllStrategies().first(),
                    currencies = repositories.currencyRepository.getAllCurrencies().first(),
                    accountMappingRepository = repositories.accountMappingRepository,
                    accountRepository = repositories.accountRepository,
                    csvImportRepository = repositories.csvImportRepository,
                    maintenance = maintenance,
                    importEngine = repositories.importEngine,
                    onProgress = {},
                    cryptoRepository = repositories.cryptoRepository,
                    attributeAccountMatchers = attributeMatchers,
                    directoryAccounts = repositories.importDirectoryRepository.csvImportSourceAccounts(),
                )
            assertEquals(1, result.filesImported)

            val merchantId =
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .first { it.name == "Some Merchant" }
                    .id
            val personalTransfers = repositories.transactionRepository.getTransactionsByAccount(personalId).first()
            assertEquals(1, personalTransfers.count { it.sourceAccountId == personalId && it.targetAccountId == merchantId })
        }

    @Test
    fun topLevelDirectoryWithNoExplicitAccount_autoCreatesOneMatchingItsName() =
        runTest {
            repositories.currencyRepository.upsertCurrencyByCode("GBP", "British Pound")
            val dir = directory("Monzo Joint", accountId = null)
            stageInto(dir, "nov.csv", monzoCsv("tx_auto"))

            // The account is resolved (and the directory updated to point at it) as soon as it's scanned,
            // before any bulk-import run even happens.
            val resolved = repositories.importDirectoryRepository.getDirectoryById(dir.id).first()
            assertEquals(
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .first { it.name == "Monzo Joint" }
                    .id,
                resolved?.accountId,
            )

            val result = applyAllUnimported()
            assertEquals(1, result.filesImported)
            assertEquals(0, result.filesSkippedNoStrategy, "the auto-created account resolves the ambiguity")
        }

    @Test
    fun topLevelDirectoryWithNoExplicitAccount_reusesAnExistingAccountOfTheSameName() =
        runTest {
            repositories.currencyRepository.upsertCurrencyByCode("GBP", "British Pound")
            val existingId =
                repositories.importEngine.createAccount(Account(id = AccountId(0), name = "Monzo Joint", openingDate = now), Source.Manual)

            val dir = directory("Monzo Joint", accountId = null)
            stageInto(dir, "nov.csv", monzoCsv("tx_reuse"))

            val resolved = repositories.importDirectoryRepository.getDirectoryById(dir.id).first()
            assertEquals(existingId, resolved?.accountId, "the pre-existing account of the same name is reused, not duplicated")
            assertEquals(
                1,
                repositories.accountRepository
                    .getAllAccounts()
                    .first()
                    .count { it.name == "Monzo Joint" },
            )
        }
}
