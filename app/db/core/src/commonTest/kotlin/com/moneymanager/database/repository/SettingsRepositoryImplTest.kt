package com.moneymanager.database.repository
import com.moneymanager.importengineapi.setSetupWizardCompleted
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsRepositoryImplTest : DbTest() {
    @Test
    fun `fresh database returns null default currency`() =
        runDbTest {
            val result = repositories.settingsRepository.getDefaultCurrencyId().first()
            assertNull(result)
        }

    @Test
    fun `set and get default currency`() =
        runDbTest {
            val currencies = repositories.currencyRepository.getAllCurrencies().first()
            val eurCurrency = currencies.first { it.code == "EUR" }

            repositories.settingsRepository.setDefaultCurrencyId(eurCurrency.id)

            val result = repositories.settingsRepository.getDefaultCurrencyId().first()
            assertNotNull(result)
            assertEquals(eurCurrency.id, result)
        }

    @Test
    fun `update default currency replaces previous value`() =
        runDbTest {
            val currencies = repositories.currencyRepository.getAllCurrencies().first()
            val eurCurrency = currencies.first { it.code == "EUR" }
            val usdCurrency = currencies.first { it.code == "USD" }

            repositories.settingsRepository.setDefaultCurrencyId(eurCurrency.id)
            assertEquals(eurCurrency.id, repositories.settingsRepository.getDefaultCurrencyId().first())

            repositories.settingsRepository.setDefaultCurrencyId(usdCurrency.id)
            assertEquals(usdCurrency.id, repositories.settingsRepository.getDefaultCurrencyId().first())
        }

    @Test
    fun `settings columns are independent - writing one does not clear the other`() =
        runDbTest {
            val eur =
                repositories.currencyRepository
                    .getAllCurrencies()
                    .first()
                    .first { it.code == "EUR" }

            // Complete the wizard first, then set the default currency: the currency upsert must not
            // clear the flag, and vice versa.
            repositories.settingsRepository.setSetupWizardCompleted(true)
            repositories.settingsRepository.setDefaultCurrencyId(eur.id)

            assertTrue(repositories.settingsRepository.isSetupWizardCompleted().first())
            assertEquals(eur.id, repositories.settingsRepository.getDefaultCurrencyId().first())
        }

    @Test
    fun `fresh database has not completed the setup wizard`() =
        runDbTest {
            assertFalse(repositories.settingsRepository.isSetupWizardCompleted().first())
        }

    @Test
    fun `set and get setup wizard completed`() =
        runDbTest {
            repositories.settingsRepository.setSetupWizardCompleted(true)
            assertTrue(repositories.settingsRepository.isSetupWizardCompleted().first())

            repositories.settingsRepository.setSetupWizardCompleted(false)
            assertFalse(repositories.settingsRepository.isSetupWizardCompleted().first())
        }

    @Test
    fun `import engine records setup wizard completion`() =
        runDbTest {
            repositories.importEngine.setSetupWizardCompleted(true)

            assertTrue(repositories.settingsRepository.isSetupWizardCompleted().first())
        }

    @Test
    fun `completing the setup wizard does not clear the default currency`() =
        runDbTest {
            val eur =
                repositories.currencyRepository
                    .getAllCurrencies()
                    .first()
                    .first { it.code == "EUR" }

            repositories.settingsRepository.setDefaultCurrencyId(eur.id)
            repositories.settingsRepository.setSetupWizardCompleted(true)

            assertEquals(eur.id, repositories.settingsRepository.getDefaultCurrencyId().first())
            assertTrue(repositories.settingsRepository.isSetupWizardCompleted().first())
        }
}
