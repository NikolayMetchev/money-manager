@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class CsvImportRepositoryImplTest : DbTest() {
    private val headers = listOf("Date", "Amount", "Description")
    private val rows =
        listOf(
            listOf("2024-01-01", "100.00", "Test transaction 1"),
            listOf("2024-01-02", "200.00", "Test transaction 2"),
        )

    @Test
    fun `createImport should create import with device info`() =
        runTest {
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

    @Test
    fun `createImport should persist checksum and lastModified`() =
        runTest {
            val checksum = "abc123def456"
            val lastModified = Instant.fromEpochMilliseconds(1700000000000L)

            val importId =
                repositories.csvImportRepository.createImport(
                    fileName = "test.csv",
                    headers = headers,
                    rows = rows,
                    fileChecksum = checksum,
                    fileLastModified = lastModified,
                )

            val import = repositories.csvImportRepository.getImport(importId).first()
            assertNotNull(import)
            assertEquals(checksum, import.fileChecksum)
            assertEquals(lastModified, import.fileLastModified)
        }

    @Test
    fun `createImport without checksum stores nulls`() =
        runTest {
            val importId =
                repositories.csvImportRepository.createImport(
                    fileName = "test.csv",
                    headers = headers,
                    rows = rows,
                )

            val import = repositories.csvImportRepository.getImport(importId).first()
            assertNotNull(import)
            assertNull(import.fileChecksum)
            assertNull(import.fileLastModified)
        }

    @Test
    fun `getAllImports should include checksum and lastModified`() =
        runTest {
            val checksum = "sha256hash"
            val lastModified = Instant.fromEpochMilliseconds(1700000000000L)

            repositories.csvImportRepository.createImport(
                fileName = "test.csv",
                headers = headers,
                rows = rows,
                fileChecksum = checksum,
                fileLastModified = lastModified,
            )

            val imports = repositories.csvImportRepository.getAllImports().first()
            assertEquals(1, imports.size)
            assertEquals(checksum, imports[0].fileChecksum)
            assertEquals(lastModified, imports[0].fileLastModified)
        }

    @Test
    fun `findImportsByChecksum should return matching imports`() =
        runTest {
            val checksum = "matching_checksum"

            repositories.csvImportRepository.createImport(
                fileName = "file1.csv",
                headers = headers,
                rows = rows,
                fileChecksum = checksum,
            )
            repositories.csvImportRepository.createImport(
                fileName = "file2.csv",
                headers = headers,
                rows = rows,
                fileChecksum = checksum,
            )
            repositories.csvImportRepository.createImport(
                fileName = "file3.csv",
                headers = headers,
                rows = rows,
                fileChecksum = "different_checksum",
            )

            val matches = repositories.csvImportRepository.findImportsByChecksum(checksum)
            assertEquals(2, matches.size)
            assertTrue(matches.all { it.fileChecksum == checksum })
        }

    @Test
    fun `findImportsByChecksum should return empty list when no matches`() =
        runTest {
            repositories.csvImportRepository.createImport(
                fileName = "test.csv",
                headers = headers,
                rows = rows,
                fileChecksum = "some_checksum",
            )

            val matches = repositories.csvImportRepository.findImportsByChecksum("nonexistent")
            assertTrue(matches.isEmpty())
        }
}
