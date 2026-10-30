package com.moneymanager.database.csv

import com.moneymanager.csvimporter.importFileChecksum
import com.moneymanager.csvimporter.stageImportFile
import com.moneymanager.domain.model.ImportFileKind
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

// JVM only: Apache POI reads workbooks nowhere else.
class StageXlsxImportTest : DbTest() {
    private val workbook =
        ByteArrayOutputStream().use { out ->
            XSSFWorkbook().use { book ->
                val first = book.createSheet("Transactions")
                listOf(listOf("Date", "Amount"), listOf("2024-01-01", "10.00")).forEachIndexed { r, values ->
                    val row = first.createRow(r)
                    values.forEachIndexed { c, value -> row.createCell(c).setCellValue(value) }
                }
                book
                    .createSheet("Other")
                    .createRow(0)
                    .createCell(0)
                    .setCellValue("ignored")
                book.write(out)
            }
            out.toByteArray()
        }

    @Test
    fun aWorkbookIsStagedFromItsFirstSheetAndKeptForRestaging() =
        runDbTest {
            val checksum = importFileChecksum(ImportFileKind.XLSX, workbook)
            val stage =
                suspend {
                    repositories.importEngine.stageImportFile(
                        kind = ImportFileKind.XLSX,
                        fileName = "book.xlsx",
                        bytes = workbook,
                        checksum = checksum,
                        lastModified = Instant.fromEpochMilliseconds(0),
                        csvImportRepository = repositories.csvImportRepository,
                    )
                }
            val id = stage()

            val import = repositories.csvImportRepository.getImport(id).first()!!
            assertEquals(listOf("Date", "Amount"), import.columns.map { it.originalName })
            assertContentEquals(
                listOf("2024-01-01", "10.00"),
                repositories.csvImportRepository
                    .getImportRows(id, limit = 10, offset = 0)
                    .single()
                    .values,
            )
            val blob = repositories.csvImportRepository.getXlsxBlob(id)!!
            assertEquals("Transactions", blob.worksheetName)
            assertTrue(blob.fileBytes.contentEquals(workbook))
            // The same bytes again are recognised by their checksum, not staged twice.
            assertEquals(id, stage())
        }
}
