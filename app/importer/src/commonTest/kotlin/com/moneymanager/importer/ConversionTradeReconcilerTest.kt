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

    /** Every conversion leg in these tests belongs to the one sweep; only the trades move around it. */
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
    ) = ExistingConversionLeg(
        debitTransferId = TransferId(nextTransferId++),
        accountId = account,
        amount = money(debited, debitedAsset),
        timestamp = sweptAt,
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
        window: Long = 5,
    ) = ConversionTradeReconciler(window.seconds, legs)

    @Test
    fun matchesTheDebitedLegWhateverTheCreditedAmountIs() {
        // The export credits 0.03251993 BNB, the API 0.03318361: the difference is the service charge,
        // so the credited amount is never compared.
        val existing = leg("90.89657258")
        assertEquals(existing.debitTransferId, reconciler(listOf(existing)).match(incoming("2021-01-01T09:43:33Z", "90.89657258")))
    }

    @Test
    fun doesNotMatchADifferentDebitedAmount() {
        val existing = leg("90.89657258")
        assertNull(reconciler(listOf(existing)).match(incoming("2021-01-01T09:43:33Z", "90.89657259")))
    }

    @Test
    fun doesNotMatchWhenTheCreditedAssetDiffers() {
        // A genuine REEF sale for USDT at the same instant is not the sweep, however alike the debits.
        val existing = leg("90.89657258")
        assertNull(
            reconciler(listOf(existing))
                .match(incoming("2021-01-01T09:43:33Z", "90.89657258", to = "12.5", toAsset = usdt)),
        )
    }

    @Test
    fun doesNotMatchAnotherAccountsConversion() {
        val existing = leg("90.89657258", account = other)
        assertNull(reconciler(listOf(existing)).match(incoming("2021-01-01T09:43:33Z", "90.89657258")))
    }

    @Test
    fun doesNotMatchOutsideTheWindow() {
        val existing = leg("90.89657258")
        assertNull(reconciler(listOf(existing)).match(incoming("2021-01-01T09:44:33Z", "90.89657258")))
    }

    @Test
    fun tolerantOfASubSecondTimestampDisagreement() {
        // The export stamps the second; the dust endpoint stamps the millisecond.
        val existing = leg("90.89657258")
        assertNotNull(reconciler(listOf(existing)).match(incoming("2021-01-01T09:43:33.781Z", "90.89657258")))
    }

    @Test
    fun twoIncomingTradesNeverBothTakeOneLeg() {
        // Two sweeps of the identical amount in one window are two movements; only one is suppressed.
        val existing = leg("90.89657258")
        val reconciler = reconciler(listOf(existing))
        assertNotNull(reconciler.match(incoming("2021-01-01T09:43:33Z", "90.89657258")))
        assertNull(reconciler.match(incoming("2021-01-01T09:43:33Z", "90.89657258", key = "api-2")))
    }

    @Test
    fun matchesEachSweptAssetAgainstItsOwnLeg() {
        val reefLeg = leg("90.89657258")
        val psgLeg = leg("0.00187671", debitedAsset = usdt)
        val reconciler = reconciler(listOf(reefLeg, psgLeg))
        assertEquals(reefLeg.debitTransferId, reconciler.match(incoming("2021-01-01T09:43:33Z", "90.89657258")))
        assertEquals(
            psgLeg.debitTransferId,
            reconciler.match(incoming("2021-01-01T09:43:33Z", "0.00187671", fromAsset = usdt, key = "api-2")),
        )
    }
}
