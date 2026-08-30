package com.moneymanager.importer

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.CurrencyScaleFactors
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Trade
import com.moneymanager.domain.model.TradeId
import com.moneymanager.importengineapi.ImportTradeIntent
import com.moneymanager.importengineapi.LocalTradeKey
import com.moneymanager.importengineapi.TradeDedupePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Covers matching a trade a CSV export describes against the trades an API import already booked for
 * the same movement. The cases are the two real disagreements between the sources: the API stamps
 * milliseconds where the export stamps whole seconds, and the API reports each partial fill where the
 * export reports only their total.
 */
class TradeReconcilerTest {
    private val binance = AccountId(1)
    private val other = AccountId(2)

    private val gbp =
        Currency(
            id = CurrencyId(1),
            code = "GBP",
            name = "Pound Sterling",
            scaleFactor = CurrencyScaleFactors.DEFAULT_SCALE_FACTOR,
        )
    private val btc = CryptoAsset(id = CryptoId(10), code = "BTC", name = "Bitcoin")
    private val eth = CryptoAsset(id = CryptoId(11), code = "ETH", name = "Ethereum")

    private var nextTradeId = 1L

    private fun money(
        display: String,
        asset: Asset,
    ) = Money.fromDisplayValue(BigDecimal(display), asset)

    private fun existing(
        at: String,
        from: String,
        fromAsset: Asset,
        to: String,
        toAsset: Asset,
        account: AccountId = binance,
    ) = Trade(
        id = TradeId(nextTradeId++),
        timestamp = Instant.parse(at),
        description = "api trade",
        fromAccountId = account,
        from = money(from, fromAsset),
        toAccountId = account,
        to = money(to, toAsset),
    )

    private fun incoming(
        at: String,
        from: String,
        fromAsset: Asset,
        to: String,
        toAsset: Asset,
        account: AccountId = binance,
    ) = ImportTradeIntent(
        key = LocalTradeKey("csv-1-1"),
        source = Source.Manual,
        timestamp = Instant.parse(at),
        description = "csv trade",
        fromAccountId = account,
        fromAmount = money(from, fromAsset),
        toAccountId = account,
        toAmount = money(to, toAsset),
    )

    private fun reconciler(
        existing: List<Trade>,
        window: Long = 300,
        allowAggregation: Boolean = true,
        matchFromLegOnly: Boolean = false,
    ) = TradeReconciler(
        TradeDedupePolicy.Fuzzy(
            window = window.seconds,
            allowAggregation = allowAggregation,
            matchFromLegOnly = matchFromLegOnly,
        ),
        existing,
    )

    @Test
    fun matchesAcrossASubSecondTimestampDisagreement() {
        // api/v3/myTrades stamps 20:32:54.462; the CSV export only records the second.
        val api = existing("2022-11-14T20:32:54.462Z", "1.0", btc, "13819.5897271", gbp)
        val matched = reconciler(listOf(api)).match(incoming("2022-11-14T20:32:54Z", "1.0", btc, "13819.5897271", gbp))
        assertEquals(api.id, matched)
    }

    @Test
    fun doesNotMatchOutsideTheWindow() {
        val api = existing("2022-11-14T20:32:54Z", "1.0", btc, "13819.5897271", gbp)
        val matched =
            reconciler(listOf(api), window = 60)
                .match(incoming("2022-11-14T20:40:00Z", "1.0", btc, "13819.5897271", gbp))
        assertNull(matched)
    }

    @Test
    fun aggregatesPerFillTradesWhoseTotalsEqualTheIncomingTrade() {
        // The real 2022-11-14 group: six API fills, one CSV row group summing to them.
        val btcFills = listOf("0.04382", "0.90716", "0.00441", "0.00312", "0.01351", "0.02798")
        val gbpFills =
            listOf("605.57925400", "12536.54297800", "60.96551580", "43.12694880", "186.70374170", "386.67128880")
        val fills = btcFills.zip(gbpFills).map { (b, g) -> existing("2022-11-14T20:32:54.462Z", b, btc, g, gbp) }

        val matched =
            reconciler(fills).match(incoming("2022-11-14T20:32:54Z", "1.00000000", btc, "13819.5897271", gbp))
        assertEquals(fills.first().id, matched, "the earliest of the matched set is reported")
    }

    @Test
    fun aggregationIsRefusedWhenTheTotalsDisagree() {
        // A partial overlap - some fills already imported, some not - is genuinely ambiguous, so the
        // incoming trade is written rather than guessed at.
        val fills =
            listOf("0.04382", "0.90716").zip(listOf("605.57925400", "12536.54297800")).map { (b, g) ->
                existing("2022-11-14T20:32:54.462Z", b, btc, g, gbp)
            }
        assertNull(reconciler(fills).match(incoming("2022-11-14T20:32:54Z", "1.00000000", btc, "13819.5897271", gbp)))
    }

    @Test
    fun aggregationCanBeTurnedOff() {
        val fills =
            listOf("0.5", "0.5").map { existing("2022-11-14T20:32:54.462Z", it, btc, "6909.79486355", gbp) }
        assertNull(
            reconciler(fills, allowAggregation = false)
                .match(incoming("2022-11-14T20:32:54Z", "1.0", btc, "13819.5897271", gbp)),
        )
    }

    @Test
    fun anExistingTradeIsClaimedByAtMostOneIncomingTrade() {
        val api = existing("2022-11-14T20:32:54Z", "1.0", btc, "13819.5897271", gbp)
        val subject = reconciler(listOf(api))
        assertNotNull(subject.match(incoming("2022-11-14T20:32:54Z", "1.0", btc, "13819.5897271", gbp)))
        assertNull(
            subject.match(incoming("2022-11-14T20:32:54Z", "1.0", btc, "13819.5897271", gbp)),
            "a genuinely repeated identical trade is still written",
        )
    }

    @Test
    fun theNearestCandidateInTimeWins() {
        val near = existing("2022-11-14T20:32:54Z", "1.0", btc, "100.0", gbp)
        val far = existing("2022-11-14T20:35:00Z", "1.0", btc, "100.0", gbp)
        assertEquals(near.id, reconciler(listOf(far, near)).match(incoming("2022-11-14T20:32:55Z", "1.0", btc, "100.0", gbp)))
    }

    @Test
    fun aDifferentAssetPairIsNotAMatch() {
        val api = existing("2022-11-14T20:32:54Z", "1.0", eth, "100.0", gbp)
        assertNull(reconciler(listOf(api)).match(incoming("2022-11-14T20:32:54Z", "1.0", btc, "100.0", gbp)))
    }

    @Test
    fun aDifferentAccountIsNotAMatch() {
        val api = existing("2022-11-14T20:32:54Z", "1.0", btc, "100.0", gbp, account = other)
        assertNull(reconciler(listOf(api)).match(incoming("2022-11-14T20:32:54Z", "1.0", btc, "100.0", gbp)))
    }

    @Test
    fun matchFromLegOnly_ignoresACreditedAmountTheOtherSourceReportsGross() {
        // Binance's dust API reports the BNB received before its 2% service charge; the CSV reports it
        // after. The debited leg is identical, and is the only side that can be compared.
        val api = existing("2021-01-01T09:43:33Z", "90.89657258", eth, "0.03318361", btc)
        val csv = incoming("2021-01-01T09:43:33Z", "90.89657258", eth, "0.03251993", btc)
        assertNull(reconciler(listOf(api)).match(csv), "comparing both legs cannot match a net-vs-gross pair")
        assertEquals(api.id, reconciler(listOf(api), matchFromLegOnly = true).match(csv))
    }

    @Test
    fun anEmptyCandidateSetMatchesNothing() {
        assertNull(reconciler(emptyList()).match(incoming("2022-11-14T20:32:54Z", "1.0", btc, "100.0", gbp)))
    }
}
