package com.moneymanager.csvimporter

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.CurrencyScaleFactors
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csvstrategy.TradeGroupConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Covers assembling a trade out of the several rows a source splits it across (see [TradeGroupConfig]).
 * The cases are the real shapes a Binance export produces: a plain 1-fill swap, a many-fill order with
 * unequal counts per side, and the shapes that must NOT assemble.
 */
class CsvTradeGroupsTest {
    private val config =
        TradeGroupConfig(
            signalColumn = "Operation",
            debitPattern = "^(Sell|Transaction (Spend|Sold))$",
            creditPattern = "^(Buy|Transaction (Buy|Revenue))$",
            sideAmountColumn = "Change",
            groupingWindowSeconds = 0,
            descriptionTemplate = "Buy {to}/{from}",
        )

    private val binance = AccountId(1)
    private val trading = AccountId(2)

    // Production seeds every currency at the same 18-decimal scale as crypto, which is what lets
    // Binance's 8-decimal fiat amounts (e.g. "186.70374170" GBP) be represented exactly.
    private val gbp =
        Currency(
            id = CurrencyId(1),
            code = "GBP",
            name = "Pound Sterling",
            scaleFactor = CurrencyScaleFactors.DEFAULT_SCALE_FACTOR,
        )
    private val btc = CryptoAsset(id = CryptoId(10), code = "BTC", name = "Bitcoin")
    private val eth = CryptoAsset(id = CryptoId(11), code = "ETH", name = "Ethereum")

    private var nextRowIndex = 0L

    private fun money(
        display: String,
        asset: Asset,
    ) = Money.fromDisplayValue(BigDecimal(display), asset)

    /**
     * A mapped leg as the strategy produces it: the amount is absolute and the direction lives in the
     * accounts — a debit leaves the owner account, a credit arrives into it.
     */
    private fun leg(
        side: TradeLegSide,
        display: String,
        asset: Asset,
        at: String = "2022-11-14T20:32:54Z",
        owner: AccountId = binance,
    ): CsvTransferWithAttributes {
        val amount = money(display, asset)
        val timestamp = Instant.parse(at)
        return CsvTransferWithAttributes(
            transfer =
                Transfer(
                    id = TransferId(0),
                    timestamp = timestamp,
                    description = "leg",
                    sourceAccountId = if (side == TradeLegSide.DEBIT) owner else trading,
                    targetAccountId = if (side == TradeLegSide.DEBIT) trading else owner,
                    amount = amount,
                ),
            attributes = emptyList(),
            rowIndex = nextRowIndex++,
            tradeLeg = TradeLegInfo(side),
        )
    }

    /** A row the strategy did not flag as a trade leg (a fee, a deposit). */
    private fun nonLeg(display: String = "0.001"): CsvTransferWithAttributes = leg(TradeLegSide.DEBIT, display, btc).copy(tradeLeg = null)

    @Test
    fun oneFillPerSide_assemblesASingleTrade() {
        val rows =
            listOf(
                leg(TradeLegSide.DEBIT, "4.0", eth),
                leg(TradeLegSide.CREDIT, "0.128228", btc),
            )
        val groups = groupTradeLegs(rows, config)
        assertEquals(1, groups.size)
        val trade = assertNotNull(groups.single().assemble(config))
        assertEquals(binance, trade.ownerAccountId, "both legs of the trade sit on the owner account")
        assertEquals(money("4.0", eth), trade.fromAmount)
        assertEquals(money("0.128228", btc), trade.toAmount)
        assertEquals("Buy BTC/ETH", trade.description)
    }

    @Test
    fun manyPartialFills_sumIntoOneTradeEvenWithUnequalCountsPerSide() {
        // The real 2022-11-14 20:32:54 group: six BTC sold rows and six GBP revenue rows. The API
        // reports the same event as six per-fill trades, so the CSV's totals must be their sums.
        val btcFills = listOf("0.04382", "0.90716", "0.00441", "0.00312", "0.01351", "0.02798")
        val gbpFills =
            listOf("186.70374170", "43.12694880", "12536.54297800", "60.96551580", "605.57925400", "386.67128880")
        val rows =
            btcFills.map { leg(TradeLegSide.DEBIT, it, btc) } + gbpFills.map { leg(TradeLegSide.CREDIT, it, gbp) }

        val trade = assertNotNull(groupTradeLegs(rows, config).single().assemble(config))
        assertEquals(money("1.00000000", btc), trade.fromAmount, "the six BTC fills sum to exactly 1 BTC")
        assertEquals(money("13819.5897271", gbp), trade.toAmount)
        assertEquals(12, trade.group.rowIndexes.size, "every leg belongs to the group, for status write-back")
    }

    @Test
    fun distinctTimestamps_makeDistinctGroups() {
        val rows =
            listOf(
                leg(TradeLegSide.DEBIT, "1.0", eth, at = "2022-11-14T20:32:54Z"),
                leg(TradeLegSide.CREDIT, "0.03", btc, at = "2022-11-14T20:32:54Z"),
                leg(TradeLegSide.DEBIT, "2.0", eth, at = "2022-11-14T20:39:53Z"),
                leg(TradeLegSide.CREDIT, "0.06", btc, at = "2022-11-14T20:39:53Z"),
            )
        val groups = groupTradeLegs(rows, config)
        assertEquals(2, groups.size, "orders seconds apart are separate trades, not one aggregate")
        assertEquals(money("1.0", eth), assertNotNull(groups[0].assemble(config)).fromAmount)
        assertEquals(money("2.0", eth), assertNotNull(groups[1].assemble(config)).fromAmount)
    }

    @Test
    fun aGroupingWindow_holdsTogetherLegsThatStraddleASecondBoundary() {
        val windowed = config.copy(groupingWindowSeconds = 2)
        val rows =
            listOf(
                leg(TradeLegSide.DEBIT, "1.0", eth, at = "2021-01-01T09:43:33Z"),
                leg(TradeLegSide.CREDIT, "0.03", btc, at = "2021-01-01T09:43:34Z"),
            )
        assertEquals(1, groupTradeLegs(rows, windowed).size)
        assertEquals(2, groupTradeLegs(rows, config).size, "with a zero window the same rows are two groups")
    }

    @Test
    fun theTradeTakesTheGroupsEarliestTimestamp() {
        val windowed = config.copy(groupingWindowSeconds = 2)
        val rows =
            listOf(
                leg(TradeLegSide.CREDIT, "0.03", btc, at = "2021-01-01T09:43:34Z"),
                leg(TradeLegSide.DEBIT, "1.0", eth, at = "2021-01-01T09:43:33Z"),
            )
        val trade = assertNotNull(groupTradeLegs(rows, windowed).single().assemble(windowed))
        assertEquals(Instant.parse("2021-01-01T09:43:33Z"), trade.timestamp)
    }

    @Test
    fun aOneSidedGroupDoesNotAssemble() {
        // A boundary spill or a truncated export: the rows stay ordinary transfers rather than becoming
        // a trade with an invented other side.
        val rows = listOf(leg(TradeLegSide.DEBIT, "1.0", eth))
        assertNull(groupTradeLegs(rows, config).single().assemble(config))
    }

    @Test
    fun aGroupWithTwoAssetsOnOneSideDoesNotAssemble() {
        // Guards the property the assembly relies on: a real trade group names exactly one asset per
        // side. Anything else cannot be folded into one trade without inventing a pairing.
        val rows =
            listOf(
                leg(TradeLegSide.DEBIT, "1.0", eth),
                leg(TradeLegSide.DEBIT, "0.5", btc),
                leg(TradeLegSide.CREDIT, "100.0", gbp),
            )
        assertNull(groupTradeLegs(rows, config).single().assemble(config))
    }

    @Test
    fun aGroupWhoseSidesNameTheSameAssetDoesNotAssemble() {
        val rows =
            listOf(
                leg(TradeLegSide.DEBIT, "1.0", eth),
                leg(TradeLegSide.CREDIT, "1.0", eth),
            )
        assertNull(groupTradeLegs(rows, config).single().assemble(config))
    }

    @Test
    fun aGroupSummingToZeroDoesNotAssemble() {
        // Binance writes vanishing amounts as "0E-8"; a group of nothing but those is not a trade.
        val rows =
            listOf(
                leg(TradeLegSide.DEBIT, "0E-8", eth),
                leg(TradeLegSide.CREDIT, "0E-8", btc),
            )
        assertNull(groupTradeLegs(rows, config).single().assemble(config))
    }

    @Test
    fun legsDisagreeingAboutTheOwnerAccountDoNotAssemble() {
        val rows =
            listOf(
                leg(TradeLegSide.DEBIT, "1.0", eth, owner = binance),
                leg(TradeLegSide.CREDIT, "0.03", btc, owner = AccountId(99)),
            )
        assertNull(groupTradeLegs(rows, config).single().assemble(config))
    }

    @Test
    fun rowsThatAreNotTradeLegsAreIgnoredEntirely() {
        val rows = listOf(nonLeg(), leg(TradeLegSide.DEBIT, "1.0", eth), leg(TradeLegSide.CREDIT, "0.03", btc), nonLeg())
        val group = groupTradeLegs(rows, config).single()
        assertEquals(2, group.rows.size, "fee and other rows stay out of the group and import as transfers")
    }

    @Test
    fun noLegsMeansNoGroups() {
        assertEquals(emptyList(), groupTradeLegs(listOf(nonLeg(), nonLeg()), config))
    }
}
