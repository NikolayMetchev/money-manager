package com.moneymanager.database.csv

import com.moneymanager.csvimporter.stagePdfImport
import com.moneymanager.pdf.testing.STATEMENT_EXCERPT_PDF
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

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
        }
}
