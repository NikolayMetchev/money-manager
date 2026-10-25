package com.moneymanager.cli

import kotlinx.coroutines.test.runTest
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
}
