package com.moneymanager.csvimporter

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csvstrategy.AmountMode
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.ConversionConfig
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.DateTimeParsingMapping
import com.moneymanager.domain.model.csvstrategy.DirectColumnMapping
import com.moneymanager.domain.model.csvstrategy.FieldMappingId
import com.moneymanager.domain.model.csvstrategy.RegexAccountMapping
import com.moneymanager.domain.model.csvstrategy.RegexRule
import com.moneymanager.domain.model.csvstrategy.TransferField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Proves [ConversionConfig] is a generic, source-agnostic mechanism — nothing crypto.com-specific.
 * A synthetic strategy with its own column names, signal patterns, and counterparty account routes
 * both legs of a two-row conversion through the configured account and marks each leg's side.
 */
class ConversionConfigMapperTest {
    private val now = Clock.System.now()

    private val usd = Currency(id = CurrencyId(1), code = "USD", name = "US Dollar")
    private val eur = Currency(id = CurrencyId(2), code = "EUR", name = "Euro")
    private val wallet = Account(id = AccountId(1), name = "Wallet", openingDate = now)

    private val columns =
        listOf("Kind", "Amount", "Asset", "Date", "Memo")
            .mapIndexed { index, name -> CsvColumn(CsvColumnId(Uuid.random()), index, name) }

    private fun fieldId() = FieldMappingId(Uuid.random())

    private val strategy =
        CsvImportStrategy(
            id = CsvImportStrategyId(Uuid.random()),
            name = "Synthetic",
            identificationColumns = setOf("Kind", "Amount", "Asset", "Date", "Memo"),
            fieldMappings =
                mapOf(
                    TransferField.SOURCE_ACCOUNT to
                        RegexAccountMapping(fieldId(), TransferField.SOURCE_ACCOUNT, "Memo", listOf(RegexRule("^", "Wallet"))),
                    TransferField.TARGET_ACCOUNT to
                        RegexAccountMapping(fieldId(), TransferField.TARGET_ACCOUNT, "Memo", listOf(RegexRule("^", "Counterparty"))),
                    TransferField.AMOUNT to
                        AmountParsingMapping(
                            fieldId(),
                            TransferField.AMOUNT,
                            mode = AmountMode.SINGLE_COLUMN,
                            amountColumnName = "Amount",
                            flipAccountsOnPositive = true,
                        ),
                    TransferField.CURRENCY to CurrencyLookupMapping(fieldId(), TransferField.CURRENCY, "Asset"),
                    TransferField.TIMESTAMP to
                        DateTimeParsingMapping(
                            fieldId(),
                            TransferField.TIMESTAMP,
                            dateColumnName = "Date",
                            dateFormat = "yyyy-MM-dd",
                            dateTimeFormat = "yyyy-MM-dd HH:mm:ss",
                        ),
                    TransferField.DESCRIPTION to DirectColumnMapping(fieldId(), TransferField.DESCRIPTION, "Memo"),
                ),
            conversionConfig =
                ConversionConfig(
                    signalColumn = "Kind",
                    debitPattern = "^swap_out$",
                    creditPattern = "^swap_in$",
                    conversionAccountName = "Conversions",
                    pairingKeyPattern = "^(swap)_",
                    pairingWindowSeconds = 5,
                    relationshipTypeName = "conversion",
                ),
            createdAt = now,
            updatedAt = now,
        )

    private fun mapper() =
        CsvTransferMapper(
            strategy = strategy,
            columns = columns,
            existingAccounts = mapOf(wallet.name to wallet),
            existingCurrencies = mapOf(usd.id to usd, eur.id to eur),
            existingCurrenciesByCode = mapOf(usd.code to usd, eur.code to eur),
        )

    private fun row(
        kind: String,
        amount: String,
        asset: String,
    ): CsvRow = CsvRow(rowIndex = 1, values = listOf(kind, amount, asset, "2024-01-01 10:00:00", "Swap"))

    private fun map(row: CsvRow) = assertIs<MappingResult.Success>(mapper().mapRow(row), "mapping failed")

    @Test
    fun debitLeg_routesCounterpartyToConversionsAccountAndMarksSide() {
        val r = map(row("swap_out", "-5", "USD"))
        // Negative amount -> no flip: Wallet is the source, the shared Conversions account the target.
        assertEquals(wallet.id, r.transfer.sourceAccountId)
        assertEquals(AccountId(-1), r.transfer.targetAccountId, "Conversions is a new account (placeholder id)")
        assertTrue(r.newAccounts.any { it.name == "Conversions" }, "the Conversions account is created on demand")
        assertTrue(r.newAccounts.none { it.name == "Counterparty" }, "the description-derived counterparty is not used")
        val leg = assertIs<ConversionLegInfo>(r.conversionLeg)
        assertEquals(ConversionSide.DEBIT, leg.side)
    }

    @Test
    fun creditLeg_flipsSoWalletReceivesAndMarksSide() {
        val r = map(row("swap_in", "5", "EUR"))
        // Positive amount flips: the Conversions account is the source, Wallet receives the asset.
        assertEquals(AccountId(-1), r.transfer.sourceAccountId, "Conversions is a new account (placeholder id)")
        assertEquals(wallet.id, r.transfer.targetAccountId)
        val leg = assertIs<ConversionLegInfo>(r.conversionLeg)
        assertEquals(ConversionSide.CREDIT, leg.side)
    }

    @Test
    fun debitAndCreditLegs_shareAPairingKey() {
        val debit = map(row("swap_out", "-5", "USD")).conversionLeg
        val credit = map(row("swap_in", "5", "EUR")).conversionLeg
        assertEquals(debit?.pairingKey, credit?.pairingKey, "both legs of the family pair on the same key")
    }

    @Test
    fun nonConversionRow_hasNoConversionLeg() {
        val r = map(row("reward", "5", "USD"))
        assertNull(r.conversionLeg)
    }
}
