package com.moneymanager.database.csv

import com.moneymanager.csvimporter.importFileChecksum
import com.moneymanager.csvimporter.planXlsxRestage
import com.moneymanager.csvimporter.stageImportFile
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.ImportFileKind
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.importengineapi.applyCsvImportMutations
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

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
                val other = book.createSheet("Other")
                other.createRow(0).createCell(0).setCellValue("Value")
                other.createRow(1).createCell(0).setCellValue("12.00")
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

    @Test
    fun worksheetRestagingCommitsTheRowsFromItsReadOnlyPlan() =
        runDbTest {
            val engine = repositories.importEngine
            val reads = repositories.csvImportRepository
            val at = Instant.fromEpochMilliseconds(0)
            val id =
                engine.stageImportFile(
                    kind = ImportFileKind.XLSX,
                    fileName = "book.xlsx",
                    bytes = workbook,
                    checksum = importFileChecksum(ImportFileKind.XLSX, workbook),
                    lastModified = at,
                    csvImportRepository = reads,
                )
            val before = assertNotNull(reads.getImport(id).first())
            val rows = reads.getImportRows(id, limit = 10, offset = 0)
            val strategy =
                CsvImportStrategy(
                    id = CsvImportStrategyId(Uuid.random()),
                    name = "Other sheet",
                    config = CsvStrategyConfig(identificationColumns = setOf("Value"), fieldMappings = emptyMap()),
                    worksheetName = "Other",
                    createdAt = at,
                    updatedAt = at,
                )
            val version = engine.writeVersion.value
            val plan = assertNotNull(planXlsxRestage(before, strategy, reads))
            assertEquals(version, engine.writeVersion.value)
            assertEquals(before, reads.getImport(id).first())
            assertEquals(rows, reads.getImportRows(id, limit = 10, offset = 0))
            assertEquals("Transactions", reads.getXlsxBlob(id)?.worksheetName)
            assertEquals(listOf(listOf("12.00")), plan.rows)

            engine.applyCsvImportMutations(listOf(plan))
            val staged = assertNotNull(reads.getImport(id).first())
            assertEquals(plan.headers, staged.columns.map { it.originalName })
            assertEquals(plan.rows, reads.getImportRows(id, limit = 10, offset = 0).map { it.values })
            assertEquals("Other", reads.getXlsxBlob(id)?.worksheetName)
            assertNull(planXlsxRestage(staged, strategy, reads))
        }
}
