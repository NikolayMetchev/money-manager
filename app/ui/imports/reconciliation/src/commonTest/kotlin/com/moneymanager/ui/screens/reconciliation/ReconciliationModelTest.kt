package com.moneymanager.ui.screens.reconciliation

import com.moneymanager.domain.model.AccountId
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
