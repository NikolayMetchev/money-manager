@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.port.EntitySourcePort
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.ui.error.ProvideSchemaAwareScope
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class CurrenciesScreenTest {
    private val testDeviceId = DeviceId(1L)
    private val stubEntitySourcePort = createEntitySourcePort()

    private fun createCurrencyRepository(currencies: List<Currency>): CurrencyRepository =
        mock<CurrencyRepository>(MockMode.autoUnit) {
            every { getAllCurrencies() } returns flowOf(currencies)
            every { getCurrencyById(any()) } returns flowOf(null)
            every { getCurrencyByCode(any()) } returns flowOf(null)
            everySuspend { upsertCurrencyByCode(any(), any()) } returns CurrencyId(999L)
        }

    @Test
    fun currenciesScreen_displaysEmptyState_whenNoCurrencies() =
        runMoneyManagerComposeUiTest {
            val repository = createCurrencyRepository(emptyList())

            setContent {
                ProvideSchemaAwareScope {
                    CurrenciesScreen(
                        currencyRepository = repository,
                        entitySourcePort = stubEntitySourcePort,
                        deviceId = testDeviceId,
                    )
                }
            }

            onNodeWithText("No currencies yet. Add your first currency!").assertIsDisplayed()
        }

    @Test
    fun currenciesScreen_displaysCurrencies_whenCurrenciesExist() =
        runMoneyManagerComposeUiTest {
            val testCurrency =
                Currency(
                    id = CurrencyId(1L),
                    code = "USD",
                    name = "US Dollar",
                )
            val repository = createCurrencyRepository(listOf(testCurrency))

            setContent {
                ProvideSchemaAwareScope {
                    CurrenciesScreen(
                        currencyRepository = repository,
                        entitySourcePort = stubEntitySourcePort,
                        deviceId = testDeviceId,
                    )
                }
            }

            onNodeWithText("USD").assertIsDisplayed()
            onNodeWithText("US Dollar").assertIsDisplayed()
        }

    @Test
    fun currenciesScreen_displaysMultipleCurrencies() =
        runMoneyManagerComposeUiTest {
            val currencies =
                listOf(
                    Currency(id = CurrencyId(1L), code = "USD", name = "US Dollar"),
                    Currency(id = CurrencyId(2L), code = "EUR", name = "Euro"),
                    Currency(id = CurrencyId(3L), code = "GBP", name = "British Pound"),
                )
            val repository = createCurrencyRepository(currencies)

            setContent {
                ProvideSchemaAwareScope {
                    CurrenciesScreen(
                        currencyRepository = repository,
                        entitySourcePort = stubEntitySourcePort,
                        deviceId = testDeviceId,
                    )
                }
            }

            onNodeWithText("USD").assertIsDisplayed()
            onNodeWithText("US Dollar").assertIsDisplayed()
            onNodeWithText("EUR").assertIsDisplayed()
            onNodeWithText("Euro").assertIsDisplayed()
            onNodeWithText("GBP").assertIsDisplayed()
            onNodeWithText("British Pound").assertIsDisplayed()
        }

    @Test
    fun currenciesScreen_opensCreateDialog_whenFabClicked() =
        runMoneyManagerComposeUiTest {
            val repository = createCurrencyRepository(emptyList())

            setContent {
                ProvideSchemaAwareScope {
                    CurrenciesScreen(
                        currencyRepository = repository,
                        entitySourcePort = stubEntitySourcePort,
                        deviceId = testDeviceId,
                    )
                }
            }

            onNodeWithText("+").performClick()

            onNodeWithText("Create New Currency").assertIsDisplayed()
        }

    @Test
    fun createCurrencyDialog_showsValidationError_whenCodeIsEmpty() =
        runMoneyManagerComposeUiTest {
            val repository = createCurrencyRepository(emptyList())

            setContent {
                ProvideSchemaAwareScope {
                    CurrenciesScreen(
                        currencyRepository = repository,
                        entitySourcePort = stubEntitySourcePort,
                        deviceId = testDeviceId,
                    )
                }
            }

            onNodeWithText("+").performClick()
            onNodeWithText("Create").performClick()

            onNodeWithText("Currency code is required").assertIsDisplayed()
        }

    private fun createEntitySourcePort(): EntitySourcePort = mock(MockMode.autoUnit)
}

