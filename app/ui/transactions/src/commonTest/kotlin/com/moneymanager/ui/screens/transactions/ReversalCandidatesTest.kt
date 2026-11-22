package com.moneymanager.ui.screens.transactions

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class ReversalCandidatesTest {
    private val gbp = CryptoAsset(id = CryptoId(1), code = "GBX", name = "Test pound")
    private val other = CryptoAsset(id = CryptoId(2), code = "USX", name = "Test dollar")
    private val monzo = AccountId(1)
    private val marcus = AccountId(2)
    private val goldman = AccountId(3)
    private val shop = AccountId(4)
    private val t0 = Instant.parse("2022-11-14T22:54:00Z")

    private fun transfer(
        id: Long,
        from: AccountId,
        to: AccountId,
        after: Duration = Duration.ZERO,
        amount: Long = 1000,
        asset: CryptoAsset = gbp,
    ) = Transfer(
        TransferId(id),
        timestamp = t0 + after,
        description = "",
        sourceAccountId = from,
        targetAccountId = to,
        amount = Money(amount, asset),
    )

    private val payment = transfer(1, monzo, marcus)

    @Test
    fun `a return from another account into the account the payment left is offered`() {
        val returned = transfer(2, goldman, monzo, after = 1.hours)
        assertEquals(listOf(returned), reversalCandidates(payment, listOf(payment, returned)))
    }

    @Test
    fun `seen from the return the payment it reverses is offered`() {
        val returned = transfer(2, goldman, monzo, after = 1.hours)
        assertEquals(listOf(payment), reversalCandidates(returned, listOf(payment, returned)))
    }

    @Test
    fun `the same direction, another amount, another asset and a distant date are not offered`() {
        val retry = transfer(2, monzo, marcus, after = 17.hours)
        val smaller = transfer(3, goldman, monzo, after = 1.hours, amount = 999)
        val dollars = transfer(4, goldman, monzo, after = 1.hours, asset = other)
        val late = transfer(5, goldman, monzo, after = 31.days)
        val unrelated = transfer(6, shop, goldman, after = 1.hours)
        assertEquals(emptyList(), reversalCandidates(payment, listOf(retry, smaller, dollars, late, unrelated)))
    }

    @Test
    fun `candidates come nearest first`() {
        val far = transfer(2, goldman, monzo, after = 3.days)
        val near = transfer(3, marcus, shop, after = 2.hours)
        assertEquals(listOf(near, far), reversalCandidates(payment, listOf(far, near)))
    }
}
