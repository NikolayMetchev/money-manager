package com.moneymanager.database.csv

import com.moneymanager.csvimporter.scanImportDirectory
import com.moneymanager.csvimporter.stagePdfImport
import com.moneymanager.domain.model.ImportDirectoryId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.importdirectory.ImportDirectory
import com.moneymanager.domain.model.importdirectory.ImportDirectoryProvider
import com.moneymanager.importengineapi.createImportDirectory
import com.moneymanager.importfilesource.ImportFileEntry
import com.moneymanager.importfilesource.ImportFileSource
import com.moneymanager.importfilesource.ImportSubfolder
import com.moneymanager.pdf.testing.STATEMENT_EXCERPT_PDF
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

// JVM only: the other platforms' extractors arrive with their own pdfium.
class StagePdfImportTest : DbTest() {
    @Test
    fun aPickedPdfIsStagedWithItsLinesAsRawRows() =
        runDbTest {
            val id =
                repositories.importEngine.stagePdfImport(
                    fileName = "statement.pdf",
                    bytes = STATEMENT_EXCERPT_PDF,
                    fileChecksum = "checksum",
                    fileLastModified = Instant.fromEpochMilliseconds(0),
                )

            val lines = repositories.csvImportRepository.getPdfSource(id)!!.lines
            assertEquals("Statement Date 19/01/13", lines.first().text)
            val import = repositories.csvImportRepository.getImport(id).first()!!
            assertEquals("statement.pdf", import.originalFileName)
            assertEquals(lines.size, import.rowCount)
            assertEquals(setOf(id), repositories.csvImportRepository.getPdfImportIds())
        }

    @Test
    fun aPdfInAnImportFolderIsStagedTheSameWay() =
        runDbTest {
            val engine = repositories.importEngine
            val directoryId =
                engine.createImportDirectory(
                    ImportDirectory(
                        id = ImportDirectoryId(Uuid.random()),
                        name = "Statements",
                        provider = ImportDirectoryProvider.LOCAL,
                        folderRef = "/fake",
                        deviceId = repositories.deviceId,
                        createdAt = Clock.System.now(),
                        updatedAt = Clock.System.now(),
                        source = Source.Manual,
                    ),
                )
            val directory = repositories.importDirectoryRepository.getDirectoryById(directoryId).first()!!
            val folder =
                object : ImportFileSource {
                    override suspend fun list() = listOf(ImportFileEntry("statement.pdf", "statement.pdf", 1_000))

                    override suspend fun listSubfolders() = emptyList<ImportSubfolder>()

                    override suspend fun download(fileRef: String) = STATEMENT_EXCERPT_PDF
                }

            scanImportDirectory(
                directory = directory,
                fileSource = folder,
                importDirectoryRepository = repositories.importDirectoryRepository,
                csvImportRepository = repositories.csvImportRepository,
                qifImportRepository = repositories.qifImportRepository,
                importEngine = engine,
            )

            val id = repositories.importDirectoryRepository.getTrackedFile(directory.id, "statement.pdf")!!.csvImportId!!
            assertEquals(
                "Statement Date 19/01/13",
                repositories.csvImportRepository
                    .getPdfSource(id)!!
                    .lines
                    .first()
                    .text,
            )
        }
}
