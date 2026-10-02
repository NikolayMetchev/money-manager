@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.csv

import com.moneymanager.csvimporter.bulkApplyCsv
import com.moneymanager.csvimporter.executeCsvUnimport
import com.moneymanager.csvimporter.planCsvUnimport
import com.moneymanager.csvimporter.redownloadImportDirectory
import com.moneymanager.csvimporter.scanImportDirectory
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.ImportDirectoryId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.importdirectory.ImportDirectoryProvider
import com.moneymanager.importengineapi.createCsvImport
import com.moneymanager.importengineapi.createImportDirectory
import com.moneymanager.importengineapi.recordDirectoryFileImported
import com.moneymanager.importfilesource.ImportFileEntry
import com.moneymanager.importfilesource.ImportFileSource
import com.moneymanager.importfilesource.ImportSubfolder
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.uuid.Uuid

/**
 * Re-downloading a folder-staged file re-parses it with the current parser and re-stages it in place:
 * a never-imported file is simply replaced, an imported one keeps each row's outcome, transaction link
 * and provenance on the row it is matched to, and an imported row the file no longer has blocks it.
 */
class CsvRedownloadE2ETest : DbTest() {
    override val installBuiltInStrategies: Boolean = true

    /** One file in a local folder whose content the test edits between steps. */
    private var fileContent = ""

    private val fileName = "AssetChangeDetails_spot_12345678_20220101_20221231_0.csv"

    private val source =
        object : ImportFileSource {
            override suspend fun list() = listOf(ImportFileEntry(ref = fileName, name = fileName, lastModifiedEpochMs = 1_000))

            override suspend fun listSubfolders(): List<ImportSubfolder> = emptyList()

            override suspend fun download(fileRef: String) = fileContent.encodeToByteArray()
        }

    private val maintenance =
        object : Maintenance {
            override suspend fun reindex() = Duration.ZERO

            override suspend fun vacuum() = Duration.ZERO

            override suspend fun analyze() = Duration.ZERO

            override suspend fun refreshMaterializedViews() = Duration.ZERO

            override suspend fun fullRefreshMaterializedViews() = Duration.ZERO
        }

    private val preamble = "UID: 12345678,Company Name: ,Country: "
    private val header = "Uid,Type,Coin,Amount,Wallet Balance,Time(UTC)"
    private val spotRows =
        listOf(
            "12345678,trade,USDT,-684.982144,0.119229,2022-01-05 13:18:18",
            "12345678,trade,KASTA,492.8,967.9244,2022-01-05 13:18:18",
            "12345678,tradingFee,KASTA,-0.4928,967.4316,2022-01-05 13:18:18",
            "12345678,userDeposit,USDT,1346,1346,2022-01-02 14:36:12",
        )

    private suspend fun directory(): ImportDirectory {
        val id =
            repositories.importEngine.createImportDirectory(
                ImportDirectory(
                    id = ImportDirectoryId(Uuid.random()),
                    name = "ByBit",
                    provider = ImportDirectoryProvider.LOCAL,
                    folderRef = "/bybit",
                    deviceId = repositories.deviceId,
                    createdAt = Clock.System.now(),
                    updatedAt = Clock.System.now(),
                    source = Source.Manual,
                ),
            )
        return assertNotNull(repositories.importDirectoryRepository.getDirectoryById(id).first())
    }

    private suspend fun scan(directory: ImportDirectory) =
        scanImportDirectory(
            directory = directory,
            fileSource = source,
            importDirectoryRepository = repositories.importDirectoryRepository,
            csvImportRepository = repositories.csvImportRepository,
            qifImportRepository = repositories.qifImportRepository,
            importEngine = repositories.importEngine,
        )

    private suspend fun stagedId(directory: ImportDirectory): CsvImportId =
        assertNotNull(repositories.importDirectoryRepository.getTrackedFile(directory.id, fileName)?.csvImportId)

    private suspend fun importAll(id: CsvImportId) =
        bulkApplyCsv(
            imports = listOf(assertNotNull(repositories.csvImportRepository.getImport(id).first())),
            sourceAccountOverride = null,
            strategies = repositories.csvImportStrategyRepository.getAllStrategies().first(),
            currencies = repositories.currencyRepository.getAllCurrencies().first(),
            accountMappingRepository = repositories.accountMappingRepository,
            accountRepository = repositories.accountRepository,
            csvImportRepository = repositories.csvImportRepository,
            maintenance = maintenance,
            importEngine = repositories.importEngine,
            onProgress = { },
            cryptoRepository = repositories.cryptoRepository,
            tradeRepository = repositories.tradeRepository,
        )

    private suspend fun redownload(
        directory: ImportDirectory,
        unimport: Boolean = false,
    ) = redownloadImportDirectory(
        directory = directory,
        fileSource = source,
        importDirectoryRepository = repositories.importDirectoryRepository,
        csvImportRepository = repositories.csvImportRepository,
        importEngine = repositories.importEngine,
        unimport =
            if (unimport) {
                { importId ->
                    executeCsvUnimport(
                        plan = planCsvUnimport(importId, repositories.csvImportRepository),
                        accountRepository = repositories.accountRepository,
                        transactionRepository = repositories.transactionRepository,
                        transferRelationshipRepository = repositories.transferRelationshipRepository,
                        tradeRepository = repositories.tradeRepository,
                        maintenance = maintenance,
                        importEngine = repositories.importEngine,
                    )
                }
            } else {
                null
            },
    )

    private suspend fun rows(id: CsvImportId) = repositories.csvImportRepository.getImportRows(id, limit = 100, offset = 0)

    @Test
    fun `a file staged by an older parser is re-parsed and then matches its strategy`() =
        runTest {
            fileContent = (listOf(preamble, header) + spotRows).joinToString("\n")
            val directory = directory()
            // What the parser produced before it skipped the preamble: the "UID: …" line as the header.
            val staleId =
                repositories.importEngine.createCsvImport(
                    fileName = fileName,
                    headers = listOf("UID: 12345678", "Company Name: ", "Country: "),
                    rows = (listOf(header) + spotRows).map { it.split(",").take(3) },
                    fileChecksum = "stale",
                    fileLastModified = Clock.System.now(),
                )
            repositories.importEngine.recordDirectoryFileImported(
                directoryId = directory.id,
                fileRef = fileName,
                fileName = fileName,
                lastModified = Clock.System.now(),
                checksum = "stale",
                csvImportId = staleId,
                importedAt = Clock.System.now(),
            )
            assertEquals(0, importAll(staleId).filesImported, "the stale columns match no strategy")

            assertEquals(listOf(fileName), redownload(directory).replaced)

            val restaged = assertNotNull(repositories.csvImportRepository.getImport(staleId).first())
            assertEquals(header.split(","), restaged.columns.sortedBy { it.columnIndex }.map { it.originalName })
            assertEquals(spotRows.size, restaged.rowCount)
            assertEquals(1, importAll(staleId).filesImported, "Bybit Spot CSV claims the re-parsed file")
            assertEquals(0, scan(directory).filesDownloaded, "the folder cursor now matches, so nothing is staged twice")
        }

    @Test
    fun `an imported file keeps its rows' outcomes and provenance when a column and a row are added`() =
        runTest {
            fileContent = (listOf(preamble, header) + spotRows).joinToString("\n")
            val directory = directory()
            scan(directory)
            val id = stagedId(directory)
            importAll(id)
            val before = rows(id).associate { it.values to (it.importStatus to it.transferId) }
            val depositTransfer = assertNotNull(rows(id).single { it.values[1] == "userDeposit" }.transferId)

            // The exchange's export gains a column, and a newer row at the top.
            fileContent =
                (
                    listOf(preamble, "$header,Note", "12345678,userDeposit,USDT,5,5,2022-02-01 09:00:00,") +
                        spotRows.map { "$it,ok" }
                ).joinToString("\n")
            val result = redownload(directory)
            assertEquals(listOf(fileName), result.replaced)
            assertEquals(listOf(fileName), result.reimportSuggested, "its columns changed under imported rows")

            val after = rows(id)
            assertEquals(null, after.first().importStatus, "the new row has never been imported")
            after.drop(1).forEach { row ->
                assertEquals(
                    before.getValue(row.values.dropLast(1)),
                    row.importStatus to row.transferId,
                    "row ${row.rowIndex} keeps its outcome",
                )
            }
            val provenance =
                repositories.transferSourceRepository
                    .getSourcesForTransaction(depositTransfer)
                    .mapNotNull { it.source as? Source.Csv }
                    .single()
            assertEquals(after.single { it.transferId == depositTransfer }.rowIndex, provenance.rowIndex, "provenance follows the row")
            assertEquals(0, scan(directory).filesDownloaded)
        }

    @Test
    fun `re-downloading a directory re-stages its files and only unimports when asked`() =
        runTest {
            fileContent = (listOf(preamble, header) + spotRows).joinToString("\n")
            val directory = directory()
            scan(directory)
            val id = stagedId(directory)
            importAll(id)
            fileContent = (listOf(preamble, header) + spotRows.dropLast(1)).joinToString("\n")

            val skipped = redownload(directory)
            assertEquals(listOf(fileName), skipped.blocked, "an imported row went missing, so the file is left alone")
            assertEquals(spotRows.size, rows(id).size)

            val forced = redownload(directory, unimport = true)
            assertEquals(listOf(fileName), forced.unimported)
            assertEquals(spotRows.size - 1, rows(id).size)
        }
}
