package com.moneymanager.reconciliation

import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Trade
import com.moneymanager.domain.model.TradeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class OtherSideSuggestionsTest {
    private val scale = 1_000_000_000_000_000_000L

    private fun asset(
        id: Long,
        code: String,
    ) = Currency(CurrencyId(id), code = code, name = code, scaleFactor = scale)

    private val gbp = asset(1, "GBP")
    private val btc = asset(2, "BTC")
    private val unknown = asset(3, "UNKNOWN")

    /** [units] of the asset in thousandths-of-a-billionth places: the 1e18-scaled integer for a decimal string. */
    private fun money(
        asset: Currency,
        decimal: String,
    ): Money {
        val (whole, fraction) = decimal.split('.').let { it[0] to it.getOrElse(1) { "" } }
        return Money(BigInteger(whole + fraction.padEnd(18, '0')), asset)
    }

    private fun trade(
        id: Long,
        at: String,
        from: Money,
        to: Money,
        account: Long = 1,
    ) = Trade(
        TradeId(id),
        timestamp = Instant.parse(at),
        description = "t$id",
        fromAccountId = AccountId(account),
        from = from,
        toAccountId = AccountId(account),
        to = to,
    )

    private val buy = trade(1, "2020-05-10T01:15:27Z", money(gbp, "1000.00"), money(unknown, "1000.00"))
    private val sell = trade(2, "2020-05-30T08:10:18Z", money(unknown, "1097.87"), money(gbp, "1097.87"))

    private fun koinlyBuy(at: String = "2020-05-10T01:15:27Z") = trade(10, at, money(gbp, "1000.0"), money(btc, "0.1330735"), account = 9)

    private fun koinlySell(
        gbpAmount: String,
        id: Long = 11,
    ) = trade(id, "2020-05-30T08:10:18Z", money(btc, "0.1330735"), money(gbp, gbpAmount), account = 9)

    @Test
    fun `a placeholder on the from side takes the to side of the source trade`() {
        val suggestion = suggestOtherSides(listOf(buy to "UNKNOWN"), listOf(koinlyBuy())).getValue(buy.id)

        assertEquals(money(btc, "0.1330735"), suggestion.otherSide)
        assertEquals(TradeId(10), suggestion.shadowTrade.id)
    }

    @Test
    fun `a placeholder on the to side takes the from side of the source trade at the decimals both keep`() {
        val rounded = suggestOtherSides(listOf(sell to "UNKNOWN"), listOf(koinlySell("1097.87204055"))).getValue(sell.id)
        val cut = suggestOtherSides(listOf(sell to "UNKNOWN"), listOf(koinlySell("1097.879"))).getValue(sell.id)

        assertEquals(money(btc, "0.1330735"), rounded.otherSide)
        assertEquals(money(btc, "0.1330735"), cut.otherSide)
    }

    @Test
    fun `nothing is suggested for another second or another amount or another side`() {
        assertNull(suggestOtherSides(listOf(buy to "UNKNOWN"), listOf(koinlyBuy("2020-05-10T01:15:29Z"))).get(buy.id))
        assertNull(suggestOtherSides(listOf(sell to "UNKNOWN"), listOf(koinlySell("1098.50"))).get(sell.id))
        assertNull(suggestOtherSides(listOf(sell to "UNKNOWN"), listOf(koinlyBuy())).get(sell.id))
    }

    @Test
    fun `two source trades fitting one placeholder is left to a person`() {
        val twin = koinlyBuy().copy(id = TradeId(12))

        assertNull(suggestOtherSides(listOf(buy to "UNKNOWN"), listOf(koinlyBuy(), twin)).get(buy.id))
    }

    @Test
    fun `one source trade fitting two placeholders is left to a person`() {
        val second = buy.copy(id = TradeId(3))

        assertEquals(emptyMap(), suggestOtherSides(listOf(buy to "UNKNOWN", second to "UNKNOWN"), listOf(koinlyBuy())))
    }
}
