@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.csv

import com.moneymanager.database.DatabaseConfig
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csv.ImportStatus
import com.moneymanager.domain.model.csvstrategy.CsvAccountMapping
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Exercises the built-in Wise CSV strategy (as seeded by [DatabaseConfig.buildWiseCsvStrategy])
 * against the row shapes found in real Wise transaction-history.csv exports.
 */
class WiseCsvMapperTest {
    private val strategy = DatabaseConfig.buildWiseCsvStrategy(Clock.System.now())

    private val eur = Currency(id = CurrencyId(1), code = "EUR", name = "Euro")
    private val gbp = Currency(id = CurrencyId(2), code = "GBP", name = "British Pound")
    private val bgn = Currency(id = CurrencyId(3), code = "BGN", name = "Bulgarian Lev")
    private val currencies = listOf(eur, gbp, bgn)
    private val currenciesById = currencies.associateBy { it.id }
    private val currenciesByCode = currencies.associateBy { it.code }

    private val wiseEur = Account(id = AccountId(10), name = "Wise: EUR", openingDate = Clock.System.now())
    private val wiseGbp = Account(id = AccountId(11), name = "Wise: GBP", openingDate = Clock.System.now())
    private val teodora = Account(id = AccountId(20), name = "Teodora Veselinova", openingDate = Clock.System.now())
    private val transferwise = Account(id = AccountId(21), name = "TransferWise", openingDate = Clock.System.now())
    private val defaultAccounts =
        listOf(wiseEur, wiseGbp, teodora, transferwise).associateBy { it.name }

    private val columnNames =
        listOf(
            "ID",
            "Status",
            "Direction",
            "Created on",
            "Finished on",
            "Source fee amount",
            "Source fee currency",
            "Target fee amount",
            "Target fee currency",
            "Source name",
            "Source amount (after fees)",
            "Source currency",
            "Target name",
            "Target amount (after fees)",
            "Target currency",
            "Exchange rate",
            "Reference",
            "Batch",
            "Created by",
            "Category",
            "Note",
        )

    private val columns =
        columnNames.mapIndexed { index, name ->
            CsvColumn(CsvColumnId(Uuid.random()), index, name)
        }

    @Suppress("LongParameterList")
    private fun wiseRow(
        rowIndex: Long = 1,
        id: String = "TRANSFER-1",
        status: String = "COMPLETED",
        direction: String = "OUT",
        createdOn: String = "2026-04-20 11:39:52",
        sourceName: String = "Nikolay Metchev",
        sourceAmount: String = "22.10",
        sourceFee: String = "0.00",
        sourceCurrency: String = "EUR",
        targetName: String = "Avolta - Tenerife",
        targetAmount: String = "22.10",
        targetCurrency: String = "EUR",
        exchangeRate: String = "1.0",
        reference: String = "",
        note: String = "",
    ): CsvRow =
        CsvRow(
            rowIndex = rowIndex,
            values =
                listOf(
                    id,
                    status,
                    direction,
                    createdOn,
                    createdOn,
                    sourceFee,
                    sourceCurrency,
                    "",
                    "",
                    sourceName,
                    sourceAmount,
                    sourceCurrency,
                    targetName,
                    targetAmount,
                    targetCurrency,
                    exchangeRate,
                    reference,
                    "",
                    sourceName,
                    "General",
                    note,
                ),
        )

    private fun mapper(
        accounts: Map<String, Account> = defaultAccounts,
        existingTransfers: List<ExistingTransferInfo> = emptyList(),
        accountMappings: List<CsvAccountMapping> = emptyList(),
    ): CsvTransferMapper =
        CsvTransferMapper(
            strategy = strategy,
            columns = columns,
            existingAccounts = accounts,
            existingCurrencies = currenciesById,
            existingCurrenciesByCode = currenciesByCode,
            existingTransfers = existingTransfers,
            accountMappings = accountMappings,
        )

    @Test
    fun `OUT card payment routes from the source-currency Wise account to the counterparty`() {
        val result = mapper().mapRow(wiseRow())

        assertIs<MappingResult.Success>(result)
        assertEquals(wiseEur.id, result.transfer.sourceAccountId)
        // "Avolta - Tenerife" is unknown so a new account placeholder is used
        assertEquals(AccountId(-1), result.transfer.targetAccountId)
        assertEquals(listOf(NewAccount("Avolta - Tenerife", -1L)), result.newAccounts)
        assertEquals(2210L, result.transfer.amount.amount)
        assertEquals(eur, result.transfer.amount.currency)
    }

    @Test
    fun `IN transfer swaps sides so money flows from the counterparty into the target-currency Wise account`() {
        val row =
            wiseRow(
                id = "TRANSFER-1709494792",
                direction = "IN",
                sourceName = "Ivan Metchev",
                sourceAmount = "2500.0",
                sourceCurrency = "GBP",
                targetName = "Nikolay Metchev",
                targetAmount = "2500.0",
                targetCurrency = "GBP",
            )
        val accounts = defaultAccounts + ("Ivan Metchev" to Account(AccountId(30), name = "Ivan Metchev", openingDate = Clock.System.now()))
        val result = mapper(accounts).mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals(AccountId(30), result.transfer.sourceAccountId)
        assertEquals(wiseGbp.id, result.transfer.targetAccountId)
        assertEquals(250000L, result.transfer.amount.amount)
        assertEquals(gbp, result.transfer.amount.currency)
        assertTrue(result.newAccounts.isEmpty())
    }

    @Test
    fun `balance conversion routes both sides to Wise accounts`() {
        val row =
            wiseRow(
                id = "BALANCE-1",
                sourceAmount = "100.00",
                targetName = "Nikolay Metchev",
                targetAmount = "85.00",
                targetCurrency = "GBP",
            )
        val result = mapper().mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals(wiseEur.id, result.transfer.sourceAccountId)
        assertEquals(wiseGbp.id, result.transfer.targetAccountId)
        // Wise-side (source) amount is recorded
        assertEquals(10000L, result.transfer.amount.amount)
        assertEquals(eur, result.transfer.amount.currency)
    }

    @Test
    fun `fee row with empty source name resolves the counterparty from the target name`() {
        val row =
            wiseRow(
                id = "ACCRUAL_CHARGE-18326272",
                sourceName = "",
                sourceAmount = "0.06",
                sourceFee = "",
                targetName = "TransferWise",
                targetAmount = "0.06",
                reference = "Assets fee 18326272",
            )
        val result = mapper().mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals(wiseEur.id, result.transfer.sourceAccountId)
        assertEquals(transferwise.id, result.transfer.targetAccountId)
        assertEquals(6L, result.transfer.amount.amount)
        assertEquals("Assets fee 18326272", result.transfer.description)
    }

    @Test
    fun `OUT fee is split into its own fee movement, not folded into the amount`() {
        // Real export shape: 200.00 withdrawn, 7.29 fee. The main transfer is the 200.00 movement and
        // the 7.29 fee becomes its own linked fee transfer.
        val row =
            wiseRow(
                id = "CARD_TRANSACTION-3683129415",
                sourceAmount = "200.00",
                sourceFee = "7.29",
                targetName = "Av De Las Americas",
                targetAmount = "207.29",
            )
        val accounts =
            defaultAccounts +
                ("Av De Las Americas" to Account(AccountId(32), name = "Av De Las Americas", openingDate = Clock.System.now()))
        val result = mapper(accounts).mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals(20000L, result.transfer.amount.amount)
        assertEquals(729L, result.feeAmount?.amount)
    }

    @Test
    fun `IN rows never produce a fee movement`() {
        val row =
            wiseRow(
                id = "TRANSFER-42",
                direction = "IN",
                sourceName = "Ivan Metchev",
                sourceAmount = "99.00",
                sourceFee = "1.00",
                sourceCurrency = "GBP",
                targetName = "Nikolay Metchev",
                targetAmount = "99.00",
                targetCurrency = "GBP",
            )
        val accounts = defaultAccounts + ("Ivan Metchev" to Account(AccountId(30), name = "Ivan Metchev", openingDate = Clock.System.now()))
        val result = mapper(accounts).mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals(9900L, result.transfer.amount.amount)
        assertEquals(null, result.feeAmount)
    }

    @Test
    fun `cross-currency payment records the Wise-side amount and a separate fee`() {
        val row =
            wiseRow(
                id = "TRANSFER-876114969",
                sourceAmount = "89.19",
                sourceFee = "1.18",
                sourceCurrency = "GBP",
                targetName = "Teodora Veselinova",
                targetAmount = "200.0",
                targetCurrency = "BGN",
                exchangeRate = "2.24251",
                reference = "Teah Birthday",
            )
        val result = mapper().mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals(wiseGbp.id, result.transfer.sourceAccountId)
        assertEquals(teodora.id, result.transfer.targetAccountId)
        // 89.19 converted is the main movement; the 1.18 fee is its own linked fee transfer.
        assertEquals(8919L, result.transfer.amount.amount)
        assertEquals(118L, result.feeAmount?.amount)
        assertEquals(gbp, result.transfer.amount.currency)
        assertEquals(gbp, result.feeAmount?.currency)
        assertTrue(result.attributes.contains("wise-exchange-rate" to "2.24251"))
    }

    @Test
    fun `refunded row imports as a zero-amount transfer`() {
        val row =
            wiseRow(
                id = "CARD_TRANSACTION-3064849936",
                status = "REFUNDED",
                sourceAmount = "0.00",
                targetAmount = "0.00",
                targetName = "Atac Roma - tap & go",
            )
        val accounts =
            defaultAccounts +
                ("Atac Roma - tap & go" to Account(AccountId(31), name = "Atac Roma - tap & go", openingDate = Clock.System.now()))
        val result = mapper(accounts).mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals(0L, result.transfer.amount.amount)
        assertTrue(result.attributes.contains("wise-status" to "REFUNDED"))
    }

    @Test
    fun `combined date-time column parses in UTC`() {
        val result = mapper().mapRow(wiseRow(createdOn = "2026-06-02 15:12:22"))

        assertIs<MappingResult.Success>(result)
        val expected = LocalDateTime(2026, 6, 2, 15, 12, 22).toInstant(TimeZone.UTC)
        assertEquals(expected, result.transfer.timestamp)
    }

    @Test
    fun `re-mapping the same row is detected as a duplicate by the ID attribute`() {
        val firstResult = mapper().mapRow(wiseRow(targetName = "TransferWise"))
        assertIs<MappingResult.Success>(firstResult)

        val existing =
            ExistingTransferInfo(
                transferId = TransferId(99),
                transfer = firstResult.transfer,
                attributes = firstResult.attributes,
                uniqueIdentifierValues = mapOf("ID" to "TRANSFER-1"),
            )
        val secondResult = mapper(existingTransfers = listOf(existing)).mapRow(wiseRow(targetName = "TransferWise"))

        assertIs<MappingResult.Success>(secondResult)
        assertEquals(ImportStatus.DUPLICATE, secondResult.importStatus)
        assertEquals(TransferId(99), secondResult.existingTransferId)
    }

    @Test
    fun `re-mapping the same ID with a changed reference is detected as an update`() {
        val firstResult = mapper().mapRow(wiseRow(targetName = "TransferWise"))
        assertIs<MappingResult.Success>(firstResult)

        val existing =
            ExistingTransferInfo(
                transferId = TransferId(99),
                transfer = firstResult.transfer,
                attributes = firstResult.attributes,
                uniqueIdentifierValues = mapOf("ID" to "TRANSFER-1"),
            )
        val changedRow = wiseRow(targetName = "TransferWise", reference = "corrected description")
        val secondResult = mapper(existingTransfers = listOf(existing)).mapRow(changedRow)

        assertIs<MappingResult.Success>(secondResult)
        assertEquals(ImportStatus.UPDATED, secondResult.importStatus)
        assertEquals(TransferId(99), secondResult.existingTransferId)
    }

    @Test
    fun `missing Wise currency account is discovered as a new source account`() {
        val row =
            wiseRow(
                sourceAmount = "50.00",
                sourceCurrency = "BGN",
                targetName = "TransferWise",
                targetAmount = "50.00",
                targetCurrency = "BGN",
            )
        val result = mapper().mapRow(row)

        assertIs<MappingResult.Success>(result)
        assertEquals(AccountId(-1), result.transfer.sourceAccountId)
        assertEquals(listOf(NewAccount("Wise: BGN", -1L)), result.newAccounts)
        val discovered = result.discoveredMappings.single()
        assertEquals("Source currency", discovered.columnName)
        assertEquals("BGN", discovered.csvValue)
        assertEquals("Wise: BGN", discovered.targetAccountName)
    }

    @Test
    fun `persisted account mapping overrides the templated source account lookup`() {
        val renamedAccount = Account(AccountId(40), name = "My Euro Wallet", openingDate = Clock.System.now())
        val mapping =
            CsvAccountMapping(
                id = 1,
                strategyId = strategy.id,
                columnName = "Source currency",
                valuePattern = Regex("^EUR$"),
                accountId = renamedAccount.id,
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now(),
            )
        val accounts = defaultAccounts - "Wise: EUR" + ("My Euro Wallet" to renamedAccount)
        val result =
            mapper(accounts = accounts, accountMappings = listOf(mapping))
                .mapRow(wiseRow(targetName = "TransferWise"))

        assertIs<MappingResult.Success>(result)
        assertEquals(renamedAccount.id, result.transfer.sourceAccountId)
        assertTrue(result.newAccounts.isEmpty())
    }

    @Test
    fun `prepareImport collects new accounts from both sides`() {
        val rows =
            listOf(
                wiseRow(),
                wiseRow(
                    rowIndex = 2,
                    id = "TRANSFER-2",
                    sourceCurrency = "BGN",
                    targetCurrency = "BGN",
                    targetName = "TransferWise",
                ),
            )
        val preparation = mapper().prepareImport(rows)

        assertEquals(2, preparation.validTransfers.size)
        assertEquals(
            setOf(NewAccount("Avolta - Tenerife", -1L), NewAccount("Wise: BGN", -1L)),
            preparation.newAccounts,
        )
        assertTrue(preparation.errorRows.isEmpty())
    }
}
