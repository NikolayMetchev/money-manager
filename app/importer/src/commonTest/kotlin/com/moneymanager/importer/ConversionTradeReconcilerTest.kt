package com.moneymanager.importer

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TransferId
import com.moneymanager.importengineapi.ImportTradeIntent
import com.moneymanager.importengineapi.LocalTradeKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Covers matching a dust trade an API import describes against the conversion legs a CSV export
 * already booked as transfers — the reverse of the order `ConversionGroupReconciler` handles.
 *
 * The amounts are a real Binance sweep: the export debits 90.89657258 REEF and credits BNB net of the
 * service charge, while the API's trade reports the same REEF gross and its BNB leg differently. Only
 * the debited side can decide the match.
 */
class ConversionTradeReconcilerTest {
    private val binance = AccountId(1)
    private val other = AccountId(2)

    private val reef = CryptoAsset(id = CryptoId(10), code = "REEF", name = "Reef")
    private val bnb = CryptoAsset(id = CryptoId(11), code = "BNB", name = "Binance Coin")
    private val usdt = CryptoAsset(id = CryptoId(12), code = "USDT", name = "Tether")

    /** The sweep every conversion leg here belongs to, unless a test deliberately moves one off it. */
    private val sweptAt = Instant.parse("2021-01-01T09:43:33Z")

    private var nextTransferId = 1L

    private fun money(
        display: String,
        asset: Asset,
    ) = Money.fromDisplayValue(BigDecimal(display), asset)

    private fun leg(
        debited: String,
        debitedAsset: Asset = reef,
        creditedAsset: Asset = bnb,
        account: AccountId = binance,
        at: Instant = sweptAt,
    ) = ExistingConversionLeg(
        debitTransferId = TransferId(nextTransferId++),
        accountId = account,
        amount = money(debited, debitedAsset),
        timestamp = at,
        creditAssetId = creditedAsset.id,
    )

    private fun incoming(
        at: String,
        from: String,
        fromAsset: Asset = reef,
        to: String = "0.03318361",
        toAsset: Asset = bnb,
        account: AccountId = binance,
        key: String = "api-1",
    ) = ImportTradeIntent(
        key = LocalTradeKey(key),
        source = Source.Manual,
        timestamp = Instant.parse(at),
        description = "Buy BNB/REEF",
        fromAccountId = account,
        fromAmount = money(from, fromAsset),
        toAccountId = account,
        toAmount = money(to, toAsset),
    )

    private fun reconciler(
        legs: List<ExistingConversionLeg>,
        incoming: List<ImportTradeIntent>,
        window: Long = 5,
    ) = ConversionTradeReconciler(window.seconds, legs, incoming)

    /** The one-trade case, which every test but the competing-window ones is. */
    private fun matchOne(
        legs: List<ExistingConversionLeg>,
        intent: ImportTradeIntent,
        window: Long = 5,
    ) = reconciler(legs, listOf(intent), window).match(intent)

    @Test
    fun matchesTheDebitedLegWhateverTheCreditedAmountIs() {
        // The export credits 0.03251993 BNB, the API 0.03318361: the difference is the service charge,
        // so the credited amount is never compared.
        val existing = leg("90.89657258")
        assertEquals(existing.debitTransferId, matchOne(listOf(existing), incoming("2021-01-01T09:43:33Z", "90.89657258")))
    }

    @Test
    fun doesNotMatchADifferentDebitedAmount() {
        val existing = leg("90.89657258")
        assertNull(matchOne(listOf(existing), incoming("2021-01-01T09:43:33Z", "90.89657259")))
    }

    @Test
    fun doesNotMatchWhenTheCreditedAssetDiffers() {
        // A genuine REEF sale for USDT at the same instant is not the sweep, however alike the debits.
        val existing = leg("90.89657258")
        assertNull(
            matchOne(listOf(existing), incoming("2021-01-01T09:43:33Z", "90.89657258", to = "12.5", toAsset = usdt)),
        )
    }

    @Test
    fun doesNotMatchAnotherAccountsConversion() {
        val existing = leg("90.89657258", account = other)
        assertNull(matchOne(listOf(existing), incoming("2021-01-01T09:43:33Z", "90.89657258")))
    }

    @Test
    fun doesNotMatchOutsideTheWindow() {
        val existing = leg("90.89657258")
        assertNull(matchOne(listOf(existing), incoming("2021-01-01T09:44:33Z", "90.89657258")))
    }

    @Test
    fun tolerantOfASubSecondTimestampDisagreement() {
        // The export stamps the second; the dust endpoint stamps the millisecond.
        val existing = leg("90.89657258")
        assertNotNull(matchOne(listOf(existing), incoming("2021-01-01T09:43:33.781Z", "90.89657258")))
    }

    @Test
    fun twoIncomingTradesNeverBothTakeOneLeg() {
        // Two sweeps of the identical amount in one window are two movements; only one is suppressed.
        val existing = leg("90.89657258")
        val first = incoming("2021-01-01T09:43:33Z", "90.89657258")
        val second = incoming("2021-01-01T09:43:33Z", "90.89657258", key = "api-2")
        val reconciler = reconciler(listOf(existing), listOf(first, second))
        assertNotNull(reconciler.match(first))
        assertNull(reconciler.match(second))
    }

    @Test
    fun matchesEachSweptAssetAgainstItsOwnLeg() {
        val reefLeg = leg("90.89657258")
        val psgLeg = leg("0.00187671", debitedAsset = usdt)
        val reef = incoming("2021-01-01T09:43:33Z", "90.89657258")
        val psg = incoming("2021-01-01T09:43:33Z", "0.00187671", fromAsset = usdt, key = "api-2")
        val reconciler = reconciler(listOf(reefLeg, psgLeg), listOf(reef, psg))
        assertEquals(reefLeg.debitTransferId, reconciler.match(reef))
        assertEquals(psgLeg.debitTransferId, reconciler.match(psg))
    }

    @Test
    fun pairsUpTradesAndLegsWhoseWindowsOverlapInsteadOfStrandingOne() {
        // Two legs a window apart, and two trades between them: the 09:43:37 trade can reach both
        // legs, the 09:43:33 one only the first. Taking the nearest free leg as each trade arrives
        // lets the reachable-either-way trade take the leg the other one depends on, so whichever of
        // them the batch happens to list first decides whether the second reconciles at all. Both are
        // already recorded, so both must be suppressed however the batch is ordered.
        val early = leg("90.89657258")
        val late = leg("90.89657258", at = sweptAt + 9.seconds)
        val reachesBoth = incoming("2021-01-01T09:43:37Z", "90.89657258", key = "api-late")
        val reachesEarlyOnly = incoming("2021-01-01T09:43:33Z", "90.89657258", key = "api-early")

        for (batch in listOf(listOf(reachesBoth, reachesEarlyOnly), listOf(reachesEarlyOnly, reachesBoth))) {
            val reconciler = reconciler(listOf(early, late), batch)
            assertEquals(early.debitTransferId, reconciler.match(reachesEarlyOnly))
            assertEquals(late.debitTransferId, reconciler.match(reachesBoth))
        }
    }
}
