@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.csvimporter

import com.moneymanager.builtin.BuiltInCsvStrategies
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.bigdecimal.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Exercises the built-in crypto.com Card and Fiat strategies ([BuiltInCsvStrategies]) — pure config —
 * against the real export row shapes. The fiat export's quirk under test: the amount's sign does not
 * encode direction (viban_card_top_up and viban_purchase are positive but money leaves the Cash
 * account), so row rules flip per Transaction Kind, and crypto_viban conversions carry the wallet
 * code in the Currency column (swapped into To Currency before mapping).
 */
class CryptoComCsvMapperTest {
    private val now = Clock.System.now()
    private val cardStrategy = BuiltInCsvStrategies.buildCryptoComCardStrategy(now)
    private val fiatStrategy = BuiltInCsvStrategies.buildCryptoComFiatStrategy(now)

    private val gbp = Currency(id = CurrencyId(1), code = "GBP", name = "British Pound")
    private val card = Account(id = AccountId(1), name = "Crypto.com Card", openingDate = now)
    private val cash = Account(id = AccountId(2), name = "Crypto.com Cash", openingDate = now)

    private val columns =
        listOf(
            "Timestamp (UTC)",
            "Transaction Description",
            "Currency",
            "Amount",
            "To Currency",
            "To Amount",
            "Native Currency",
            "Native Amount",
            "Native Amount (in USD)",
            "Transaction Kind",
            "Transaction Hash",
        ).mapIndexed { index, name -> CsvColumn(CsvColumnId(Uuid.random()), index, name) }

    private fun mapper(strategy: CsvImportStrategy): CsvTransferMapper =
        CsvTransferMapper(
            strategy = strategy,
            columns = columns,
            existingAccounts = mapOf(card.name to card, cash.name to cash),
            existingCurrencies = mapOf(gbp.id to gbp),
            existingCurrenciesByCode = mapOf(gbp.code to gbp),
        )

    @Suppress("LongParameterList")
    private fun row(
        description: String,
        currency: String,
        amount: String,
        toCurrency: String,
        toAmount: String,
        nativeAmount: String,
        kind: String,
    ): CsvRow =
        CsvRow(
            rowIndex = 1,
            values =
                listOf(
                    "2023-11-19 20:04:29",
                    description,
                    currency,
                    amount,
                    toCurrency,
                    toAmount,
                    "GBP",
                    nativeAmount,
                    "0.0",
                    kind,
                    "",
                ),
        )

    private fun map(
        strategy: CsvImportStrategy,
        row: CsvRow,
    ): MappingResult.Success = assertIs<MappingResult.Success>(mapper(strategy).mapRow(row), "mapping failed")

    private fun MappingResult.Success.newAccountName(): String {
        assertTrue(newAccounts.isNotEmpty(), "expected a discovered counterparty account")
        return newAccounts.first().name
    }

    // ---- Fiat strategy: direction comes from Transaction Kind, not the sign ----

    @Test
    fun `viban_deposit is external counterparty into Cash`() {
        val r = map(fiatStrategy, row("GBP Deposit (via FPS)", "GBP", "2000.0", "GBP", "2000.0", "2000.0", "viban_deposit"))
        // Positive amount flips: the description-derived counterparty funds the Cash account.
        assertEquals(cash.id, r.transfer.targetAccountId)
        assertEquals("GBP Deposit (via FPS)", r.newAccountName())
        assertEquals(Money.fromDisplayValue(BigDecimal("2000.0"), gbp), r.transfer.amount)
    }

    @Test
    fun `viban_withdrawal is Cash to external counterparty`() {
        val r = map(fiatStrategy, row("GBP Withdrawal (via FPS)", "GBP", "-5055.89", "GBP", "-5055.89", "-5055.89", "viban_withdrawal"))
        assertEquals(cash.id, r.transfer.sourceAccountId)
        assertEquals("GBP Withdrawal (via FPS)", r.newAccountName())
    }

    @Test
    fun `viban_card_top_up is Cash to Card despite the positive amount`() {
        val r = map(fiatStrategy, row("Top Up Card", "GBP", "200.0", "GBP", "200.0", "200.0", "viban_card_top_up"))
        // The row rule's flip cancels the positive-amount flip, keeping Cash as the source.
        assertEquals(cash.id, r.transfer.sourceAccountId)
        assertEquals(card.id, r.transfer.targetAccountId)
        assertTrue(r.newAccounts.isEmpty(), "top-ups must not create new accounts")
    }

    @Test
    fun `viban_purchase is Cash into the per-currency wallet`() {
        val r = map(fiatStrategy, row("GBP -> TGBP", "GBP", "5000.0", "TGBP", "5000.0", "5000.0", "viban_purchase"))
        assertEquals(cash.id, r.transfer.sourceAccountId)
        assertEquals("Crypto.com TGBP", r.newAccountName())
        // Amount stays in the Native (Cash-side) currency; no TGBP currency is needed.
        assertEquals(Money.fromDisplayValue(BigDecimal("5000.0"), gbp), r.transfer.amount)
    }

    @Test
    fun `crypto_viban is the per-currency wallet back into Cash`() {
        val r = map(fiatStrategy, row("TGBP -> GBP", "TGBP", "5009.86", "GBP", "5009.86", "5009.86", "crypto_viban"))
        // The Currency<->To Currency swap resolves the wallet, and the positive amount flips it
        // onto the source side.
        assertEquals(cash.id, r.transfer.targetAccountId)
        assertEquals("Crypto.com TGBP", r.newAccountName())
        assertEquals(Money.fromDisplayValue(BigDecimal("5009.86"), gbp), r.transfer.amount)
    }

    @Test
    fun `fiat rows tag the transaction kind attribute`() {
        val r = map(fiatStrategy, row("Top Up Card", "GBP", "200.0", "GBP", "200.0", "200.0", "viban_card_top_up"))
        assertEquals("viban_card_top_up", r.attributes.toMap()["cryptocom-kind"])
    }

    // ---- Card strategy: blank Transaction Kind on every row ----

    @Test
    fun `card spend is Card to merchant`() {
        val r = map(cardStrategy, row("Spotify P3 C6 Ef4945", "GBP", "-12.99", "", "", "-12.99", ""))
        assertEquals(card.id, r.transfer.sourceAccountId)
        assertEquals("Spotify P3 C6 Ef4945", r.newAccountName())
        assertNull(r.personalCounterpartyName)
    }

    @Test
    fun `card cross-currency spend uses the Native amount and currency`() {
        val r = map(cardStrategy, row("Of", "USD", "-3.97", "GBP", "-3.03", "-3.03", ""))
        assertEquals(card.id, r.transfer.sourceAccountId)
        assertEquals(Money.fromDisplayValue(BigDecimal("3.03"), gbp), r.transfer.amount)
    }

    @Test
    fun `card top-up row is Cash to Card, matching the fiat viban_card_top_up direction`() {
        val r = map(cardStrategy, row("GBP Deposit", "GBP", "400.0", "", "", "400.0", ""))
        // Same account pair and direction as the fiat file's viban_card_top_up rows, which is what
        // lets cross-source reconciliation link the two records of the same movement.
        assertEquals(cash.id, r.transfer.sourceAccountId)
        assertEquals(card.id, r.transfer.targetAccountId)
        assertTrue(r.newAccounts.isEmpty())
    }

    // ---- Selection across the three crypto.com export flavours ----

    @Test
    fun `selection routes card and fiat files by filename and skips crypto files`() {
        val strategies = BuiltInCsvStrategies.builtInCsvStrategies(now)
        val fiatRows = listOf(row("Top Up Card", "GBP", "200.0", "GBP", "200.0", "200.0", "viban_card_top_up"))
        val cardRows = listOf(row("Spotify", "GBP", "-12.99", "", "", "-12.99", ""))
        val cryptoRows =
            listOf(
                row("Card Cashback", "CRO", "0.37", "", "", "0.09", "referral_card_cashback"),
                row("GBP -> TGBP", "GBP", "10.0", "TGBP", "10.0", "10.0", "viban_purchase"),
                row("Card Cashback", "CRO", "0.42", "", "", "0.10", "referral_card_cashback"),
            )

        assertEquals(
            "Crypto.com Card",
            strategies.selectForCsv("card_transactions_record_20260629_230556.csv", columns, cardRows)?.name,
        )
        assertEquals(
            "Crypto.com Fiat",
            strategies.selectForCsv("fiat_transactions_record_20231120_085814.csv", columns, fiatRows)?.name,
        )
        // Renamed files still resolve by content.
        assertEquals("Crypto.com Card", strategies.selectForCsv("renamed.csv", columns, cardRows)?.name)
        assertEquals("Crypto.com Fiat", strategies.selectForCsv("renamed.csv", columns, fiatRows)?.name)
        // crypto_* files (occasional viban rows, below the content threshold) match nothing.
        assertNull(strategies.selectForCsv("crypto_transactions_record_20251116_101217.csv", columns, cryptoRows))
    }
}
