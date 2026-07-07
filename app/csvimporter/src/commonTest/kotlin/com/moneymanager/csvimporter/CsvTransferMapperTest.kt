@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.csvimporter

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.accountmapping.AccountMapping
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
import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.model.passthrough.PassThroughAccountId
import com.moneymanager.domain.model.passthrough.PassThroughRule
import com.moneymanager.importengineapi.PassThroughDetector
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
    private val testCurrencyId = CurrencyId(1L)
    private val testCurrency =
        Currency(
            id = testCurrencyId,
            code = "GBP",
            name = "British Pound",
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
        assertEquals(com.moneymanager.bigdecimal.BigInteger(5000L), result.transfer.amount.amount)
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
    fun `mapRow routes a Curve pass-through row through the conduit and cleans the merchant`() {
        val strategy = createStrategy()
        val curve =
            PassThroughAccount(
                id = PassThroughAccountId(1),
                name = "Curve",
                conduitAccountName = "Curve",
                rules =
                    listOf(
                        PassThroughRule(
                            detectionPattern = "(?i)^CRV\\*",
                            merchantPattern = "(?i)^CRV\\*\\s*(.+?)(?:\\s{2,}.*)?$",
                        ),
                    ),
            )
        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columns,
                existingAccounts = emptyMap(),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
                passThroughDetector = PassThroughDetector(listOf(curve)),
            )

        val row = CsvRow(rowIndex = 1, values = listOf("15/12/2024", "Crv*Sainsburys", "-50.00", "Crv*Sainsburys"))
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        // The funding leg targets the conduit; the merchant is cleaned and carried on passThrough.
        assertEquals("Sainsburys", result.passThrough?.merchantName)
        assertEquals("Curve", result.passThrough?.conduitName)
        // The conduit + cleaned merchant are created; the raw "Crv*Sainsburys" junk account is not.
        val newNames = result.newAccounts.map { it.name }.toSet()
        assertEquals(setOf("Curve", "Sainsburys"), newNames)
    }

    @Test
    fun `mapRow routes a chained Curve-PayPal row through both conduits`() {
        val strategy = createStrategy()
        val curve =
            PassThroughAccount(
                id = PassThroughAccountId(1),
                name = "Curve",
                conduitAccountName = "Curve",
                rules =
                    listOf(
                        PassThroughRule(
                            detectionPattern = "(?i)^CRV\\*",
                            merchantPattern = "(?i)^CRV\\*\\s*(.+?)(?:\\s{2,}.*)?$",
                        ),
                    ),
            )
        val paypal =
            PassThroughAccount(
                id = PassThroughAccountId(2),
                name = "PayPal",
                conduitAccountName = "PayPal",
                rules =
                    listOf(
                        PassThroughRule(
                            detectionPattern = "(?i)^PAYPAL\\s*\\*",
                            merchantPattern = "(?i)^PAYPAL\\s*\\*\\s*(.+?)(?:\\s{2,}.*)?$",
                        ),
                    ),
            )
        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columns,
                existingAccounts = emptyMap(),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
                passThroughDetector = PassThroughDetector(listOf(curve, paypal)),
            )

        val row = CsvRow(rowIndex = 1, values = listOf("15/12/2024", "CRV*PAYPAL *THEPIHUT 0", "-27.00", "CRV*PAYPAL *THEPIHUT 0"))
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        // The whole chain is carried, outermost first; the merchant is fully stripped.
        assertEquals(listOf("Curve", "PayPal"), result.passThrough?.conduitNames)
        assertEquals("THEPIHUT 0", result.passThrough?.merchantName)
        assertEquals(listOf("PAYPAL *THEPIHUT 0", "THEPIHUT 0"), result.passThrough?.spendDescriptions)
        // The transfer's own counterparty side is the FIRST conduit; every chain account is created.
        assertEquals(setOf("Curve", "PayPal", "THEPIHUT 0"), result.newAccounts.map { it.name }.toSet())
    }

    // Mirrors the seeded Curve rule incl. Crypto.com's cancellation prefixes.
    private val curveWithCancellations =
        PassThroughAccount(
            id = PassThroughAccountId(1),
            name = "Curve",
            conduitAccountName = "Curve",
            rules =
                listOf(
                    PassThroughRule(
                        detectionPattern = "(?i)^(?:Refund: |Refund reversal: |Cancellation: )?CRV\\*",
                        merchantPattern = "(?i)^(?:Refund: |Refund reversal: |Cancellation: )?CRV\\*\\s*(.+?)(?:\\s{2,}.*)?$",
                    ),
                ),
        )

    @Test
    fun `mapRow routes an incoming Curve refund through the conduit on the source side`() {
        val strategy = createStrategy(flipAccountsOnPositive = true)
        val curveAccountId = AccountId(5)
        val curveAccount = Account(id = curveAccountId, name = "Curve", openingDate = Clock.System.now())
        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columns,
                existingAccounts = mapOf("Curve" to curveAccount),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
                passThroughDetector = PassThroughDetector(listOf(curveWithCancellations)),
            )

        // A cancelled charge refunded onto the card: positive amount, so the row is incoming (flipped).
        val row = CsvRow(rowIndex = 1, values = listOf("15/12/2024", "Refund: Crv*Navan", "363.58", "Refund: Crv*Navan"))
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        // The funding leg runs conduit -> card: the conduit replaces the row's source account.
        assertEquals(curveAccountId, result.transfer.sourceAccountId)
        assertEquals(testSourceAccountId, result.transfer.targetAccountId)
        assertEquals(true, result.passThrough?.incoming)
        // The merchant is the bare name, so the refund hits the same account as the original charge.
        assertEquals("Navan", result.passThrough?.merchantName)
        // Only the merchant is new; no junk "Refund: Crv*Navan" account is created.
        assertEquals(setOf("Navan"), result.newAccounts.map { it.name }.toSet())
    }

    @Test
    fun `mapRow routes an outgoing Curve refund reversal through the conduit like an ordinary charge`() {
        val strategy = createStrategy(flipAccountsOnPositive = true)
        val mapper =
            CsvTransferMapper(
                strategy = strategy,
                columns = columns,
                existingAccounts = emptyMap(),
                existingCurrencies = mapOf(testCurrencyId to testCurrency),
                existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
                passThroughDetector = PassThroughDetector(listOf(curveWithCancellations)),
            )

        // A refund reversal is negative (money leaves the card again), so it takes the outgoing path.
        val row =
            CsvRow(
                rowIndex = 1,
                values = listOf("15/12/2024", "Refund reversal: Crv*Navan", "-363.58", "Refund reversal: Crv*Navan"),
            )
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals(testSourceAccountId, result.transfer.sourceAccountId)
        assertEquals(false, result.passThrough?.incoming)
        assertEquals("Navan", result.passThrough?.merchantName)
        assertEquals(setOf("Curve", "Navan"), result.newAccounts.map { it.name }.toSet())
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
        assertEquals(com.moneymanager.bigdecimal.BigInteger(5000L), result.transfer.amount.amount)
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
        assertEquals(com.moneymanager.bigdecimal.BigInteger(123400L), result.transfer.amount.amount)
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
        assertEquals(com.moneymanager.bigdecimal.BigInteger(5000L), result.transfer.amount.amount)
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

    // ============= Pass-through merchant + persisted account mapping tests =============

    private fun accountMapping(
        id: Long,
        accountId: AccountId,
        strategyId: CsvImportStrategyId? = null,
    ): AccountMapping {
        val now = Clock.System.now()
        return AccountMapping(
            id = id,
            strategyId = strategyId,
            valuePattern = Regex(".*Amazoncouk.*", RegexOption.IGNORE_CASE),
            accountId = accountId,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun passThroughMapper(
        strategy: CsvImportStrategy,
        existingAccounts: Map<String, Account>,
        accountMappings: List<AccountMapping> = emptyList(),
        historicalAccountNames: Map<String, AccountId> = emptyMap(),
    ) = CsvTransferMapper(
        strategy = strategy,
        columns = columns,
        existingAccounts = existingAccounts,
        existingCurrencies = mapOf(testCurrencyId to testCurrency),
        existingCurrenciesByCode = mapOf(testCurrency.code.uppercase() to testCurrency),
        accountMappings = accountMappings,
        historicalAccountNames = historicalAccountNames,
        passThroughDetector = PassThroughDetector(listOf(curveWithCancellations)),
    )

    private val amazonAccount = Account(id = AccountId(7), name = "Amazon", openingDate = Clock.System.now())
    private val curveAccount = Account(id = AccountId(5), name = "Curve", openingDate = Clock.System.now())

    @Test
    fun `mapRow applies persisted mapping to the stripped pass-through merchant`() {
        val strategy = createStrategy()
        val mapper =
            passThroughMapper(
                strategy = strategy,
                existingAccounts = mapOf("Amazon" to amazonAccount, "Curve" to curveAccount),
                accountMappings = listOf(accountMapping(1, amazonAccount.id)),
            )

        val row = CsvRow(rowIndex = 1, values = listOf("15/12/2024", "Crv*Amazoncouk 1234", "-50.00", "Crv*Amazoncouk 1234"))
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals("Amazon", result.passThrough?.merchantName)
        assertEquals(amazonAccount.id, result.passThrough?.merchantAccountId)
        assertEquals(listOf("Amazoncouk 1234"), result.passThrough?.spendDescriptions)
        // No junk "Amazoncouk 1234" account is created — the mapping routed the spend leg to Amazon.
        assertEquals(emptySet(), result.newAccounts.map { it.name }.toSet())
    }

    @Test
    fun `mapRow prefers a strategy-scoped mapping over a global one for the merchant`() {
        val strategy = createStrategy()
        val otherAccount = Account(id = AccountId(8), name = "Amazon Business", openingDate = Clock.System.now())
        val mapper =
            passThroughMapper(
                strategy = strategy,
                existingAccounts =
                    mapOf(
                        "Amazon" to amazonAccount,
                        "Amazon Business" to otherAccount,
                        "Curve" to curveAccount,
                    ),
                accountMappings =
                    listOf(
                        accountMapping(1, amazonAccount.id),
                        accountMapping(2, otherAccount.id, strategyId = strategy.id),
                    ),
            )

        val row = CsvRow(rowIndex = 1, values = listOf("15/12/2024", "Crv*Amazoncouk 1234", "-50.00", "Crv*Amazoncouk 1234"))
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals(otherAccount.id, result.passThrough?.merchantAccountId)
    }

    @Test
    fun `mapRow ignores a merchant mapping that targets the conduit account`() {
        val strategy = createStrategy()
        val mapper =
            passThroughMapper(
                strategy = strategy,
                existingAccounts = mapOf("Curve" to curveAccount),
                accountMappings = listOf(accountMapping(1, curveAccount.id)),
            )

        val row = CsvRow(rowIndex = 1, values = listOf("15/12/2024", "Crv*Amazoncouk 1234", "-50.00", "Crv*Amazoncouk 1234"))
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals("Amazoncouk 1234", result.passThrough?.merchantName)
        assertEquals(null, result.passThrough?.merchantAccountId)
        assertEquals(setOf("Amazoncouk 1234"), result.newAccounts.map { it.name }.toSet())
    }

    @Test
    fun `mapRow resolves an unmapped merchant through historical account names`() {
        val strategy = createStrategy()
        val mapper =
            passThroughMapper(
                strategy = strategy,
                existingAccounts = mapOf("Amazon" to amazonAccount, "Curve" to curveAccount),
                historicalAccountNames = mapOf("amazoncouk 1234" to amazonAccount.id),
            )

        val row = CsvRow(rowIndex = 1, values = listOf("15/12/2024", "Crv*Amazoncouk 1234", "-50.00", "Crv*Amazoncouk 1234"))
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals(amazonAccount.id, result.passThrough?.merchantAccountId)
        assertEquals(emptySet(), result.newAccounts.map { it.name }.toSet())
    }

    @Test
    fun `mapRow applies the merchant mapping to an incoming Curve refund`() {
        val strategy = createStrategy(flipAccountsOnPositive = true)
        val mapper =
            passThroughMapper(
                strategy = strategy,
                existingAccounts = mapOf("Amazon" to amazonAccount, "Curve" to curveAccount),
                accountMappings = listOf(accountMapping(1, amazonAccount.id)),
            )

        val row =
            CsvRow(rowIndex = 1, values = listOf("15/12/2024", "Refund: Crv*Amazoncouk 1234", "50.00", "Refund: Crv*Amazoncouk 1234"))
        val result = mapper.mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals(true, result.passThrough?.incoming)
        assertEquals("Amazon", result.passThrough?.merchantName)
        assertEquals(amazonAccount.id, result.passThrough?.merchantAccountId)
    }
}
