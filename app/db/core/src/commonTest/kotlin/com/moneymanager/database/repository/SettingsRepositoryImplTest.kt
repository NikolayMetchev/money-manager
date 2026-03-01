package com.moneymanager.database.repository

import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SettingsRepositoryImplTest : DbTest() {
    @Test
    fun `fresh database returns null default currency`() =
        runTest {
            val result = repositories.settingsRepository.getDefaultCurrencyId().first()
            assertNull(result)
        }

    @Test
    fun `set and get default currency`() =
        runTest {
            val currencies = repositories.currencyRepository.getAllCurrencies().first()
            val eurCurrency = currencies.first { it.code == "EUR" }

            repositories.settingsRepository.setDefaultCurrencyId(eurCurrency.id)

            val result = repositories.settingsRepository.getDefaultCurrencyId().first()
            assertNotNull(result)
            assertEquals(eurCurrency.id, result)
        }

    @Test
    fun `update default currency replaces previous value`() =
        runTest {
            val currencies = repositories.currencyRepository.getAllCurrencies().first()
            val eurCurrency = currencies.first { it.code == "EUR" }
            val usdCurrency = currencies.first { it.code == "USD" }

            repositories.settingsRepository.setDefaultCurrencyId(eurCurrency.id)
            assertEquals(eurCurrency.id, repositories.settingsRepository.getDefaultCurrencyId().first())

            repositories.settingsRepository.setDefaultCurrencyId(usdCurrency.id)
            assertEquals(usdCurrency.id, repositories.settingsRepository.getDefaultCurrencyId().first())
        }
}
