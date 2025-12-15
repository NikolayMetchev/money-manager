@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.ui.error.ProvideSchemaAwareScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class CurrenciesScreenTest {
    private class FakeCurrencyRepository(
        private val currencies: List<Currency>,
    ) : CurrencyRepository {
        private val currenciesFlow = MutableStateFlow(currencies)

        override fun getAllCurrencies(): Flow<List<Currency>> = currenciesFlow

        override fun getCurrencyById(id: CurrencyId): Flow<Currency?> {
            TODO("Not needed for this test")
        }

        override fun getCurrencyByCode(code: String): Flow<Currency?> {
            TODO("Not needed for this test")
        }

        override suspend fun upsertCurrencyByCode(
            code: String,
            name: String,
        ): CurrencyId {
            TODO("Not needed for this test")
        }

        override suspend fun updateCurrency(currency: Currency) {
            TODO("Not needed for this test")
        }

        override suspend fun deleteCurrency(id: CurrencyId) {
            TODO("Not needed for this test")
        }
    }

    @Test
    fun currenciesScreen_displaysEmptyState_whenNoCurrencies() =
        runComposeUiTest {
            val repository = FakeCurrencyRepository(emptyList())

            setContent {
                ProvideSchemaAwareScope {
                    CurrenciesScreen(currencyRepository = repository)
                }
            }

            onNodeWithText("No currencies yet. Add your first currency!").assertIsDisplayed()
        }

    @Test
    fun currenciesScreen_displaysCurrencies_whenCurrenciesExist() =
        runComposeUiTest {
            val testCurrency =
                Currency(
                    id = CurrencyId(Uuid.random()),
                    code = "USD",
                    name = "US Dollar",
                )
            val repository = FakeCurrencyRepository(listOf(testCurrency))

            setContent {
                ProvideSchemaAwareScope {
                    CurrenciesScreen(currencyRepository = repository)
                }
            }

            onNodeWithText("USD").assertIsDisplayed()
            onNodeWithText("US Dollar").assertIsDisplayed()
        }

    @Test
    fun currenciesScreen_displaysMultipleCurrencies() =
        runComposeUiTest {
            val currencies =
                listOf(
                    Currency(id = CurrencyId(Uuid.random()), code = "USD", name = "US Dollar"),
                    Currency(id = CurrencyId(Uuid.random()), code = "EUR", name = "Euro"),
                    Currency(id = CurrencyId(Uuid.random()), code = "GBP", name = "British Pound"),
                )
            val repository = FakeCurrencyRepository(currencies)

            setContent {
                ProvideSchemaAwareScope {
                    CurrenciesScreen(currencyRepository = repository)
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
        runComposeUiTest {
            val repository = FakeCurrencyRepository(emptyList())

            setContent {
                ProvideSchemaAwareScope {
                    CurrenciesScreen(currencyRepository = repository)
                }
            }

            onNodeWithText("+").performClick()

            onNodeWithText("Create New Currency").assertIsDisplayed()
        }

    @Test
    fun createCurrencyDialog_showsValidationError_whenCodeIsEmpty() =
        runComposeUiTest {
            val repository = FakeCurrencyRepository(emptyList())

            setContent {
                ProvideSchemaAwareScope {
                    CurrenciesScreen(currencyRepository = repository)
                }
            }

            onNodeWithText("+").performClick()
            onNodeWithText("Create").performClick()

            onNodeWithText("Currency code is required").assertIsDisplayed()
        }
}
