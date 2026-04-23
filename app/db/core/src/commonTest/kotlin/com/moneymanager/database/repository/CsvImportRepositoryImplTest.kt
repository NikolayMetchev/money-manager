@file:OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)

package com.moneymanager.database.repository

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class CsvImportRepositoryImplTest : DbTest() {
    private val headers = listOf("Date", "Amount", "Description")
    private val lastModified = Instant.fromEpochMilliseconds(1700000000000L)
    private val appliedAt = Instant.fromEpochMilliseconds(1701000000000L)
    private val appliedAtSecond = Instant.fromEpochMilliseconds(1702000000000L)
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
                    fileChecksum = "test_checksum",
                    fileLastModified = lastModified,
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
            assertEquals(0, import.applicationCount)
            assertNull(import.lastAppliedStrategyId)
            assertNull(import.lastAppliedStrategyName)
            assertNull(import.lastAppliedAt)
        }

    @Test
    fun `recordImportApplication should persist latest application metadata and count`() =
        runTest {
            val monzoStrategy = createStrategy("Monzo")
            val revolutStrategy = createStrategy("Revolut")

            val importId =
                repositories.csvImportRepository.createImport(
                    fileName = "test.csv",
                    headers = headers,
                    rows = rows,
                    fileChecksum = "applied_strategy_checksum",
                    fileLastModified = lastModified,
                )

            repositories.csvImportRepository.recordImportApplication(
                id = importId,
                strategyId = monzoStrategy.id,
                strategyName = "Monzo",
                appliedAt = appliedAt,
            )
            repositories.csvImportRepository.recordImportApplication(
                id = importId,
                strategyId = revolutStrategy.id,
                strategyName = "Revolut",
                appliedAt = appliedAtSecond,
            )

            val import = repositories.csvImportRepository.getImport(importId).first()
            assertNotNull(import)
            assertEquals(2, import.applicationCount)
            assertEquals(revolutStrategy.id, import.lastAppliedStrategyId)
            assertEquals("Revolut", import.lastAppliedStrategyName)
            assertEquals(appliedAtSecond, import.lastAppliedAt)

            val allImports = repositories.csvImportRepository.getAllImports().first()
            assertEquals(2, allImports.single().applicationCount)
            assertEquals(revolutStrategy.id, allImports.single().lastAppliedStrategyId)
            assertEquals("Revolut", allImports.single().lastAppliedStrategyName)
            assertEquals(appliedAtSecond, allImports.single().lastAppliedAt)
        }

    @Test
    fun `createImport requires checksum and lastModified`() =
        runTest {
            val checksum = "required_checksum"

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
    fun `getAllImports should include checksum and lastModified`() =
        runTest {
            val checksum = "sha256hash"

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
    fun `findImportsByChecksum should return matching import`() =
        runTest {
            val checksum = "matching_checksum"

            repositories.csvImportRepository.createImport(
                fileName = "file1.csv",
                headers = headers,
                rows = rows,
                fileChecksum = checksum,
                fileLastModified = lastModified,
            )
            repositories.csvImportRepository.createImport(
                fileName = "file3.csv",
                headers = headers,
                rows = rows,
                fileChecksum = "different_checksum",
                fileLastModified = lastModified,
            )

            val matches = repositories.csvImportRepository.findImportsByChecksum(checksum)
            assertEquals(1, matches.size)
            assertTrue(matches.all { it.fileChecksum == checksum })
        }

    @Test
    fun `createImport should reject duplicate checksum`() =
        runTest {
            val checksum = "duplicate_checksum"

            repositories.csvImportRepository.createImport(
                fileName = "file1.csv",
                headers = headers,
                rows = rows,
                fileChecksum = checksum,
                fileLastModified = lastModified,
            )

            assertFailsWith<Exception> {
                repositories.csvImportRepository.createImport(
                    fileName = "file2.csv",
                    headers = headers,
                    rows = rows,
                    fileChecksum = checksum,
                    fileLastModified = lastModified,
                )
            }

            val matches = repositories.csvImportRepository.findImportsByChecksum(checksum)
            assertEquals(1, matches.size)
            assertEquals("file1.csv", matches.first().originalFileName)
        }

    @Test
    fun `findImportsByChecksum should return empty list when no matches`() =
        runTest {
            repositories.csvImportRepository.createImport(
                fileName = "test.csv",
                headers = headers,
                rows = rows,
                fileChecksum = "some_checksum",
                fileLastModified = lastModified,
            )

            val matches = repositories.csvImportRepository.findImportsByChecksum("nonexistent")
            assertTrue(matches.isEmpty())
        }

    @Test
    fun `getImportRows should resolve placeholder transfer id from CSV source records`() =
        runTest {
            val importId =
                repositories.csvImportRepository.createImport(
                    fileName = "test.csv",
                    headers = headers,
                    rows = rows,
                    fileChecksum = "source_lookup_checksum",
                    fileLastModified = lastModified,
                )

            val currencyId = repositories.currencyRepository.upsertCurrencyByCode("GBP", "British Pound")
            val currency = repositories.currencyRepository.getCurrencyById(currencyId).first()!!

            repositories.accountRepository.createAccount(
                Account(
                    id = AccountId(0),
                    name = "Source",
                    openingDate = Clock.System.now(),
                ),
            )
            repositories.accountRepository.createAccount(
                Account(
                    id = AccountId(0),
                    name = "Target",
                    openingDate = Clock.System.now(),
                ),
            )
            val accounts = repositories.accountRepository.getAllAccounts().first()
            val sourceAccount = accounts.first { it.name == "Source" }
            val targetAccount = accounts.first { it.name == "Target" }

            val createdTransfer =
                createTransfer(
                    Transfer(
                        id = TransferId(0),
                        timestamp = Clock.System.now(),
                        description = "CSV transfer",
                        sourceAccountId = sourceAccount.id,
                        targetAccountId = targetAccount.id,
                        amount = Money.fromDisplayValue(BigDecimal("12.34"), currency),
                    ),
                )

            val createdSource =
                repositories.transferSourceRepository.getSourceByRevision(
                    transactionId = createdTransfer.id,
                    revisionId = createdTransfer.revisionId,
                )
            assertNotNull(createdSource)
            transferSourceQueries.insertCsvImportDetails(
                id = createdSource.id,
                csv_import_id = importId.id.toString(),
                csv_row_index = 1,
            )
            repositories.csvImportRepository.updateRowStatus(
                id = importId,
                rowIndex = 1,
                status = "IMPORTED",
                transferId = TransferId(0),
            )

            val importRows = repositories.csvImportRepository.getImportRows(importId, limit = 10, offset = 0)

            assertEquals(createdTransfer.id, importRows.first { it.rowIndex == 1L }.transferId)
        }

    private suspend fun createStrategy(name: String): CsvImportStrategy {
        val strategy =
            CsvImportStrategy(
                id = CsvImportStrategyId(Uuid.random()),
                name = name,
                identificationColumns = headers.toSet(),
                fieldMappings = emptyMap(),
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now(),
            )
        repositories.csvImportStrategyRepository.createStrategy(strategy)
        return strategy
    }
}
