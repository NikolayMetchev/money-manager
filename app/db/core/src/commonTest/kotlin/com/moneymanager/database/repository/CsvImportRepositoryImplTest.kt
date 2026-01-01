@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.repository

import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CsvImportRepositoryImplTest : DbTest() {
    @Test
    fun `createImport should create import with device info`() =
        runTest {
            val headers = listOf("Date", "Amount", "Description")
            val rows =
                listOf(
                    listOf("2024-01-01", "100.00", "Test transaction 1"),
                    listOf("2024-01-02", "200.00", "Test transaction 2"),
                )

            val importId =
                repositories.csvImportRepository.createImport(
                    fileName = "test.csv",
                    headers = headers,
                    rows = rows,
                )

            assertNotNull(importId)

            val import = repositories.csvImportRepository.getImport(importId).first()
            assertNotNull(import)
            assertEquals("test.csv", import.originalFileName)
            assertEquals(2, import.rowCount)
            assertEquals(3, import.columnCount)
            // Device info is now injected via DI, so we just verify it's not null
            assertNotNull(import.deviceInfo)
        }
}
