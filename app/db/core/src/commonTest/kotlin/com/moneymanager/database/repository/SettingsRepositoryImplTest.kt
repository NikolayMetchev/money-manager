@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock

class SettingsRepositoryImplTest : DbTest() {
    private suspend fun createAccount(name: String): AccountId {
        repositories.accountRepository.createAccount(
            Account(id = AccountId(0), name = name, openingDate = Clock.System.now()),
        )
        return repositories.accountRepository.getAllAccounts().first().first { it.name == name }.id
    }

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

    @Test
    fun `fresh database returns null last qif account`() =
        runTest {
            assertNull(repositories.settingsRepository.getLastQifAccountId().first())
        }

    @Test
    fun `set and get last qif account`() =
        runTest {
            val accountId = createAccount("Cash Account")

            repositories.settingsRepository.setLastQifAccountId(accountId)

            assertEquals(accountId, repositories.settingsRepository.getLastQifAccountId().first())
        }

    @Test
    fun `settings columns are independent - writing one does not clear the other`() =
        runTest {
            val eur = repositories.currencyRepository.getAllCurrencies().first().first { it.code == "EUR" }
            val accountId = createAccount("Cash Account")

            // Set the last QIF account first, then the default currency: the currency upsert must
            // not wipe the account, and vice versa.
            repositories.settingsRepository.setLastQifAccountId(accountId)
            repositories.settingsRepository.setDefaultCurrencyId(eur.id)

            assertEquals(accountId, repositories.settingsRepository.getLastQifAccountId().first())
            assertEquals(eur.id, repositories.settingsRepository.getDefaultCurrencyId().first())
        }
}
