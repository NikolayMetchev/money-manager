@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.DeviceId
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.ui.error.ProvideSchemaAwareScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class CurrenciesScreenTest {
    private val testDeviceId = DeviceId(1L)
    private val stubEntitySourceQueries = createStubEntitySourceQueries()

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
                    CurrenciesScreen(
                        currencyRepository = repository,
                        entitySourceQueries = stubEntitySourceQueries,
                        deviceId = testDeviceId,
                    )
                }
            }

            onNodeWithText("No currencies yet. Add your first currency!").assertIsDisplayed()
        }

    @Test
    fun currenciesScreen_displaysCurrencies_whenCurrenciesExist() =
        runComposeUiTest {
            val testCurrency =
                Currency(
                    id = CurrencyId(1L),
                    code = "USD",
                    name = "US Dollar",
                )
            val repository = FakeCurrencyRepository(listOf(testCurrency))

            setContent {
                ProvideSchemaAwareScope {
                    CurrenciesScreen(
                        currencyRepository = repository,
                        entitySourceQueries = stubEntitySourceQueries,
                        deviceId = testDeviceId,
                    )
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
                    Currency(id = CurrencyId(1L), code = "USD", name = "US Dollar"),
                    Currency(id = CurrencyId(2L), code = "EUR", name = "Euro"),
                    Currency(id = CurrencyId(3L), code = "GBP", name = "British Pound"),
                )
            val repository = FakeCurrencyRepository(currencies)

            setContent {
                ProvideSchemaAwareScope {
                    CurrenciesScreen(
                        currencyRepository = repository,
                        entitySourceQueries = stubEntitySourceQueries,
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
        runComposeUiTest {
            val repository = FakeCurrencyRepository(emptyList())

            setContent {
                ProvideSchemaAwareScope {
                    CurrenciesScreen(
                        currencyRepository = repository,
                        entitySourceQueries = stubEntitySourceQueries,
                        deviceId = testDeviceId,
                    )
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
                    CurrenciesScreen(
                        currencyRepository = repository,
                        entitySourceQueries = stubEntitySourceQueries,
                        deviceId = testDeviceId,
                    )
                }
            }

            onNodeWithText("+").performClick()
            onNodeWithText("Create").performClick()

            onNodeWithText("Currency code is required").assertIsDisplayed()
        }

    /**
     * Creates a stub EntitySourceQueries for tests that don't actually query entity sources.
     * Uses a minimal SqlDriver stub that throws NotImplementedError if actually invoked.
     */
    private companion object {
        fun createStubEntitySourceQueries(): com.moneymanager.database.sql.EntitySourceQueries {
            val stubDriver =
                object : app.cash.sqldelight.db.SqlDriver {
                    override fun close() = Unit

                    override fun currentTransaction(): app.cash.sqldelight.Transacter.Transaction? = null

                    override fun execute(
                        identifier: Int?,
                        sql: String,
                        parameters: Int,
                        binders: (app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit)?,
                    ): app.cash.sqldelight.db.QueryResult<Long> {
                        throw NotImplementedError("Stub SqlDriver - should not be called in display-only tests")
                    }

                    override fun <R> executeQuery(
                        identifier: Int?,
                        sql: String,
                        mapper: (app.cash.sqldelight.db.SqlCursor) -> app.cash.sqldelight.db.QueryResult<R>,
                        parameters: Int,
                        binders: (app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit)?,
                    ): app.cash.sqldelight.db.QueryResult<R> {
                        throw NotImplementedError("Stub SqlDriver - should not be called in display-only tests")
                    }

                    override fun newTransaction(): app.cash.sqldelight.db.QueryResult<app.cash.sqldelight.Transacter.Transaction> {
                        throw NotImplementedError("Stub SqlDriver - should not be called in display-only tests")
                    }

                    override fun addListener(
                        vararg queryKeys: String,
                        listener: app.cash.sqldelight.Query.Listener,
                    ) = Unit

                    override fun removeListener(
                        vararg queryKeys: String,
                        listener: app.cash.sqldelight.Query.Listener,
                    ) = Unit

                    override fun notifyListeners(vararg queryKeys: String) = Unit
                }

            return com.moneymanager.database.sql.EntitySourceQueries(stubDriver)
        }
    }
}
