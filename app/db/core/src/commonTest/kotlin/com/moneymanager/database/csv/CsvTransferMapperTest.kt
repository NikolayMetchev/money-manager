@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.csv

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csvstrategy.AccountLookupMapping
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategyId
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMappingId
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedCurrencyMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedTimezoneMapping
import com.moneymanager.domain.model.csvstrategy.RegexAccountMapping
import com.moneymanager.domain.model.csvstrategy.RegexRule
import com.moneymanager.domain.model.csvstrategy.TimezoneLookupMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

class CsvTransferMapperTest {
    private val testCurrencyId = CurrencyId(Uuid.random())
    private val testCurrency =
        Currency(
            id = testCurrencyId,
            code = "GBP",
            name = "British Pound",
            scaleFactor = 100,
        )

    private val testSourceAccountId = AccountId(1)

    private val testTargetAccountId = AccountId(2)
    private val testTargetAccount =
        Account(
            id = testTargetAccountId,
            name = "Payee Account",
            openingDate = Clock.System.now(),
        )

    private val columns =
        listOf(
            CsvColumn(CsvColumnId(Uuid.random()), 0, "Date"),
            CsvColumn(CsvColumnId(Uuid.random()), 1, "Description"),
            CsvColumn(CsvColumnId(Uuid.random()), 2, "Amount"),
            CsvColumn(CsvColumnId(Uuid.random()), 3, "Payee"),
        )

    private fun createStrategy(
        flipAccountsOnPositive: Boolean = false,
        negateValues: Boolean = false,
    ): CsvImportStrategy {
        val now = Clock.System.now()
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
                        AccountLookupMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.TARGET_ACCOUNT,
                            columnName = "Payee",
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
                            flipAccountsOnPositive = flipAccountsOnPositive,
                            negateValues = negateValues,
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

    @Test
    fun `mapRow successfully maps valid row with existing account`() {
        val strategy = createStrategy()
        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columns,
                existingAccounts = mapOf("Payee Account" to testTargetAccount),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
            )

        val row = CsvRow(rowIndex = 1, values = listOf("15/12/2024", "Test payment", "-50.00", "Payee Account"))
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals("Test payment", result.transfer.description)
        assertEquals(testSourceAccountId, result.transfer.sourceAccountId)
        assertEquals(testTargetAccountId, result.transfer.targetAccountId)
        assertEquals(5000L, result.transfer.amount.amount)
    }

    @Test
    fun `mapRow identifies new account when account not found`() {
        val strategy = createStrategy()
        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columns,
                existingAccounts = emptyMap(),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
            )

        val row = CsvRow(rowIndex = 1, values = listOf("15/12/2024", "Test payment", "-50.00", "New Payee"))
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals("New Payee", result.newAccountName)
    }

    @Test
    fun `mapRow returns error for invalid date format`() {
        val strategy = createStrategy()
        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columns,
                existingAccounts = mapOf("Payee Account" to testTargetAccount),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
            )

        val row = CsvRow(rowIndex = 1, values = listOf("invalid-date", "Test payment", "-50.00", "Payee Account"))
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Error>(result)
        assertEquals(1L, result.rowIndex)
    }

    @Test
    fun `mapRow returns error when currency not found`() {
        val strategy = createStrategy()
        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columns,
                existingAccounts = mapOf("Payee Account" to testTargetAccount),
                existingCurrencies = emptyMap(),
                existingCurrenciesByCode = emptyMap(),
            )

        val row = CsvRow(rowIndex = 1, values = listOf("15/12/2024", "Test payment", "-50.00", "Payee Account"))
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Error>(result)
        assertTrue(result.errorMessage.contains("Currency"))
    }

    @Test
    fun `mapRow flips accounts when amount is positive and flipAccountsOnPositive is true`() {
        val strategy = createStrategy(flipAccountsOnPositive = true)
        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columns,
                existingAccounts = mapOf("Payee Account" to testTargetAccount),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
            )

        val row = CsvRow(rowIndex = 1, values = listOf("15/12/2024", "Incoming payment", "100.00", "Payee Account"))
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals(testTargetAccountId, result.transfer.sourceAccountId)
        assertEquals(testSourceAccountId, result.transfer.targetAccountId)
    }

    @Test
    fun `mapRow does not flip accounts when amount is negative`() {
        val strategy = createStrategy(flipAccountsOnPositive = true)
        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columns,
                existingAccounts = mapOf("Payee Account" to testTargetAccount),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
            )

        val row = CsvRow(rowIndex = 1, values = listOf("15/12/2024", "Outgoing payment", "-100.00", "Payee Account"))
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals(testSourceAccountId, result.transfer.sourceAccountId)
        assertEquals(testTargetAccountId, result.transfer.targetAccountId)
    }

    @Test
    fun `prepareImport collects valid transfers and errors`() {
        val strategy = createStrategy()
        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columns,
                existingAccounts = mapOf("Payee Account" to testTargetAccount),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
            )

        val rows =
            listOf(
                CsvRow(rowIndex = 1, values = listOf("15/12/2024", "Valid payment", "-50.00", "Payee Account")),
                CsvRow(rowIndex = 2, values = listOf("invalid-date", "Invalid payment", "-25.00", "Payee Account")),
                CsvRow(rowIndex = 3, values = listOf("16/12/2024", "Another valid", "-75.00", "Payee Account")),
            )

        val preparation = mapper.prepareImport(rows)

        assertEquals(2, preparation.validTransfers.size)
        assertEquals(1, preparation.errorRows.size)
        assertEquals(2L, preparation.errorRows[0].rowIndex)
    }

    @Test
    fun `prepareImport collects new accounts to create`() {
        val strategy = createStrategy()
        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columns,
                existingAccounts = mapOf("Existing Payee" to testTargetAccount),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
            )

        val rows =
            listOf(
                CsvRow(rowIndex = 1, values = listOf("15/12/2024", "Payment 1", "-50.00", "Existing Payee")),
                CsvRow(rowIndex = 2, values = listOf("16/12/2024", "Payment 2", "-25.00", "New Payee 1")),
                CsvRow(rowIndex = 3, values = listOf("17/12/2024", "Payment 3", "-75.00", "New Payee 2")),
                CsvRow(rowIndex = 4, values = listOf("18/12/2024", "Payment 4", "-30.00", "New Payee 1")),
            )

        val preparation = mapper.prepareImport(rows)

        assertEquals(4, preparation.validTransfers.size)
        assertEquals(2, preparation.newAccounts.size)
        assertTrue(preparation.newAccounts.any { it.name == "New Payee 1" })
        assertTrue(preparation.newAccounts.any { it.name == "New Payee 2" })
    }

    @Test
    fun `mapRow handles amounts with currency symbols`() {
        val strategy = createStrategy()
        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columns,
                existingAccounts = mapOf("Payee Account" to testTargetAccount),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
            )

        val row = CsvRow(rowIndex = 1, values = listOf("15/12/2024", "Test payment", "£50.00", "Payee Account"))
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals(5000L, result.transfer.amount.amount)
    }

    @Test
    fun `mapRow handles amounts with thousand separators`() {
        val strategy = createStrategy()
        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columns,
                existingAccounts = mapOf("Payee Account" to testTargetAccount),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
            )

        val row = CsvRow(rowIndex = 1, values = listOf("15/12/2024", "Large payment", "1,234.00", "Payee Account"))
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals(123400L, result.transfer.amount.amount)
    }

    @Test
    fun `mapRow with CurrencyLookupMapping parses currency from column`() {
        val columnsWithCurrency =
            listOf(
                CsvColumn(CsvColumnId(Uuid.random()), 0, "Date"),
                CsvColumn(CsvColumnId(Uuid.random()), 1, "Description"),
                CsvColumn(CsvColumnId(Uuid.random()), 2, "Amount"),
                CsvColumn(CsvColumnId(Uuid.random()), 3, "Payee"),
                CsvColumn(CsvColumnId(Uuid.random()), 4, "Currency"),
            )

        val now = Clock.System.now()
        val strategyWithCurrencyColumn =
            CsvImportStrategy(
                id = CsvImportStrategyId(Uuid.random()),
                name = "Test Strategy With Currency Column",
                identificationColumns = setOf("Date", "Description", "Amount", "Currency"),
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
                                columnName = "Payee",
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
                            CurrencyLookupMapping(
                                id = FieldMappingId(Uuid.random()),
                                fieldType = TransferField.CURRENCY,
                                columnName = "Currency",
                            ),
                    ),
                createdAt = now,
                updatedAt = now,
            )

        val mapper =
            CsvTransferMapper(
                strategy = strategyWithCurrencyColumn,
                columns = columnsWithCurrency,
                existingAccounts = mapOf("Payee Account" to testTargetAccount),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
            )

        val row =
            CsvRow(rowIndex = 1, values = listOf("15/12/2024", "Test payment", "-50.00", "Payee Account", "GBP"))
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals("Test payment", result.transfer.description)
        assertEquals(testCurrencyId, result.transfer.amount.currency.id)
        assertEquals(5000L, result.transfer.amount.amount)
    }

    @Test
    fun `mapRow with CurrencyLookupMapping returns error when currency code not found`() {
        val columnsWithCurrency =
            listOf(
                CsvColumn(CsvColumnId(Uuid.random()), 0, "Date"),
                CsvColumn(CsvColumnId(Uuid.random()), 1, "Description"),
                CsvColumn(CsvColumnId(Uuid.random()), 2, "Amount"),
                CsvColumn(CsvColumnId(Uuid.random()), 3, "Payee"),
                CsvColumn(CsvColumnId(Uuid.random()), 4, "Currency"),
            )

        val now = Clock.System.now()
        val strategyWithCurrencyColumn =
            CsvImportStrategy(
                id = CsvImportStrategyId(Uuid.random()),
                name = "Test Strategy With Currency Column",
                identificationColumns = setOf("Date", "Description", "Amount", "Currency"),
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
                                columnName = "Payee",
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
                            CurrencyLookupMapping(
                                id = FieldMappingId(Uuid.random()),
                                fieldType = TransferField.CURRENCY,
                                columnName = "Currency",
                            ),
                    ),
                createdAt = now,
                updatedAt = now,
            )

        val mapper =
            CsvTransferMapper(
                strategy = strategyWithCurrencyColumn,
                columns = columnsWithCurrency,
                existingAccounts = mapOf("Payee Account" to testTargetAccount),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
            )

        // Use a currency code that doesn't exist in the map
        val row =
            CsvRow(rowIndex = 1, values = listOf("15/12/2024", "Test payment", "-50.00", "Payee Account", "USD"))
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Error>(result)
        assertTrue(result.errorMessage.contains("Currency"))
    }

    @Test
    fun `mapRow with HardCodedTimezoneMapping uses specified timezone`() {
        val now = Clock.System.now()
        val strategyWithTimezone =
            CsvImportStrategy(
                id = CsvImportStrategyId(Uuid.random()),
                name = "Test Strategy With Timezone",
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
                            AccountLookupMapping(
                                id = FieldMappingId(Uuid.random()),
                                fieldType = TransferField.TARGET_ACCOUNT,
                                columnName = "Payee",
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
                        TransferField.TIMEZONE to
                            HardCodedTimezoneMapping(
                                id = FieldMappingId(Uuid.random()),
                                fieldType = TransferField.TIMEZONE,
                                timezoneId = "UTC",
                            ),
                    ),
                createdAt = now,
                updatedAt = now,
            )

        val mapper =
            CsvTransferMapper(
                strategy = strategyWithTimezone,
                columns = columns,
                existingAccounts = mapOf("Payee Account" to testTargetAccount),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
            )

        val row = CsvRow(rowIndex = 1, values = listOf("15/12/2024", "Test payment", "-50.00", "Payee Account"))
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        // December 15, 2024 at 12:00 UTC should be 2024-12-15T12:00:00Z
        val expected =
            LocalDateTime(2024, 12, 15, 12, 0, 0)
                .toInstant(TimeZone.UTC)
        assertEquals(expected, result.transfer.timestamp)
    }

    @Test
    fun `mapRow with TimezoneLookupMapping reads timezone from column`() {
        val columnsWithTimezone =
            listOf(
                CsvColumn(CsvColumnId(Uuid.random()), 0, "Date"),
                CsvColumn(CsvColumnId(Uuid.random()), 1, "Description"),
                CsvColumn(CsvColumnId(Uuid.random()), 2, "Amount"),
                CsvColumn(CsvColumnId(Uuid.random()), 3, "Payee"),
                CsvColumn(CsvColumnId(Uuid.random()), 4, "Timezone"),
            )

        val now = Clock.System.now()
        val strategyWithTimezoneColumn =
            CsvImportStrategy(
                id = CsvImportStrategyId(Uuid.random()),
                name = "Test Strategy With Timezone Column",
                identificationColumns = setOf("Date", "Description", "Amount", "Timezone"),
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
                                columnName = "Payee",
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
                        TransferField.TIMEZONE to
                            TimezoneLookupMapping(
                                id = FieldMappingId(Uuid.random()),
                                fieldType = TransferField.TIMEZONE,
                                columnName = "Timezone",
                            ),
                    ),
                createdAt = now,
                updatedAt = now,
            )

        val mapper =
            CsvTransferMapper(
                strategy = strategyWithTimezoneColumn,
                columns = columnsWithTimezone,
                existingAccounts = mapOf("Payee Account" to testTargetAccount),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
            )

        val row =
            CsvRow(
                rowIndex = 1,
                values = listOf("15/12/2024", "Test payment", "-50.00", "Payee Account", "America/New_York"),
            )
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        // December 15, 2024 at 12:00 in America/New_York timezone
        val expected =
            LocalDateTime(2024, 12, 15, 12, 0, 0)
                .toInstant(TimeZone.of("America/New_York"))
        assertEquals(expected, result.transfer.timestamp)
    }

    @Test
    fun `mapRow produces different timestamps for different timezones`() {
        val now = Clock.System.now()

        fun createStrategyWithTimezone(timezoneId: String): CsvImportStrategy =
            CsvImportStrategy(
                id = CsvImportStrategyId(Uuid.random()),
                name = "Test Strategy With $timezoneId",
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
                            AccountLookupMapping(
                                id = FieldMappingId(Uuid.random()),
                                fieldType = TransferField.TARGET_ACCOUNT,
                                columnName = "Payee",
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
                        TransferField.TIMEZONE to
                            HardCodedTimezoneMapping(
                                id = FieldMappingId(Uuid.random()),
                                fieldType = TransferField.TIMEZONE,
                                timezoneId = timezoneId,
                            ),
                    ),
                createdAt = now,
                updatedAt = now,
            )

        val utcMapper =
            CsvTransferMapper(
                strategy = createStrategyWithTimezone("UTC"),
                columns = columns,
                existingAccounts = mapOf("Payee Account" to testTargetAccount),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
            )

        val londonMapper =
            CsvTransferMapper(
                strategy = createStrategyWithTimezone("Europe/London"),
                columns = columns,
                existingAccounts = mapOf("Payee Account" to testTargetAccount),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
            )

        // June 15 is during BST (British Summer Time), so London is UTC+1
        val row = CsvRow(rowIndex = 1, values = listOf("15/06/2024", "Test payment", "-50.00", "Payee Account"))

        val utcResult = utcMapper.mapRow(row)
        val londonResult = londonMapper.mapRow(row)

        assertIs<MappingResult.Success>(utcResult)
        assertIs<MappingResult.Success>(londonResult)

        // During BST, the same local time in London is 1 hour behind UTC
        // So 12:00 London time = 11:00 UTC
        assertTrue(
            utcResult.transfer.timestamp != londonResult.transfer.timestamp,
            "UTC and London timestamps should differ during BST",
        )

        // UTC timestamp should be 2024-06-15T12:00:00Z
        val expectedUtc =
            LocalDateTime(2024, 6, 15, 12, 0, 0)
                .toInstant(TimeZone.UTC)
        assertEquals(expectedUtc, utcResult.transfer.timestamp)

        // London timestamp should be 2024-06-15T11:00:00Z (12:00 London = 11:00 UTC during BST)
        val expectedLondon =
            LocalDateTime(2024, 6, 15, 12, 0, 0)
                .toInstant(TimeZone.of("Europe/London"))
        assertEquals(expectedLondon, londonResult.transfer.timestamp)
    }

    // ============= Fallback Column Tests =============

    private val columnsWithType =
        listOf(
            CsvColumn(CsvColumnId(Uuid.random()), 0, "Date"),
            CsvColumn(CsvColumnId(Uuid.random()), 1, "Description"),
            CsvColumn(CsvColumnId(Uuid.random()), 2, "Amount"),
            CsvColumn(CsvColumnId(Uuid.random()), 3, "Name"),
            CsvColumn(CsvColumnId(Uuid.random()), 4, "Type"),
        )

    private fun createStrategyWithFallback(
        primaryColumn: String = "Name",
        fallbackColumns: List<String> = listOf("Type"),
    ): CsvImportStrategy {
        val now = Clock.System.now()
        return CsvImportStrategy(
            id = CsvImportStrategyId(Uuid.random()),
            name = "Strategy With Fallback",
            identificationColumns = setOf("Date", "Description", "Amount", "Name", "Type"),
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
                            columnName = primaryColumn,
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

    @Test
    fun `mapRow uses fallback column when primary column is empty`() {
        val chequeAccount =
            Account(
                id = AccountId(3),
                name = "Cheque",
                openingDate = Clock.System.now(),
            )

        val strategy = createStrategyWithFallback()
        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columnsWithType,
                existingAccounts = mapOf("Cheque" to chequeAccount),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
            )

        // Name is empty, should fall back to Type
        val row =
            CsvRow(
                rowIndex = 1,
                values = listOf("15/12/2024", "Cheque credited", "2.40", "", "Cheque"),
            )
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals(chequeAccount.id, result.transfer.targetAccountId)
    }

    @Test
    fun `mapRow uses primary column when it has value`() {
        val strategy = createStrategyWithFallback()
        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columnsWithType,
                existingAccounts = mapOf("Payee Account" to testTargetAccount),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
            )

        // Name has value, should use it (not fallback)
        val row =
            CsvRow(
                rowIndex = 1,
                values = listOf("15/12/2024", "Payment", "-50.00", "Payee Account", "Faster payment"),
            )
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals(testTargetAccountId, result.transfer.targetAccountId)
    }

    @Test
    fun `mapRow identifies new account from fallback column when primary is empty`() {
        val strategy = createStrategyWithFallback()
        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columnsWithType,
                existingAccounts = emptyMap(),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
            )

        // Name is empty, should identify "Cheque" from Type as new account
        val row =
            CsvRow(
                rowIndex = 1,
                values = listOf("15/12/2024", "Cheque credited", "2.40", "", "Cheque"),
            )
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals("Cheque", result.newAccountName)
    }

    @Test
    fun `mapRow returns empty account name when both primary and fallback are empty`() {
        val strategy = createStrategyWithFallback()
        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columnsWithType,
                existingAccounts = emptyMap(),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
            )

        // Both Name and Type are empty
        val row =
            CsvRow(
                rowIndex = 1,
                values = listOf("15/12/2024", "Unknown transaction", "10.00", "", ""),
            )
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        // newAccountName should be null (empty string is not a valid account name)
        assertEquals(null, result.newAccountName)
    }

    @Test
    fun `prepareImport handles mix of primary and fallback account lookups`() {
        val chequeAccount =
            Account(
                id = AccountId(3),
                name = "Cheque",
                openingDate = Clock.System.now(),
            )

        val strategy = createStrategyWithFallback()
        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columnsWithType,
                existingAccounts =
                    mapOf(
                        "Payee Account" to testTargetAccount,
                        "Cheque" to chequeAccount,
                    ),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
            )

        val rows =
            listOf(
                // Normal payment - uses Name column
                CsvRow(rowIndex = 1, values = listOf("15/12/2024", "Payment 1", "-50.00", "Payee Account", "Faster payment")),
                // Cheque - Name empty, uses Type column
                CsvRow(rowIndex = 2, values = listOf("16/12/2024", "Cheque credited", "2.40", "", "Cheque")),
                // Another normal payment
                CsvRow(rowIndex = 3, values = listOf("17/12/2024", "Payment 2", "-25.00", "Payee Account", "Card payment")),
            )

        val preparation = mapper.prepareImport(rows)

        assertEquals(3, preparation.validTransfers.size)
        assertEquals(0, preparation.errorRows.size)
        assertEquals(0, preparation.newAccounts.size) // All accounts exist
    }

    // ============= RegexAccountMapping Tests =============

    private val paxosAccount =
        Account(
            id = AccountId(4),
            name = "Paxos",
            openingDate = Clock.System.now(),
        )

    private fun createStrategyWithRegex(
        rules: List<RegexRule> =
            listOf(
                RegexRule(pattern = ".*paxos.*", accountName = "Paxos"),
            ),
        fallbackColumns: List<String> = listOf("Type"),
    ): CsvImportStrategy {
        val now = Clock.System.now()
        return CsvImportStrategy(
            id = CsvImportStrategyId(Uuid.random()),
            name = "Strategy With Regex",
            identificationColumns = setOf("Date", "Description", "Amount", "Name", "Type"),
            fieldMappings =
                mapOf(
                    TransferField.SOURCE_ACCOUNT to
                        HardCodedAccountMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.SOURCE_ACCOUNT,
                            accountId = testSourceAccountId,
                        ),
                    TransferField.TARGET_ACCOUNT to
                        RegexAccountMapping(
                            id = FieldMappingId(Uuid.random()),
                            fieldType = TransferField.TARGET_ACCOUNT,
                            columnName = "Name",
                            rules = rules,
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

    @Test
    fun `mapRow with RegexAccountMapping uses matched account when pattern matches`() {
        val strategy = createStrategyWithRegex()
        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columnsWithType,
                existingAccounts = mapOf("Paxos" to paxosAccount),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
            )

        // Name contains "paxos" - should match and map to Paxos account
        val row =
            CsvRow(
                rowIndex = 1,
                values = listOf("15/12/2024", "Salary payment", "1500.00", "103611797paxos Te", "Faster payment"),
            )
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals(paxosAccount.id, result.transfer.targetAccountId)
    }

    @Test
    fun `mapRow with RegexAccountMapping is case insensitive`() {
        val strategy = createStrategyWithRegex()
        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columnsWithType,
                existingAccounts = mapOf("Paxos" to paxosAccount),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
            )

        // Name contains "PAXOS" (uppercase) - should still match due to case-insensitive matching
        val row =
            CsvRow(
                rowIndex = 1,
                values = listOf("15/12/2024", "Salary payment", "1500.00", "PAXOS TECHNOLOGY LIMITED", "Faster payment"),
            )
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals(paxosAccount.id, result.transfer.targetAccountId)
    }

    @Test
    fun `mapRow with RegexAccountMapping uses column value when no pattern matches`() {
        val strategy = createStrategyWithRegex()
        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columnsWithType,
                existingAccounts = mapOf("Amazon UK" to testTargetAccount),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
            )

        // Name doesn't match regex - should use raw column value
        val row =
            CsvRow(
                rowIndex = 1,
                values = listOf("15/12/2024", "Purchase", "-50.00", "Amazon UK", "Card payment"),
            )
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals(testTargetAccountId, result.transfer.targetAccountId)
    }

    @Test
    fun `mapRow with RegexAccountMapping identifies new account from matched name`() {
        val strategy = createStrategyWithRegex()
        // Paxos account doesn't exist yet
        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columnsWithType,
                existingAccounts = emptyMap(),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
            )

        val row =
            CsvRow(
                rowIndex = 1,
                values = listOf("15/12/2024", "Salary payment", "1500.00", "103611797paxos Te", "Faster payment"),
            )
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals("Paxos", result.newAccountName)
    }

    @Test
    fun `mapRow with RegexAccountMapping identifies new account from fallback when primary empty and no match`() {
        val strategy = createStrategyWithRegex()
        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columnsWithType,
                existingAccounts = emptyMap(),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
            )

        // Name is empty, Type is "Cheque" - should fall back to column value
        val row =
            CsvRow(
                rowIndex = 1,
                values = listOf("15/12/2024", "Cheque credited", "100.00", "", "Cheque"),
            )
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals("Cheque", result.newAccountName)
    }

    @Test
    fun `mapRow with RegexAccountMapping uses first matching rule when multiple rules exist`() {
        val strategy =
            createStrategyWithRegex(
                rules =
                    listOf(
                        RegexRule(pattern = ".*paxos.*", accountName = "Paxos"),
                        RegexRule(pattern = ".*crypto.*", accountName = "Crypto.com"),
                    ),
            )
        val cryptoAccount =
            Account(
                id = AccountId(5),
                name = "Crypto.com",
                openingDate = Clock.System.now(),
            )
        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columnsWithType,
                existingAccounts =
                    mapOf(
                        "Paxos" to paxosAccount,
                        "Crypto.com" to cryptoAccount,
                    ),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
            )

        // Should match crypto pattern
        val row =
            CsvRow(
                rowIndex = 1,
                values = listOf("15/12/2024", "Crypto purchase", "-100.00", "crypto.com app", "Card payment"),
            )
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals(cryptoAccount.id, result.transfer.targetAccountId)
    }

    @Test
    fun `prepareImport with RegexAccountMapping handles mix of matched and unmatched values`() {
        val strategy = createStrategyWithRegex()
        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columnsWithType,
                existingAccounts =
                    mapOf(
                        "Paxos" to paxosAccount,
                        "Amazon UK" to testTargetAccount,
                    ),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
            )

        val rows =
            listOf(
                // Matches paxos regex
                CsvRow(rowIndex = 1, values = listOf("15/12/2024", "Salary", "1500.00", "103611797paxos Te", "Faster payment")),
                // Doesn't match, uses column value
                CsvRow(rowIndex = 2, values = listOf("16/12/2024", "Purchase", "-50.00", "Amazon UK", "Card payment")),
                // Matches paxos regex (different prefix)
                CsvRow(rowIndex = 3, values = listOf("17/12/2024", "Salary", "1500.00", "350709785paxos Te", "Faster payment")),
            )

        val preparation = mapper.prepareImport(rows)

        assertEquals(3, preparation.validTransfers.size)
        assertEquals(0, preparation.errorRows.size)
        assertEquals(0, preparation.newAccounts.size) // All map to existing accounts
    }
}
