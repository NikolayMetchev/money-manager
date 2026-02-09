@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.csv

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.AttributeColumnMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMappingId
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Tests for duplicate detection in CSV imports using unique identifier columns.
 */
class CsvDuplicateDetectionTest {
    private val testCurrencyId = CurrencyId(1L)
    private val testCurrency =
        Currency(
            id = testCurrencyId,
            code = "GBP",
            name = "British Pound",
            scaleFactor = 100,
        )

    private val testSourceAccountId = AccountId(1)
    private val testTargetAccountId = AccountId(2)

    private val columns =
        listOf(
            CsvColumn(CsvColumnId(Uuid.random()), 0, "Transaction ID"),
            CsvColumn(CsvColumnId(Uuid.random()), 1, "Date"),
            CsvColumn(CsvColumnId(Uuid.random()), 2, "Description"),
            CsvColumn(CsvColumnId(Uuid.random()), 3, "Amount"),
        )

    private fun createStrategy(attributeMappings: List<AttributeColumnMapping> = emptyList()): CsvImportStrategy {
        return CsvImportStrategy(
            id = CsvImportStrategyId(Uuid.random()),
            name = "Test Strategy",
            identificationColumns = setOf("Date", "Description", "Amount"),
            fieldMappings =
                mapOf(
                    TransferField.SOURCE_ACCOUNT to
                        HardCodedAccountMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.SOURCE_ACCOUNT,
                            accountId = testSourceAccountId,
                        ),
                    TransferField.TARGET_ACCOUNT to
                        HardCodedAccountMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.TARGET_ACCOUNT,
                            accountId = testTargetAccountId,
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
                            negateValues = true,
                        ),
                    TransferField.CURRENCY to
                        HardCodedCurrencyMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.CURRENCY,
                            currencyId = testCurrencyId,
                        ),
                    TransferField.TIMEZONE to
                        HardCodedTimezoneMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.TIMEZONE,
                            timezoneId = "UTC",
                        ),
                ),
            attributeMappings = attributeMappings,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
        )
    }

    private fun createExistingTransfer(
        transactionId: String,
        date: String,
        description: String,
        amount: String,
        attributes: List<Pair<String, String>> = emptyList(),
    ): ExistingTransferInfo {
        val transferId = TransferId(1L)
        val localDate = kotlinx.datetime.LocalDate.parse(date)
        val localTime = kotlinx.datetime.LocalTime(12, 0, 0)
        val timestamp = LocalDateTime(localDate, localTime).toInstant(TimeZone.UTC)
        val transfer =
            Transfer(
                id = transferId,
                timestamp = timestamp,
                description = description,
                sourceAccountId = testSourceAccountId,
                targetAccountId = testTargetAccountId,
                amount = Money.fromDisplayValue(BigDecimal(amount).abs(), testCurrency),
            )
        val uniqueIdValues = mapOf("Transaction ID" to transactionId)
        return ExistingTransferInfo(transferId, transfer, attributes, uniqueIdValues)
    }

    @Test
    fun testNoOverlappingTransactions() {
        // Second CSV has completely different transaction IDs
        val strategy =
            createStrategy(
                listOf(
                    AttributeColumnMapping(
                        columnName = "Transaction ID",
                        attributeTypeName = "Transaction ID",
                        isUniqueIdentifier = true,
                    ),
                ),
            )

        val existingTransfers =
            listOf(
                createExistingTransfer("TX001", "2024-01-01", "Coffee", "3.50"),
                createExistingTransfer("TX002", "2024-01-02", "Lunch", "12.00"),
            )

        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columns,
                existingAccounts = emptyMap(),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf("GBP" to testCurrency),
                existingTransfers = existingTransfers,
            )

        val rows =
            listOf(
                CsvRow(1, listOf("TX003", "03/01/2024", "Dinner", "25.00")),
                CsvRow(2, listOf("TX004", "04/01/2024", "Transport", "5.50")),
            )

        val prep = mapper.prepareImport(rows)

        assertEquals(2, prep.validTransfers.size)
        assertEquals(0, prep.errorRows.size)
        assertEquals(2, prep.statusCounts[ImportStatus.IMPORTED])
        assertEquals(null, prep.statusCounts[ImportStatus.DUPLICATE])
        assertEquals(null, prep.statusCounts[ImportStatus.UPDATED])
    }

    @Test
    fun testAllDuplicateTransactions() {
        // Second CSV has all the same transactions with identical values
        val strategy =
            createStrategy(
                listOf(
                    AttributeColumnMapping(
                        columnName = "Transaction ID",
                        attributeTypeName = "Transaction ID",
                        isUniqueIdentifier = true,
                    ),
                ),
            )

        val existingTransfers =
            listOf(
                createExistingTransfer(
                    "TX001",
                    "2024-01-01",
                    "Coffee",
                    "3.50",
                    listOf("Transaction ID" to "TX001"),
                ),
                createExistingTransfer(
                    "TX002",
                    "2024-01-02",
                    "Lunch",
                    "12.00",
                    listOf("Transaction ID" to "TX002"),
                ),
            )

        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columns,
                existingAccounts = emptyMap(),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf("GBP" to testCurrency),
                existingTransfers = existingTransfers,
            )

        val rows =
            listOf(
                CsvRow(1, listOf("TX001", "01/01/2024", "Coffee", "3.50")),
                CsvRow(2, listOf("TX002", "02/01/2024", "Lunch", "12.00")),
            )

        val prep = mapper.prepareImport(rows)

        assertEquals(2, prep.validTransfers.size)
        assertEquals(0, prep.errorRows.size)
        assertEquals(null, prep.statusCounts[ImportStatus.IMPORTED])
        assertEquals(2, prep.statusCounts[ImportStatus.DUPLICATE])
        assertEquals(null, prep.statusCounts[ImportStatus.UPDATED])

        // Verify existing transfer IDs are set
        assertEquals(existingTransfers[0].transferId, prep.validTransfers[0].existingTransferId)
        assertEquals(existingTransfers[1].transferId, prep.validTransfers[1].existingTransferId)
    }

    @Test
    fun testMixedNewDuplicateAndUpdated() {
        // Mix of new, duplicate, and updated transactions
        val strategy =
            createStrategy(
                listOf(
                    AttributeColumnMapping(
                        columnName = "Transaction ID",
                        attributeTypeName = "Transaction ID",
                        isUniqueIdentifier = true,
                    ),
                ),
            )

        val existingTransfers =
            listOf(
                createExistingTransfer(
                    "TX001",
                    "2024-01-01",
                    "Coffee",
                    "3.50",
                    listOf("Transaction ID" to "TX001"),
                ),
                createExistingTransfer(
                    "TX002",
                    "2024-01-02",
                    "Lunch",
                    "12.00",
                    listOf("Transaction ID" to "TX002"),
                ),
            )

        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columns,
                existingAccounts = emptyMap(),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf("GBP" to testCurrency),
                existingTransfers = existingTransfers,
            )

        val rows =
            listOf(
                // Duplicate
                CsvRow(1, listOf("TX001", "01/01/2024", "Coffee", "3.50")),
                // Updated description and amount
                CsvRow(2, listOf("TX002", "02/01/2024", "Lunch Updated", "15.00")),
                // New
                CsvRow(3, listOf("TX003", "03/01/2024", "Dinner", "25.00")),
            )

        val prep = mapper.prepareImport(rows)

        assertEquals(3, prep.validTransfers.size)
        assertEquals(0, prep.errorRows.size)
        assertEquals(1, prep.statusCounts[ImportStatus.IMPORTED])
        assertEquals(1, prep.statusCounts[ImportStatus.DUPLICATE])
        assertEquals(1, prep.statusCounts[ImportStatus.UPDATED])

        assertEquals(ImportStatus.DUPLICATE, prep.validTransfers[0].importStatus)
        assertEquals(ImportStatus.UPDATED, prep.validTransfers[1].importStatus)
        assertEquals(ImportStatus.IMPORTED, prep.validTransfers[2].importStatus)
    }

    @Test
    fun testUpdatedAmount() {
        // Transaction with same ID but different amount
        val strategy =
            createStrategy(
                listOf(
                    AttributeColumnMapping(
                        columnName = "Transaction ID",
                        attributeTypeName = "Transaction ID",
                        isUniqueIdentifier = true,
                    ),
                ),
            )

        val existingTransfers =
            listOf(
                createExistingTransfer(
                    "TX001",
                    "2024-01-01",
                    "Coffee",
                    "3.50",
                    listOf("Transaction ID" to "TX001"),
                ),
            )

        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columns,
                existingAccounts = emptyMap(),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf("GBP" to testCurrency),
                existingTransfers = existingTransfers,
            )

        val rows = listOf(CsvRow(1, listOf("TX001", "01/01/2024", "Coffee", "4.00")))

        val prep = mapper.prepareImport(rows)

        assertEquals(1, prep.validTransfers.size)
        assertEquals(ImportStatus.UPDATED, prep.validTransfers[0].importStatus)
        assertEquals(existingTransfers[0].transferId, prep.validTransfers[0].existingTransferId)
    }

    @Test
    fun testUpdatedDescription() {
        // Transaction with same ID but different description
        val strategy =
            createStrategy(
                listOf(
                    AttributeColumnMapping(
                        columnName = "Transaction ID",
                        attributeTypeName = "Transaction ID",
                        isUniqueIdentifier = true,
                    ),
                ),
            )

        val existingTransfers =
            listOf(
                createExistingTransfer(
                    "TX001",
                    "2024-01-01",
                    "Coffee",
                    "3.50",
                    listOf("Transaction ID" to "TX001"),
                ),
            )

        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columns,
                existingAccounts = emptyMap(),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf("GBP" to testCurrency),
                existingTransfers = existingTransfers,
            )

        val rows = listOf(CsvRow(1, listOf("TX001", "01/01/2024", "Coffee at Starbucks", "3.50")))

        val prep = mapper.prepareImport(rows)

        assertEquals(1, prep.validTransfers.size)
        assertEquals(ImportStatus.UPDATED, prep.validTransfers[0].importStatus)
    }

    @Test
    fun testUpdatedTimestamp() {
        // Transaction with same ID but different date
        val strategy =
            createStrategy(
                listOf(
                    AttributeColumnMapping(
                        columnName = "Transaction ID",
                        attributeTypeName = "Transaction ID",
                        isUniqueIdentifier = true,
                    ),
                ),
            )

        val existingTransfers =
            listOf(
                createExistingTransfer(
                    "TX001",
                    "2024-01-01",
                    "Coffee",
                    "3.50",
                    listOf("Transaction ID" to "TX001"),
                ),
            )

        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columns,
                existingAccounts = emptyMap(),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf("GBP" to testCurrency),
                existingTransfers = existingTransfers,
            )

        val rows = listOf(CsvRow(1, listOf("TX001", "02/01/2024", "Coffee", "3.50")))

        val prep = mapper.prepareImport(rows)

        assertEquals(1, prep.validTransfers.size)
        assertEquals(ImportStatus.UPDATED, prep.validTransfers[0].importStatus)
    }

    @Test
    fun testUpdatedAttributes() {
        // Transaction with same core fields but different attribute values
        val strategy =
            createStrategy(
                listOf(
                    AttributeColumnMapping(
                        columnName = "Transaction ID",
                        attributeTypeName = "Transaction ID",
                        isUniqueIdentifier = true,
                    ),
                ),
            )

        val existingTransfers =
            listOf(
                createExistingTransfer(
                    "TX001",
                    "2024-01-01",
                    "Coffee",
                    "3.50",
                    listOf("Transaction ID" to "TX001", "Category" to "Food"),
                ),
            )

        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columns,
                existingAccounts = emptyMap(),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf("GBP" to testCurrency),
                existingTransfers = existingTransfers,
            )

        val rows = listOf(CsvRow(1, listOf("TX001", "01/01/2024", "Coffee", "3.50")))

        val prep = mapper.prepareImport(rows)

        // Should detect as UPDATED because existing has "Category" attribute that new one doesn't
        assertEquals(1, prep.validTransfers.size)
        assertEquals(ImportStatus.UPDATED, prep.validTransfers[0].importStatus)
    }

    @Test
    fun testMultipleUniqueIdentifiers() {
        // Strategy with two unique identifier columns
        val columnsWithExtra =
            listOf(
                CsvColumn(CsvColumnId(Uuid.random()), 0, "Transaction ID"),
                CsvColumn(CsvColumnId(Uuid.random()), 1, "External ID"),
                CsvColumn(CsvColumnId(Uuid.random()), 2, "Date"),
                CsvColumn(CsvColumnId(Uuid.random()), 3, "Description"),
                CsvColumn(CsvColumnId(Uuid.random()), 4, "Amount"),
            )

        val strategy =
            CsvImportStrategy(
                id = CsvImportStrategyId(Uuid.random()),
                name = "Test Strategy",
                identificationColumns = setOf("Date", "Description", "Amount"),
                fieldMappings =
                    mapOf(
                        TransferField.SOURCE_ACCOUNT to
                            HardCodedAccountMapping(
                                id = FieldMappingId(Uuid.random()),
                                fieldType = TransferField.SOURCE_ACCOUNT,
                                accountId = testSourceAccountId,
                            ),
                        TransferField.TARGET_ACCOUNT to
                            HardCodedAccountMapping(
                                id = FieldMappingId(Uuid.random()),
                                fieldType = TransferField.TARGET_ACCOUNT,
                                accountId = testTargetAccountId,
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
                                negateValues = true,
                            ),
                        TransferField.CURRENCY to
                            HardCodedCurrencyMapping(
                                id = FieldMappingId(Uuid.random()),
                                fieldType = TransferField.CURRENCY,
                                currencyId = testCurrencyId,
                            ),
                        TransferField.TIMEZONE to
                            HardCodedTimezoneMapping(
                                id = FieldMappingId(Uuid.random()),
                                fieldType = TransferField.TIMEZONE,
                                timezoneId = "UTC",
                            ),
                    ),
                attributeMappings =
                    listOf(
                        AttributeColumnMapping(
                            columnName = "Transaction ID",
                            attributeTypeName = "Transaction ID",
                            isUniqueIdentifier = true,
                        ),
                        AttributeColumnMapping(
                            columnName = "External ID",
                            attributeTypeName = "External ID",
                            isUniqueIdentifier = true,
                        ),
                    ),
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now(),
            )

        val transferId = TransferId(1L)
        val localDate = kotlinx.datetime.LocalDate.parse("2024-01-01")
        val localTime = kotlinx.datetime.LocalTime(12, 0, 0)
        val timestamp = LocalDateTime(localDate, localTime).toInstant(TimeZone.UTC)
        val existingTransfers =
            listOf(
                ExistingTransferInfo(
                    transferId = transferId,
                    transfer =
                        Transfer(
                            id = transferId,
                            timestamp = timestamp,
                            description = "Coffee",
                            sourceAccountId = testSourceAccountId,
                            targetAccountId = testTargetAccountId,
                            amount = Money.fromDisplayValue(BigDecimal("3.50"), testCurrency),
                        ),
                    attributes = listOf("Transaction ID" to "TX001", "External ID" to "EXT001"),
                    uniqueIdentifierValues = mapOf("Transaction ID" to "TX001", "External ID" to "EXT001"),
                ),
            )

        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columnsWithExtra,
                existingAccounts = emptyMap(),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf("GBP" to testCurrency),
                existingTransfers = existingTransfers,
            )

        // Same transaction IDs - should be duplicate
        val rows1 = listOf(CsvRow(1, listOf("TX001", "EXT001", "01/01/2024", "Coffee", "3.50")))
        val prep1 = mapper.prepareImport(rows1)
        assertEquals(ImportStatus.DUPLICATE, prep1.validTransfers[0].importStatus)

        // Different External ID - should be new
        val rows2 = listOf(CsvRow(1, listOf("TX001", "EXT002", "01/01/2024", "Coffee", "3.50")))
        val prep2 = mapper.prepareImport(rows2)
        assertEquals(ImportStatus.IMPORTED, prep2.validTransfers[0].importStatus)
    }

    @Test
    fun testMissingUniqueIdentifierValue() {
        // Transaction with blank unique identifier should be treated as new
        val strategy =
            createStrategy(
                listOf(
                    AttributeColumnMapping(
                        columnName = "Transaction ID",
                        attributeTypeName = "Transaction ID",
                        isUniqueIdentifier = true,
                    ),
                ),
            )

        val existingTransfers =
            listOf(
                createExistingTransfer(
                    "TX001",
                    "2024-01-01",
                    "Coffee",
                    "3.50",
                    listOf("Transaction ID" to "TX001"),
                ),
            )

        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columns,
                existingAccounts = emptyMap(),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf("GBP" to testCurrency),
                existingTransfers = existingTransfers,
            )

        val rows = listOf(CsvRow(1, listOf("", "01/01/2024", "Coffee", "3.50")))

        val prep = mapper.prepareImport(rows)

        assertEquals(1, prep.validTransfers.size)
        assertEquals(ImportStatus.IMPORTED, prep.validTransfers[0].importStatus)
    }

    @Test
    fun testNoUniqueIdentifierInStrategy_detectsDuplicatesByAllFields() {
        // Without unique identifiers, should still detect duplicates by comparing all fields
        val strategy = createStrategy(emptyList())

        val existingTransfers =
            listOf(
                // No attributes on existing transfer since strategy has no attribute mappings
                createExistingTransfer(
                    "TX001",
                    "2024-01-01",
                    "Coffee",
                    "3.50",
                    emptyList(),
                ),
            )

        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columns,
                existingAccounts = emptyMap(),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf("GBP" to testCurrency),
                existingTransfers = existingTransfers,
            )

        val rows =
            listOf(
                // Exact duplicate (same date, description, amount)
                CsvRow(1, listOf("TX001", "01/01/2024", "Coffee", "3.50")),
                // New transaction
                CsvRow(2, listOf("TX002", "02/01/2024", "Lunch", "12.00")),
            )

        val prep = mapper.prepareImport(rows)

        assertEquals(2, prep.validTransfers.size)
        assertEquals(1, prep.statusCounts[ImportStatus.IMPORTED], "Should have 1 new transaction")
        assertEquals(1, prep.statusCounts[ImportStatus.DUPLICATE], "Should detect duplicate by all fields")
        assertEquals(null, prep.statusCounts[ImportStatus.UPDATED])
    }

    @Test
    fun testNoUniqueIdentifierInStrategy_detectsUpdates() {
        // Without unique identifiers, should detect updates when core fields match but attributes differ
        val strategy = createStrategy(emptyList())

        val existingTransfers =
            listOf(
                createExistingTransfer(
                    "TX001",
                    "2024-01-01",
                    "Coffee",
                    "3.50",
                    listOf("Transaction ID" to "TX001", "Category" to "Food"),
                ),
            )

        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columns,
                existingAccounts = emptyMap(),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf("GBP" to testCurrency),
                existingTransfers = existingTransfers,
            )

        val rows =
            listOf(
                // Same core fields but different attribute (missing Category)
                CsvRow(1, listOf("TX001", "01/01/2024", "Coffee", "3.50")),
            )

        val prep = mapper.prepareImport(rows)

        assertEquals(1, prep.validTransfers.size)
        assertEquals(ImportStatus.UPDATED, prep.validTransfers[0].importStatus, "Should detect update by field comparison")
    }
}
