@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.csv

import com.moneymanager.csvimporter.scanImportDirectory
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.importdirectory.ImportDirectoryId
import com.moneymanager.domain.model.importdirectory.ImportDirectoryProvider
import com.moneymanager.importengineapi.EditGate
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.createImportDirectory
import com.moneymanager.importer.ImportEngineImpl
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
import kotlin.uuid.Uuid

private class FileSpec(
    val name: String,
    var content: String,
    var lastModifiedEpochMs: Long,
)

/** A controllable [ImportFileSource] over an in-memory set of files. */
private class FakeFileSource(
    private val files: List<FileSpec>,
) : ImportFileSource {
    override suspend fun list(): List<ImportFileEntry> =
        files.map { ImportFileEntry(ref = it.name, name = it.name, lastModifiedEpochMs = it.lastModifiedEpochMs) }

    override suspend fun listSubfolders(): List<ImportSubfolder> = emptyList()

    override suspend fun download(fileRef: String): ByteArray = files.first { it.name == fileRef }.content.encodeToByteArray()
}

class ImportDirectoryScannerTest : DbTest() {
    private fun engine(): ImportEngine =
        ImportEngineImpl(
            transactionRepository = repositories.transactionRepository,
            accountRepository = repositories.accountRepository,
            accountAttributeRepository = repositories.accountAttributeRepository,
            personRepository = repositories.personRepository,
            personAttributeRepository = repositories.personAttributeRepository,
            ownershipRepository = repositories.personAccountOwnershipRepository,
            categoryRepository = repositories.categoryRepository,
            currencyRepository = repositories.currencyRepository,
            attributeTypeRepository = repositories.attributeTypeRepository,
            relationshipTypeRepository = repositories.relationshipTypeRepository,
            csvImportStrategyRepository = repositories.csvImportStrategyRepository,
            apiImportStrategyRepository = repositories.apiImportStrategyRepository,
            csvAccountMappingRepository = repositories.csvAccountMappingRepository,
            csvImportRepository = repositories.csvImportRepository,
            qifImportRepository = repositories.qifImportRepository,
            apiSessionRepository = repositories.apiSessionRepository,
            settingsRepository = repositories.settingsRepository,
            importDirectoryRepository = repositories.importDirectoryRepository,
            editGate = EditGate.AlwaysWritable,
        )

    private val csvV1 =
        """
        Date,Description,Amount
        01/01/2024,Coffee,3.50
        02/01/2024,Lunch,12.00
        """.trimIndent()

    private suspend fun newDirectory(engine: ImportEngine): ImportDirectory {
        val id =
            engine.createImportDirectory(
                ImportDirectory(
                    id = ImportDirectoryId(Uuid.random()),
                    name = "Bank exports",
                    provider = ImportDirectoryProvider.LOCAL,
                    folderRef = "/fake",
                    deviceId = repositories.deviceId,
                    createdAt = Clock.System.now(),
                    updatedAt = Clock.System.now(),
                    source = Source.Manual,
                ),
            )
        return repositories.importDirectoryRepository.getDirectoryById(id).first()!!
    }

    private suspend fun scan(
        engine: ImportEngine,
        directory: ImportDirectory,
        source: ImportFileSource,
    ) = scanImportDirectory(
        directory = directory,
        fileSource = source,
        importDirectoryRepository = repositories.importDirectoryRepository,
        csvImportRepository = repositories.csvImportRepository,
        qifImportRepository = repositories.qifImportRepository,
        importEngine = engine,
    )

    @Test
    fun `scan stages a new csv then skips it when unchanged`() =
        runTest {
            val engine = engine()
            val directory = newDirectory(engine)
            val source = FakeFileSource(listOf(FileSpec("jan.csv", csvV1, 1_000)))

            val first = scan(engine, directory, source)
            assertEquals(1, first.filesDownloaded, "new file should be downloaded/staged")
            val tracked = repositories.importDirectoryRepository.getTrackedFile(directory.id, "jan.csv")
            assertNotNull(tracked?.csvImportId, "a CSV staging row should be recorded")
            assertEquals(
                1,
                repositories.csvImportRepository
                    .getAllImports()
                    .first()
                    .size,
                "file appears in the Imports section",
            )

            val second = scan(engine, directory, source)
            assertEquals(0, second.filesDownloaded, "unchanged file should not re-stage")
            assertEquals(1, second.filesUnchanged, "unchanged file should be counted")
        }

    @Test
    fun `scan skips unsupported files like pdf and stages only supported ones`() =
        runTest {
            val engine = engine()
            val directory = newDirectory(engine)
            val source =
                FakeFileSource(
                    listOf(
                        FileSpec("statement.pdf", "%PDF-1.4 not a real pdf", 1_000),
                        FileSpec("jan.csv", csvV1, 1_000),
                    ),
                )

            val result = scan(engine, directory, source)
            assertEquals(1, result.filesDownloaded, "only the csv is staged")
            assertEquals(1, result.filesSkipped, "the pdf is skipped")
            // The PDF must never have been staged (no tracking row, no import).
            assertEquals(null, repositories.importDirectoryRepository.getTrackedFile(directory.id, "statement.pdf"))
            assertEquals(
                1,
                repositories.csvImportRepository
                    .getAllImports()
                    .first()
                    .size,
            )
        }

    @Test
    fun `scan re-stages a changed csv as a new staging row`() =
        runTest {
            val engine = engine()
            val directory = newDirectory(engine)
            val file = FileSpec("jan.csv", csvV1, 1_000)
            val source = FakeFileSource(listOf(file))

            scan(engine, directory, source)
            val firstStaging = repositories.importDirectoryRepository.getTrackedFile(directory.id, "jan.csv")!!.csvImportId

            // Add a row and bump the timestamp.
            file.content = csvV1 + "\n03/01/2024,Dinner,20.00"
            file.lastModifiedEpochMs = 2_000

            val rescan = scan(engine, directory, source)
            assertEquals(1, rescan.filesDownloaded, "changed file should re-stage")

            val secondStaging = repositories.importDirectoryRepository.getTrackedFile(directory.id, "jan.csv")!!.csvImportId
            assertEquals(false, firstStaging == secondStaging, "re-stage should create a fresh staging row")
        }
}
