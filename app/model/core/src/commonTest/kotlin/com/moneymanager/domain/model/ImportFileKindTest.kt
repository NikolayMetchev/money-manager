package com.moneymanager.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImportFileKindTest {
    @Test
    fun `reads the kind from the extension in any case`() {
        assertEquals(ImportFileKind.CSV, importFileKind("statement.csv"))
        assertEquals(ImportFileKind.QIF, importFileKind("Bank.QIF"))
        assertEquals(ImportFileKind.XLSX, importFileKind("export.2024.xlsx"))
        assertEquals(ImportFileKind.PDF, importFileKind("amex.Pdf"))
    }

    @Test
    fun `a file without a supported extension has no kind`() {
        assertNull(importFileKind("notes.txt"))
        assertNull(importFileKind("README"))
        assertNull(importFileKind("archive.csv.gz"))
    }
}
