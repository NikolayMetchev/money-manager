package com.moneymanager.ui.util

import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.domain.model.Asset
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SampleDataGeneratorTest {
    private val assets: List<Asset> =
        listOf(
            // 0-decimal fiat: the scale factor that used to make the generator throw "Rounding necessary".
            Currency(id = CurrencyId(1), code = "JPY", name = "Japanese Yen", scaleFactor = 1),
            Currency(id = CurrencyId(2), code = "USD", name = "US Dollar", scaleFactor = 100),
            Currency(id = CurrencyId(3), code = "BHD", name = "Bahraini Dinar", scaleFactor = 1000),
            CryptoAsset(id = CryptoId(1), code = "ETH", name = "Ethereum"),
        )

    @Test
    fun randomMoneyRoundTripsExactlyForEveryScaleFactor() {
        val random = Random(42)
        for (asset in assets) {
            repeat(500) {
                val money = randomMoney(random, asset, 0, 10_000)
                assertEquals(asset, money.asset)
                // fromDisplayValue is what threw "Rounding necessary" on a 0-decimal currency; an exact
                // round-trip proves the generated amount fits the asset's precision.
                assertEquals(money, Money.fromDisplayValue(money.toDisplayValue(), asset))
            }
        }
    }

    @Test
    fun randomMoneyOnZeroDecimalCurrencyHasNoFraction() {
        val yen = assets.first { it.code == "JPY" }
        val random = Random(7)
        repeat(200) {
            val displayValue = randomMoney(random, yen, 1, 5_000).toDisplayValue().toString()
            assertTrue('.' !in displayValue, "JPY amount $displayValue must be whole")
        }
    }

    @Test
    fun randomMoneyIsAlwaysPositive() {
        val random = Random(1)
        val zero = BigInteger(0)
        for (asset in assets) {
            repeat(200) {
                val minMajor = if (asset.scaleFactor > 1) 0L else 1L
                val money = randomMoney(random, asset, minMajor, 3)
                assertTrue(money.amount > zero, "${asset.code} amount ${money.amount} must be > 0")
            }
        }
    }
}
