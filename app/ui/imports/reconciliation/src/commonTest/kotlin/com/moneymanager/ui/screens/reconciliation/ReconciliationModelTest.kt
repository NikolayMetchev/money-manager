package com.moneymanager.ui.screens.reconciliation

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.reconciliation.LegTransactionKind
import com.moneymanager.domain.model.reconciliation.ReconciliationLeg
import com.moneymanager.reconciliation.LegMatch
import com.moneymanager.reconciliation.ReconciliationResult
import com.moneymanager.reconciliation.UnmatchedRealLeg
import com.moneymanager.reconciliation.UnmatchedSourceLeg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class ReconciliationModelTest {
    @Test
    fun groupsByWalletNameThenMonth() {
        val binance = AccountId(1)
        val coinbase = AccountId(2)
        val items =
            listOf(
                binance to "2021-05-15T00:00:00Z",
                coinbase to "2020-06-01T00:00:00Z",
                binance to "2021-04-30T23:59:59Z",
                binance to "2021-05-01T00:00:00Z",
            ).map { (wallet, at) -> wallet to Instant.parse(at) }

        val groups =
            groupByWalletAndMonth(items, { it.first }, { it.second }) { if (it == binance) "Binance" else "Coinbase" }

        assertEquals(listOf("Binance", "Coinbase"), groups.map { it.walletName })
        assertEquals(listOf("2021-04" to 1, "2021-05" to 2), groups[0].months.map { it.month to it.items.size })
        assertEquals(3, groups[0].count)
    }

    private val ada = CryptoAsset(id = CryptoId(1), code = "ADA", name = "Cardano")
    private val eth = CryptoAsset(id = CryptoId(2), code = "ETH", name = "Ether")
    private val wallet = AccountId(100)
    private val otherWallet = AccountId(200)
    private val binance = AccountId(1)
    private val t0 = Instant.parse("2024-01-10T12:00:00Z")
    private var nextId = 1L

    private fun leg(
        account: AccountId,
        amount: String,
        at: Instant,
        asset: CryptoAsset = ada,
        movement: String? = null,
    ) = (nextId++).let { id ->
        ReconciliationLeg(
            transactionId = id,
            kind = LegTransactionKind.TRANSFER,
            timestamp = at,
            description = "",
            accountId = account,
            counterpartyAccountId = AccountId(999),
            amount = Money.fromDisplayValue(BigDecimal(amount), asset),
            movementKey = movement ?: "m$id",
        )
    }

    @Test
    fun pickerListsUnclaimedLegsOfTheWalletWithinTheWindowNearestFirst() {
        val koinly = leg(wallet, "5", t0)
        val near = leg(binance, "5", t0 + 1.hours)
        val nearSource = leg(wallet, "5", t0 - 1.hours)
        val far = leg(binance, "4", t0 + 5.days)
        val close = leg(binance, "4.9", t0 - 2.hours)
        val otherAsset = leg(binance, "1", t0 + 3.hours, asset = eth)
        val otherGroup = leg(AccountId(2), "4.9", t0)
        val result =
            ReconciliationResult(
                sourceRange = null,
                matches = listOf(LegMatch(nearSource, near)),
                missingInMm = listOf(UnmatchedSourceLeg(koinly, wallet, assetUnknownToMm = false)),
                missingInSource = emptyList(),
                unclaimedReal =
                    listOf(
                        UnmatchedRealLeg(far, wallet),
                        UnmatchedRealLeg(close, wallet),
                        UnmatchedRealLeg(otherAsset, wallet),
                        UnmatchedRealLeg(otherGroup, otherWallet),
                    ),
            )

        val sameAsset = result.pickerCandidates(listOf(koinly), wallet, window = 3.days, sameAssetOnly = true)
        assertEquals(listOf(near to false, close to true), sameAsset.map { it.leg to it.selectable })

        val anyAsset = result.pickerCandidates(listOf(koinly), wallet, window = 7.days, sameAssetOnly = false)
        assertEquals(listOf(near, close, otherAsset, far), anyAsset.map { it.leg })
    }

    @Test
    fun aMovementIsEveryUnmatchedLegOfItsRowOnTheWallet() {
        val trade = leg(wallet, "5", t0, movement = "c1:3")
        val fee = leg(wallet, "-0.1", t0, movement = "c1:3")
        val other = leg(wallet, "7", t0, movement = "c1:4")
        val unmatched = listOf(trade, fee, other).map { UnmatchedSourceLeg(it, wallet, assetUnknownToMm = false) }
        val result = ReconciliationResult(null, emptyList(), unmatched, emptyList())

        assertEquals(listOf(trade, fee), result.movementOf(unmatched[0]))
    }

    @Test
    fun selectionTotalsArePerAsset() {
        val movement = listOf(leg(wallet, "5", t0), leg(wallet, "-0.1", t0, asset = eth))
        val selected = listOf(leg(binance, "3", t0), leg(binance, "1.5", t0))

        val totals = selectionTotals(movement, selected)

        assertEquals(listOf("ADA", "ETH"), totals.map { it.asset.code })
        assertEquals(Money.fromDisplayValue(BigDecimal("-0.5"), ada), totals[0].difference)
        assertEquals(Money.fromDisplayValue(BigDecimal("0.1"), eth), totals[1].difference)
    }

    @Test
    fun matchesSharingATransactionOnOneWalletMergeIntoOneRelationship() {
        val w1 = AccountId(1)
        val w2 = AccountId(2)
        val trade = leg(w1, "5", t(0))
        val real1 = leg(AccountId(10), "3", t(0))
        val real2 = leg(AccountId(10), "2", t(0))
        val other = leg(w1, "9", t(1))
        val otherReal = leg(AccountId(10), "9", t(1))
        // The same trade on another wallet's link group stays a relationship of its own.
        val elsewhere = trade.copy(accountId = w2)
        val matches =
            listOf(
                LegMatch(listOf(trade), listOf(real1)),
                LegMatch(listOf(trade), listOf(real2)),
                LegMatch(listOf(other), listOf(otherReal)),
                LegMatch(listOf(elsewhere), listOf(real1)),
            )

        val merged = mergedMatchMembers(matches).map { it.toSet() }

        assertEquals(
            setOf(
                setOf(trade.transactionRef, real1.transactionRef, real2.transactionRef),
                setOf(other.transactionRef, otherReal.transactionRef),
                setOf(elsewhere.transactionRef, real1.transactionRef),
            ),
            merged.toSet(),
        )
        assertEquals(3, merged.size)
    }

    private fun t(hours: Int) = Instant.parse("2024-01-10T00:00:00Z") + hours.hours
}
