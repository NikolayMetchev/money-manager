@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.csv

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.AccountLookupMapping
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMappingId
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Tests for CSV import error handling, specifically:
 * - Rows with empty Name and no fallback should still map (with placeholder account ID)
 * - When account lookup returns empty string, the transfer will fail during actual import
 * - Row filtering should skip already-processed rows (IMPORTED/DUPLICATE/UPDATED)
 * - Re-import should only process ERROR rows
 */
class CsvImportErrorHandlingTest {
    private val testCurrencyId = CurrencyId(Uuid.random())
    private val testCurrency =
        Currency(
            id = testCurrencyId,
            code = "GBP",
            name = "British Pound",
            scaleFactor = 100,
        )

    private val testSourceAccountId = AccountId(1)

    private val columns =
        listOf(
            CsvColumn(CsvColumnId(Uuid.random()), 0, "Date"),
            CsvColumn(CsvColumnId(Uuid.random()), 1, "Name"),
            CsvColumn(CsvColumnId(Uuid.random()), 2, "Amount"),
            CsvColumn(CsvColumnId(Uuid.random()), 3, "Description"),
        )

    @Test
    fun `mapRow with empty Name and no fallback should still succeed with placeholder account ID`() {
        val strategy = createStrategy(fallbackColumns = emptyList())
        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columns,
                existingAccounts = emptyMap(),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
            )

        // Row with empty Name column
        val row = CsvRow(rowIndex = 1, values = listOf("15/12/2024", "", "-50.00", "Some description"))
        val result = mapper.mapRow(row)

        // Should still return Success but with placeholder account ID and no newAccountName
        assertIs<MappingResult.Success>(result)
        // Target account should be placeholder ID (-1) since name is empty
        assertEquals(AccountId(-1), result.transfer.targetAccountId)
        // No new account should be queued for creation (name is blank)
        assertEquals(null, result.newAccountName)
    }

    @Test
    fun `mapRow with empty Name but with fallback should use fallback column`() {
        val strategy = createStrategy(fallbackColumns = listOf("Description"))
        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columns,
                existingAccounts = emptyMap(),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
            )

        // Row with empty Name column but non-empty Description
        val row = CsvRow(rowIndex = 1, values = listOf("15/12/2024", "", "-50.00", "FallbackPayee"))
        val result = mapper.mapRow(row)

        // Should return Success with new account from fallback column
        assertIs<MappingResult.Success>(result)
        // Should queue new account with name from Description column
        assertEquals("FallbackPayee", result.newAccountName)
    }

    @Test
    fun `prepareImport should correctly populate rowIndex in CsvTransferWithAttributes`() {
        val strategy = createStrategy(fallbackColumns = emptyList())
        val targetAccount =
            Account(
                id = AccountId(2),
                name = "Test Payee",
                openingDate = Clock.System.now(),
            )
        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columns,
                existingAccounts = mapOf("Test Payee" to targetAccount),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
            )

        val rows =
            listOf(
                CsvRow(rowIndex = 0, values = listOf("15/12/2024", "Test Payee", "-50.00", "Desc1")),
                CsvRow(rowIndex = 1, values = listOf("16/12/2024", "Test Payee", "-60.00", "Desc2")),
                CsvRow(rowIndex = 2, values = listOf("17/12/2024", "Test Payee", "-70.00", "Desc3")),
            )

        val prep = mapper.prepareImport(rows)

        assertEquals(3, prep.validTransfers.size)
        // Verify rowIndex is preserved
        assertEquals(0L, prep.validTransfers[0].rowIndex)
        assertEquals(1L, prep.validTransfers[1].rowIndex)
        assertEquals(2L, prep.validTransfers[2].rowIndex)
    }

    @Test
    fun `row filtering should only include ERROR or unprocessed rows`() {
        val rows =
            listOf(
                CsvRow(rowIndex = 0, values = listOf(), importStatus = ImportStatus.IMPORTED),
                CsvRow(rowIndex = 1, values = listOf(), importStatus = ImportStatus.ERROR),
                CsvRow(rowIndex = 2, values = listOf(), importStatus = ImportStatus.DUPLICATE),
                CsvRow(rowIndex = 3, values = listOf(), importStatus = null),
                CsvRow(rowIndex = 4, values = listOf(), importStatus = ImportStatus.UPDATED),
            )

        // Filter like the ApplyStrategyDialog does
        val rowsToProcess =
            rows.filter { row ->
                row.importStatus == null || row.importStatus == ImportStatus.ERROR
            }

        assertEquals(2, rowsToProcess.size)
        // Should include ERROR row
        assertNotNull(rowsToProcess.find { it.rowIndex == 1L })
        // Should include unprocessed (null status) row
        assertNotNull(rowsToProcess.find { it.rowIndex == 3L })
    }

    private fun createStrategy(fallbackColumns: List<String>): CsvImportStrategy {
        val now = Clock.System.now()
        return CsvImportStrategy(
            id = CsvImportStrategyId(Uuid.random()),
            name = "Test Strategy",
            identificationColumns = setOf("Date", "Name", "Amount"),
            fieldMappings =
                mapOf(
                    TransferField.SOURCE_ACCOUNT to
                        HardCodedAccountMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.SOURCE_ACCOUNT,
                            accountId = testSourceAccountId,
                        ),
                    TransferField.TARGET_ACCOUNT to
                        AccountLookupMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.TARGET_ACCOUNT,
                            columnName = "Name",
                            fallbackColumns = fallbackColumns,
                        ),
                    TransferField.TIMESTAMP to
                        DateTimeParsingMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.TIMESTAMP,
                            dateColumnName = "Date",
                            dateFormat = "dd/MM/yyyy",
                        ),
                    TransferField.DESCRIPTION to
                        DirectColumnMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.DESCRIPTION,
                            columnName = "Description",
                        ),
                    TransferField.AMOUNT to
                        AmountParsingMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.AMOUNT,
                            mode = AmountMode.SINGLE_COLUMN,
                            amountColumnName = "Amount",
                        ),
                    TransferField.CURRENCY to
                        HardCodedCurrencyMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.CURRENCY,
                            currencyId = testCurrencyId,
                        ),
                ),
            createdAt = now,
            updatedAt = now,
        )
    }
}
