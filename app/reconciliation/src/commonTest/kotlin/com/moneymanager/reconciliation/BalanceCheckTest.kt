package com.moneymanager.reconciliation

import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.reconciliation.LegTransactionKind
import com.moneymanager.domain.model.reconciliation.ReconciliationLeg
import com.moneymanager.domain.model.reconciliation.ReportedBalance
import com.moneymanager.domain.model.reconciliation.ReportedBalanceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class BalanceCheckTest {
    private val coin = CryptoAsset(id = CryptoId(1), code = "GBX", name = "Test pound")
    private val other = CryptoAsset(id = CryptoId(2), code = "USX", name = "Test dollar")
    private val account = AccountId(1)
    private val t0 = Instant.parse("2025-01-01T09:00:00Z")
    private var nextId = 1L

    private fun money(
        amount: String,
        asset: CryptoAsset = coin,
    ) = Money.fromDisplayValue(BigDecimal(amount), asset)

    private fun leg(
        amount: String,
        day: Int,
        asset: CryptoAsset = coin,
        excluded: Boolean = false,
    ) = ReconciliationLeg(
        transactionId = nextId++,
        kind = LegTransactionKind.TRANSFER,
        timestamp = t0 + day.days,
        description = "",
        accountId = account,
        counterpartyAccountId = AccountId(2),
        amount = money(amount, asset),
        excludedReason = if (excluded) "declined" else null,
    )

    private fun balance(
        amount: String,
        day: Int,
        asset: CryptoAsset = coin,
    ) = ReportedBalance(ReportedBalanceId(nextId++), t0 + day.days, money(amount, asset), revisionId = 1)

    @Test
    fun `a statement that agrees has no breaks`() {
        val legs = listOf(leg("100", 0), leg("-30", 1), leg("5", 2))
        val result = checkBalances(coin, listOf(balance("100", 0), balance("70", 1), balance("75", 2)), legs)

        assertEquals(money("0"), result.openingOffset)
        assertTrue(result.breaks.isEmpty())
        assertEquals(money("75"), result.latest?.computed)
    }

    @Test
    fun `history before the first leg is the opening offset, not a break`() {
        val legs = listOf(leg("-30", 1), leg("5", 2))
        val result = checkBalances(coin, listOf(balance("470", 1), balance("475", 2)), legs)

        assertEquals(money("500"), result.openingOffset)
        assertTrue(result.breaks.isEmpty())
    }

    @Test
    fun `a missing transaction breaks the balance where it happened`() {
        val legs = listOf(leg("100", 0), leg("5", 2))
        val result = checkBalances(coin, listOf(balance("100", 0), balance("70", 1), balance("75", 2)), legs)

        val only = result.breaks.single()
        assertEquals(t0 + 1.days, only.checkpoint.timestamp)
        assertEquals(money("-30"), only.change)
        assertTrue(only.suspects.isEmpty())
    }

    @Test
    fun `a duplicated transaction is a break whose suspects include it`() {
        val legs = listOf(leg("100", 0), leg("-30", 1), leg("-30", 1))
        val result = checkBalances(coin, listOf(balance("100", 0), balance("70", 1)), legs)

        val only = result.breaks.single()
        assertEquals(money("30"), only.change)
        assertEquals(2, only.suspects.size)
    }

    @Test
    fun `excluded legs and other assets are not counted`() {
        val legs = listOf(leg("100", 0), leg("-30", 1, excluded = true), leg("999", 1, asset = other))
        val result = checkBalances(coin, listOf(balance("100", 0), balance("100", 1)), legs)

        assertTrue(result.breaks.isEmpty())
    }

    @Test
    fun `several balances at one instant compare the one after every record`() {
        val legs = listOf(leg("100", 0), leg("-10", 1), leg("-20", 1))
        // A statement's two rows at one instant: the order of the records within it doesn't matter.
        val result = checkBalances(coin, listOf(balance("100", 0), balance("80", 1), balance("70", 1)), legs)

        assertTrue(result.breaks.isEmpty())
        assertEquals(2, result.checkpoints.size)
        assertEquals(money("70"), result.checkpoints.last().reportedBalance)
    }

    @Test
    fun `a break that a later one undoes shows twice`() {
        // Money Manager books the payment a day before the statement does.
        val legs = listOf(leg("100", 0), leg("-30", 1))
        val result = checkBalances(coin, listOf(balance("100", 0), balance("100", 1), balance("70", 2)), legs)

        assertEquals(listOf(money("30"), money("-30")), result.breaks.map { it.change })
    }
}
