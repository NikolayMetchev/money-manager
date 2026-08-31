package com.moneymanager.csvimporter

import com.moneymanager.builtin.BuiltInCsvStrategies
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.CurrencyScaleFactors
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csv.CsvRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Covers the built-in Binance CSV strategy's row-level behaviour: which counterparty account each
 * `Operation` routes to, how the sign of `Change` sets the direction, and how trade and dust legs are
 * classified. Group assembly itself is covered by [CsvTradeGroupsTest].
 */
class BinanceCsvMapperTest {
    private val now = Clock.System.now()
    private val strategy = BuiltInCsvStrategies.buildBinanceCsvStrategy(now)

    // Production seeds every currency at the same 18-decimal scale as crypto, which is what lets
    // Binance's 8-decimal fiat amounts (e.g. "186.70374170" GBP) be represented exactly.
    private val gbp =
        Currency(
            id = CurrencyId(1),
            code = "GBP",
            name = "Pound Sterling",
            scaleFactor = CurrencyScaleFactors.DEFAULT_SCALE_FACTOR,
        )
    private val bnb = CryptoAsset(id = CryptoId(2), code = "BNB", name = "BNB")

    private val binance = Account(id = AccountId(1), name = "Binance", openingDate = now)

    private val columns =
        listOf("User_ID", "UTC_Time", "Account", "Operation", "Coin", "Change", "Remark")
            .mapIndexed { index, name -> CsvColumn(CsvColumnId(Uuid.random()), index, name) }

    private fun mapper() =
        CsvTransferMapper(
            strategy = strategy,
            columns = columns,
            existingAccounts = mapOf(binance.name to binance),
            existingCurrencies = mapOf(gbp.id to gbp),
            existingCurrenciesByCode = mapOf(gbp.code to gbp),
            existingCryptoByCode = mapOf(bnb.code to bnb),
        )

    private fun row(
        operation: String,
        coin: String,
        change: String,
        remark: String = "",
        rowIndex: Long = 1,
        time: String = "2023-01-02 03:04:05",
    ) = CsvRow(rowIndex = rowIndex, values = listOf("53064551", time, "Spot", operation, coin, change, remark))

    private fun map(row: CsvRow) = assertIs<MappingResult.Success>(mapper().mapRow(row), "mapping failed")

    /** The counterparty account name, whichever side of the transfer it landed on. */
    private fun counterpartyName(result: MappingResult.Success): String? = result.newAccounts.firstOrNull()?.name

    @Test
    fun negativeChange_leavesTheBinanceAccount() {
        val r = map(row("Simple Earn Flexible Subscription", "GBP", "-100.00", remark = "Binance Earn"))
        assertEquals(binance.id, r.transfer.sourceAccountId, "a negative Change leaves Binance")
        assertEquals("Binance Earn", counterpartyName(r))
    }

    @Test
    fun positiveChange_flipsSoBinanceReceives() {
        val r = map(row("Simple Earn Flexible Interest", "GBP", "0.12", remark = "Binance Earn"))
        assertEquals(binance.id, r.transfer.targetAccountId, "a positive Change arrives into Binance")
        assertEquals("Binance Earn Rewards", counterpartyName(r))
    }

    @Test
    fun operationRouting_sendsEachProductToItsOwnAccount() {
        val expected =
            mapOf(
                "Staking Purchase" to "Binance Staking",
                "Staking Redemption" to "Binance Staking",
                "Staking Rewards" to "Binance Staking Rewards",
                "BNB Vault Rewards" to "Binance Vault Rewards",
                "Launchpool Subscription/Redemption" to "Binance Launchpool",
                "Launchpool Earnings Withdrawal" to "Binance Launchpool Rewards",
                "Launchpool Interest" to "Binance Launchpool Rewards",
                "Launchpad Subscribe" to "Binance Launchpad",
                "Launchpad Token Distribution" to "Binance Launchpad",
                "Distribution" to "Binance Distribution",
                "Commission History" to "Binance Commission",
                "Commission Rebate" to "Binance Commission",
                "Liquid Swap Add" to "Binance Liquid Swap",
                "Liquidity Farming Remove" to "Binance Liquid Swap",
                "Dual Savings Purchase" to "Binance Dual Savings",
                "Dual Savings Settlement" to "Binance Dual Savings",
                "Simple Earn Locked Subscription" to "Binance Earn",
                "Simple Earn Locked Rewards" to "Binance Earn Rewards",
                "Simple Earn Flexible Airdrop" to "Binance Earn Rewards",
                "Fee" to "Binance Fees",
                "Transaction Fee" to "Binance Fees",
            )
        for ((operation, account) in expected) {
            assertEquals(account, counterpartyName(map(row(operation, "GBP", "-1.00"))), "routing for '$operation'")
        }
    }

    @Test
    fun depositAndWithdrawal_bookAgainstAPlaceholderCounterparty() {
        // The export never names the other side, so Deposit/Withdraw go to one placeholder whatever the
        // coin — the same name the API falls back to when it has no address. Only the explicitly fiat
        // operations, which the API books via its fiat endpoints, use the bank placeholder.
        assertEquals("Binance Funding", counterpartyName(map(row("Deposit", "BNB", "4.82096423"))))
        assertEquals("Binance Funding", counterpartyName(map(row("Withdraw", "BNB", "-1.0"))))
        assertEquals("Binance Funding", counterpartyName(map(row("Deposit", "GBP", "500.00"))))
        assertEquals("Binance Funding", counterpartyName(map(row("Withdraw", "GBP", "-500.00"))))
        assertEquals("Binance Bank", counterpartyName(map(row("Fiat Deposit", "GBP", "500.00"))))
        assertEquals("Binance Bank", counterpartyName(map(row("Fiat Withdrawal", "GBP", "-500.00"))))
    }

    @Test
    fun depositAndWithdrawalCounterpartiesAreMarkedUnidentified() {
        // This is what lets a deposit reconcile against the API's record of it, which names the on-chain
        // address the CSV cannot see.
        assertNotNull(map(row("Deposit", "BNB", "1.0")).unidentifiedCounterpartyAccountId)
        assertNotNull(map(row("Withdraw", "BNB", "-1.0")).unidentifiedCounterpartyAccountId)
        assertNull(
            map(row("Staking Rewards", "BNB", "0.01")).unidentifiedCounterpartyAccountId,
            "a product account is a real identity, not a placeholder",
        )
    }

    @Test
    fun patternsAreAnchored_soNoOperationSwallowsAnother() {
        // RegexRule matching is containsMatchIn: an unanchored "Deposit" would also claim "Fiat Deposit"
        // and an unanchored "Buy" would claim "Transaction Buy".
        assertEquals("Binance Bank", counterpartyName(map(row("Fiat Deposit", "GBP", "1.00"))))
        assertEquals(TradeLegSide.CREDIT, map(row("Transaction Buy", "GBP", "1.00")).tradeLeg?.side)
        assertEquals(TradeLegSide.DEBIT, map(row("Transaction Sold", "GBP", "-1.00")).tradeLeg?.side)
    }

    @Test
    fun tradeLegs_areClassifiedBySignForTheAmbiguousOperation() {
        // "Transaction Related" is the older name for *either* leg, so only the sign distinguishes them.
        assertEquals(TradeLegSide.DEBIT, map(row("Transaction Related", "GBP", "-99.97")).tradeLeg?.side)
        assertEquals(TradeLegSide.CREDIT, map(row("Transaction Related", "BNB", "0.008196")).tradeLeg?.side)
    }

    @Test
    fun feeRows_areNotTradeLegs() {
        // A trade row carries no fee field, so fee rows stay out of the group and import as transfers.
        assertNull(map(row("Fee", "BNB", "-0.00012823")).tradeLeg)
        assertNull(map(row("Transaction Fee", "BNB", "-0.0010")).tradeLeg)
    }

    @Test
    fun nonTradeRows_haveNoTradeLeg() {
        assertNull(map(row("Deposit", "GBP", "100.00")).tradeLeg)
        assertNull(map(row("Staking Rewards", "GBP", "0.01")).tradeLeg)
    }

    @Test
    fun dustLegs_areClassifiedBySignBecauseBothShareOneOperation() {
        val debit = map(row("Small Assets Exchange BNB (Spot)", "BNB", "-90.89657258"))
        val credit = map(row("Small Assets Exchange BNB (Spot)", "BNB", "0.03251993"))
        assertEquals(ConversionSide.DEBIT, debit.conversionLeg?.side)
        assertEquals(ConversionSide.CREDIT, credit.conversionLeg?.side)
        assertTrue(
            debit.newAccounts.any { it.name == "Binance Conversions" },
            "both legs route through the shared conversion account",
        )
        assertEquals(debit.conversionLeg?.pairingKey, credit.conversionLeg?.pairingKey)
    }

    @Test
    fun dustLegs_areNotAlsoTradeLegs() {
        // A dust sweep's credits cannot be attributed to its debits, so it must never be assembled into
        // a trade; it goes through ConversionConfig instead.
        assertNull(map(row("Small Assets Exchange BNB (Spot)", "BNB", "-90.89657258")).tradeLeg)
    }

    @Test
    fun scientificNotationChangeParses() {
        // Binance writes very small amounts as "2.5E-7"; the mapper must not choke or read them as zero.
        // Crypto assets hold 18 decimals, so such a value is representable exactly.
        val r = map(row("Staking Rewards", "BNB", "2.5E-7"))
        assertNotNull(r.transfer)
        assertEquals(binance.id, r.transfer.targetAccountId, "it is still a positive (incoming) amount")
    }

    @Test
    fun timestampIsParsedAsUtc() {
        val r = map(row("Deposit", "GBP", "1.00", time = "2021-06-14 23:59:58"))
        assertEquals("2021-06-14T23:59:58Z", r.transfer.timestamp.toString())
    }

    @Test
    fun attributesCarryTheExportsOwnColumns() {
        val r = map(row("Staking Rewards", "GBP", "0.01", remark = "STAKING"))
        val attributes = r.attributes.toMap()
        assertEquals("53064551", attributes["binance-user-id"])
        assertEquals("Staking Rewards", attributes["binance-operation"])
        assertEquals("STAKING", attributes["binance-remark"])
    }

    @Test
    fun anUnknownOperationLandsInTheSuspenseAccountRatherThanOneNamedAfterIt() {
        assertEquals("Binance Trading", counterpartyName(map(row("Some Future Product", "GBP", "-1.00"))))
    }
}
