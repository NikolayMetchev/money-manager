@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.csvimporter

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.builtin.BuiltInCsvStrategies
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
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
    private val cryptoStrategy = BuiltInCsvStrategies.buildCryptoComCryptoStrategy(now)

    private val gbp = Currency(id = CurrencyId(1), code = "GBP", name = "British Pound")
    private val card = Account(id = AccountId(1), name = "Crypto.com Card", openingDate = now)
    private val cash = Account(id = AccountId(2), name = "Crypto.com Cash", openingDate = now)
    private val cryptoWallet = Account(id = AccountId(3), name = "Crypto.com", openingDate = now)

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

    // TGBP/CRO are crypto assets (created on demand by ensureCryptoAssets in the real flow); provide
    // them here so the mapper resolves them and the conversion rows map to cross-asset trades.
    private val tgbp = CryptoAsset(id = CryptoId(100), code = "TGBP", name = "TGBP")
    private val cro = CryptoAsset(id = CryptoId(101), code = "CRO", name = "CRO")

    private fun mapper(
        strategy: CsvImportStrategy,
        cryptoWalletExists: Boolean = false,
    ): CsvTransferMapper =
        CsvTransferMapper(
            strategy = strategy,
            columns = columns,
            existingAccounts =
                buildMap {
                    put(card.name, card)
                    put(cash.name, cash)
                    // The crypto-strategy tests resolve both legs of a same-account trade to this real
                    // account; the fiat tests keep it absent so they can assert it gets discovered.
                    if (cryptoWalletExists) put(cryptoWallet.name, cryptoWallet)
                },
            existingCurrencies = mapOf(gbp.id to gbp),
            existingCurrenciesByCode = mapOf(gbp.code to gbp),
            existingCryptoByCode = mapOf(tgbp.code to tgbp, cro.code to cro),
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
        cryptoWalletExists: Boolean = false,
    ): MappingResult.Success {
        val result = mapper(strategy, cryptoWalletExists).mapRow(row)
        if (result is MappingResult.Error) throw AssertionError("mapping failed: ${result.errorMessage}")
        return assertIs<MappingResult.Success>(result, "mapping failed")
    }

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
    fun `viban_purchase is a trade - Cash GBP into the single Crypto_com account as TGBP`() {
        val r = map(fiatStrategy, row("GBP -> TGBP", "GBP", "5000.0", "TGBP", "5000.0", "5000.0", "viban_purchase"))
        // Debit GBP from Cash, credit TGBP into the single "Crypto.com" account (not a per-ticker wallet).
        assertEquals(cash.id, r.transfer.sourceAccountId)
        assertEquals("Crypto.com", r.newAccountName())
        assertEquals(Money.fromDisplayValue(BigDecimal("5000.0"), gbp), r.transfer.amount)
        assertEquals(Money.fromDisplayValue(BigDecimal("5000.0"), tgbp), r.tradeTo)
    }

    @Test
    fun `crypto_viban is a trade - TGBP out of the Crypto_com account into Cash as GBP`() {
        val r = map(fiatStrategy, row("TGBP -> GBP", "TGBP", "5009.86", "GBP", "5009.86", "5009.86", "crypto_viban"))
        // Debit TGBP from the "Crypto.com" account (source, after the positive-amount flip), credit GBP to Cash.
        assertEquals("Crypto.com", r.newAccountName())
        assertEquals(cash.id, r.transfer.targetAccountId)
        assertEquals(Money.fromDisplayValue(BigDecimal("5009.86"), tgbp), r.transfer.amount)
        assertEquals(Money.fromDisplayValue(BigDecimal("5009.86"), gbp), r.tradeTo)
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

    // ---- Crypto strategy: cross-asset rows become trades ----

    @Test
    fun `crypto_exchange is a same-account trade inside the Crypto_com wallet`() {
        val r =
            map(
                cryptoStrategy,
                row("TGBP -> CRO", "TGBP", "-1499.936259", "CRO", "4965.5", "1499.94", "crypto_exchange"),
                cryptoWalletExists = true,
            )
        // Both legs stay in the one wallet; only the assets differ. Must not be rejected as a
        // source==target collision, and must not invent a "TGBP -> CRO" counterparty account.
        assertEquals(cryptoWallet.id, r.transfer.sourceAccountId)
        assertEquals(cryptoWallet.id, r.transfer.targetAccountId)
        assertEquals(Money.fromDisplayValue(BigDecimal("1499.936259"), tgbp), r.transfer.amount)
        assertEquals(Money.fromDisplayValue(BigDecimal("4965.5"), cro), r.tradeTo)
        assertTrue(r.newAccounts.isEmpty(), "a same-account trade must not create counterparty accounts")
    }

    @Test
    fun `crypto file viban_purchase mirrors the fiat trade - Cash GBP into Crypto_com as TGBP`() {
        // Same event as the fiat file's viban_purchase (identical timestamp/description/amounts, but the
        // crypto file's Amount is NEGATIVE) — identical accounts make the two files' trades dedupe.
        val r =
            map(
                cryptoStrategy,
                row("GBP -> TGBP", "GBP", "-5000.0", "TGBP", "5000.0", "5000.0", "viban_purchase"),
                cryptoWalletExists = true,
            )
        assertEquals(cash.id, r.transfer.sourceAccountId)
        assertEquals(cryptoWallet.id, r.transfer.targetAccountId)
        assertEquals(Money.fromDisplayValue(BigDecimal("5000.0"), gbp), r.transfer.amount)
        assertEquals(Money.fromDisplayValue(BigDecimal("5000.0"), tgbp), r.tradeTo)
    }

    @Test
    fun `crypto file crypto_viban_exchange mirrors the fiat trade - Crypto_com TGBP into Cash as GBP`() {
        val r =
            map(
                cryptoStrategy,
                row("TGBP -> GBP", "TGBP", "-46.03", "GBP", "46.03", "46.03", "crypto_viban_exchange"),
                cryptoWalletExists = true,
            )
        assertEquals(cryptoWallet.id, r.transfer.sourceAccountId)
        assertEquals(cash.id, r.transfer.targetAccountId)
        assertEquals(Money.fromDisplayValue(BigDecimal("46.03"), tgbp), r.transfer.amount)
        assertEquals(Money.fromDisplayValue(BigDecimal("46.03"), gbp), r.tradeTo)
    }

    @Test
    fun `crypto_payment with matching To Currency stays a plain transfer`() {
        val r =
            map(
                cryptoStrategy,
                row("Crypto payment", "CRO", "-100.0", "CRO", "-100.0", "10.0", "crypto_payment"),
                cryptoWalletExists = true,
            )
        assertNull(r.tradeTo, "same-asset To Currency must not become a trade")
        assertEquals(cryptoWallet.id, r.transfer.sourceAccountId)
        assertEquals("Crypto payment", r.newAccountName())
    }

    @Test
    fun `reward row with blank To Currency keeps the description counterparty`() {
        val r =
            map(
                cryptoStrategy,
                row("Card Cashback", "CRO", "0.37", "", "", "0.09", "referral_card_cashback"),
                cryptoWalletExists = true,
            )
        assertNull(r.tradeTo)
        // Positive amount flips: the cashback flows INTO the wallet from the discovered counterparty.
        assertEquals(cryptoWallet.id, r.transfer.targetAccountId)
        assertEquals("Card Cashback", r.newAccountName())
    }

    @Test
    fun `App wallet transfer still routes to the Exchange account`() {
        val r =
            map(
                cryptoStrategy,
                row("Withdraw to Exchange from App wallet", "CRO", "-500.0", "", "", "50.0", "crypto_to_exchange_transfer"),
                cryptoWalletExists = true,
            )
        assertNull(r.tradeTo)
        assertEquals(cryptoWallet.id, r.transfer.sourceAccountId)
        assertEquals("Crypto.com Exchange", r.newAccountName())
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
        // crypto_* files now route to the "Crypto.com Crypto" strategy (by filename) and import as
        // real crypto balances instead of being skipped.
        assertEquals(
            "Crypto.com Crypto",
            strategies.selectForCsv("crypto_transactions_record_20251116_101217.csv", columns, cryptoRows)?.name,
        )
    }

    @Test
    fun `older 10-column exports without Transaction Hash still route by filename`() {
        val strategies = BuiltInCsvStrategies.builtInCsvStrategies(now)
        // crypto.com added the "Transaction Hash" column later; 2021/2022 exports have one fewer column.
        val legacyColumns =
            columns.dropLast(1).mapIndexed { index, c -> CsvColumn(CsvColumnId(Uuid.random()), index, c.originalName) }

        // The same rows minus the trailing Transaction Hash column (which older exports lacked).
        fun legacy(full: CsvRow) = CsvRow(full.rowIndex, full.values.dropLast(1))
        val legacyFiatRow = legacy(row("Top Up Card", "GBP", "200.0", "GBP", "200.0", "200.0", "viban_card_top_up"))
        val legacyCardRow = legacy(row("Spotify", "GBP", "-12.99", "", "", "-12.99", ""))
        assertEquals(
            "Crypto.com Fiat",
            strategies.selectForCsv("fiat_transactions_record_20210706_121420.csv", legacyColumns, listOf(legacyFiatRow))?.name,
        )
        assertEquals(
            "Crypto.com Card",
            strategies.selectForCsv("card_transactions_record_20210706_121443.csv", legacyColumns, listOf(legacyCardRow))?.name,
        )
    }
}
