package com.moneymanager.cli

import com.moneymanager.files.writeAtomically
import com.moneymanager.pdf.testing.STATEMENT_EXCERPT_PDF
import com.moneymanager.query.Envelope
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class PdfLinesCommandTest {
    @Test
    fun aMissingFileIsAUsageError() =
        runTest {
            val run = TestCli().run("pdf-lines", "no-such-statement.pdf")

            assertEquals(ExitCodes.USAGE, run.exitCode)
            assertContains(run.stderr, "No such file: no-such-statement.pdf")
        }

    @Test
    fun printsAPdfsLinesInReadingOrder() =
        runTest {
            val file = Path(SystemTemporaryDirectory, "mm-pdf-lines-${Random.nextLong().toULong()}.pdf")
            writeAtomically(file, STATEMENT_EXCERPT_PDF)
            try {
                val run = TestCli().run("pdf-lines", file.toString())

                assertEquals(ExitCodes.OK, run.exitCode, run.stderr)
                val lines = Json.decodeFromString<Envelope<List<PdfLineJson>>>(run.stdout).data
                assertEquals(listOf(1, 2, 3), lines.map { it.line })
                assertEquals("Statement Date 19/01/13", lines.first().text)
            } finally {
                SystemFileSystem.delete(file)
            }
        }
}
