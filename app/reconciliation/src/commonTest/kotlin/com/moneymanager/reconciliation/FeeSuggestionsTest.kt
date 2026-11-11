package com.moneymanager.reconciliation

import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.PendingManualEntry
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class FeeSuggestionsTest {
    private val btc = Currency(CurrencyId(1), code = "BTC", name = "BTC", scaleFactor = 1_000_000_000_000_000_000L)
    private val binance = AccountId(2)
    private val wallet = AccountId(20)
    private val otherWallet = AccountId(22)
    private val walletFees = AccountId(21)
    private val otherFees = AccountId(23)
    private val feeAccounts = setOf(walletFees, otherFees)
    private val gross = Instant.parse("2021-05-26T19:49:05Z")
    private val onChain = Instant.parse("2021-05-26T19:48:36Z")

    private fun btc(decimal: String): Money {
        val (whole, fraction) = decimal.split('.').let { it[0] to it.getOrElse(1) { "" } }
        return Money(BigInteger(whole + fraction.padEnd(18, '0')), btc)
    }

    private val withdrawal =
        PendingManualEntry(TransferId(1), gross, "Withdraw", binance, "Binance", AccountId(3), "Funding", btc("0.27986608"))

    private fun transfer(
        id: Long,
        from: AccountId,
        to: AccountId,
        amount: String,
    ) = Transfer(
        id = TransferId(id),
        timestamp = onChain,
        description = "t$id",
        sourceAccountId = from,
        targetAccountId = to,
        amount = btc(amount),
    )

    private val net = transfer(10, wallet, AccountId(30), "0.27931608")
    private val fee = transfer(11, wallet, walletFees, "0.00055")

    @Test
    fun `the fee is the source fee when net plus fee is exactly the withdrawal`() {
        val suggestion = suggestFees(listOf(withdrawal), listOf(net, fee), feeAccounts).getValue(withdrawal.transferId)

        assertEquals(btc("0.00055"), suggestion.fee)
        assertEquals(TransferId(11), suggestion.feeTransfer.id)
    }

    @Test
    fun `no fee is suggested when net plus fee is not the withdrawal`() {
        val off = transfer(11, wallet, walletFees, "0.001")

        assertNull(suggestFees(listOf(withdrawal), listOf(net, off), feeAccounts)[withdrawal.transferId])
    }

    @Test
    fun `a fee already matched to another fee is not offered again`() {
        val claimed = setOf(fee.id)

        assertNull(suggestFees(listOf(withdrawal), listOf(net, fee), feeAccounts, claimedFeeTransfers = claimed)[withdrawal.transferId])
    }

    @Test
    fun `a wallet that is not the withdrawal account is not taken`() {
        val elsewhere = listOf(transfer(12, otherWallet, AccountId(31), "0.27931608"), transfer(13, otherWallet, otherFees, "0.00055"))
        val mirrors: (AccountId, AccountId) -> Boolean = { walletId, real -> walletId == wallet && real == binance }

        assertNull(suggestFees(listOf(withdrawal), elsewhere, feeAccounts, mirrors = mirrors)[withdrawal.transferId])
        assertEquals(
            TransferId(11),
            suggestFees(
                listOf(withdrawal),
                listOf(net, fee),
                feeAccounts,
                mirrors = mirrors,
            ).getValue(withdrawal.transferId).feeTransfer.id,
        )
    }

    @Test
    fun `two pairs fitting one withdrawal is left to a person`() {
        val twinNet = transfer(12, otherWallet, AccountId(31), "0.27931608")
        val twinFee = transfer(13, otherWallet, otherFees, "0.00055")

        assertEquals(emptyMap(), suggestFees(listOf(withdrawal), listOf(net, fee, twinNet, twinFee), feeAccounts))
    }
}
