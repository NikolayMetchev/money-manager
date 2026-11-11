@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.screens.transactions

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.CurrencyScaleFactors
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Trade
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.LegAssembly
import com.moneymanager.domain.model.csvstrategy.LegGroupRule
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportResult
import com.moneymanager.importengineapi.TradeAttributeMutation
import com.moneymanager.presenter.error.ProvideSchemaAwareScope
import com.moneymanager.tasks.Progress
import com.moneymanager.ui.foundation.LocalImportEngine
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The part of Manual Entries that asks for the currency and amount of an exchange that named neither. */
class ManualEntriesTradesTest {
    private val at = Instant.parse("2020-07-01T10:00:00Z")

    private fun currency(
        id: Long,
        code: String,
    ) = Currency(CurrencyId(id), code = code, name = code, scaleFactor = CurrencyScaleFactors.DEFAULT_SCALE_FACTOR)

    private val eur = currency(1, "EUR")
    private val usd = currency(2, "USD")
    private val unknown = currency(3, "UNKNOWN")
    private val revolut = AccountId(1)

    private val placeholder =
        Trade(
            TradeId(1),
            timestamp = at,
            description = "Transfer to Revolut Ltd",
            fromAccountId = revolut,
            from = Money.fromDisplayValue("300", eur),
            toAccountId = revolut,
            to = Money.fromDisplayValue("300", unknown),
        )

    private val strategy =
        CsvImportStrategy(
            id = CsvImportStrategyId(Uuid.random()),
            name = "Exchange CSV",
            config =
                CsvStrategyConfig(
                    identificationColumns = emptySet(),
                    fieldMappings = emptyMap(),
                    legGroups =
                        listOf(
                            LegGroupRule(name = "x", assembly = LegAssembly.Trade(acrossFiles = true, unpairedAsset = "UNKNOWN")),
                        ),
                ),
            createdAt = at,
            updatedAt = at,
        )

    private class RecordingEngine : ImportEngine {
        val batches = mutableListOf<ImportBatch>()

        override suspend fun import(
            batch: ImportBatch,
            onProgress: (suspend (Progress) -> Unit)?,
            batchSize: Int,
        ): ImportResult {
            batches += batch
            return ImportResult(
                attributeTypeIds = batch.attributeTypeNames.associateWith { AttributeTypeId(7) },
                createdTradeIds = batch.trades.mapIndexed { index, trade -> trade.key to TradeId(500L + index) }.toMap(),
            )
        }
    }

    private val maintenance =
        object : Maintenance {
            override suspend fun reindex(): Duration = Duration.ZERO

            override suspend fun vacuum(): Duration = Duration.ZERO

            override suspend fun analyze(): Duration = Duration.ZERO

            override suspend fun refreshMaterializedViews(): Duration = Duration.ZERO

            override suspend fun fullRefreshMaterializedViews(): Duration = Duration.ZERO
        }

    /** The one place this file calls the screen, so a change to what the screen takes is a change here only. */
    private fun androidx.compose.ui.test.ComposeUiTest.showScreen(engine: RecordingEngine) {
        val strategies: CsvImportStrategyReadRepository =
            mock(MockMode.autoUnit) {
                every { getAllStrategies() } returns
                    flowOf(listOf(strategy))
            }
        val transactions: TransactionReadRepository =
            mock(MockMode.autoUnit) { every { getPendingManualEntries(any(), any()) } returns flowOf(emptyList()) }
        val trades: TradeReadRepository =
            mock(MockMode.autoUnit) { every { getPendingManualTrades(any(), any()) } returns flowOf(listOf(placeholder)) }
        val currencies: CurrencyReadRepository =
            mock(MockMode.autoUnit) { every { getAllCurrencies() } returns flowOf(listOf(eur, usd, unknown)) }
        setContent {
            ProvideSchemaAwareScope {
                CompositionLocalProvider(LocalImportEngine provides engine) {
                    ManualEntriesScreen(
                        csvImportStrategyRepository = strategies,
                        transactionRepository = transactions,
                        tradeRepository = trades,
                        currencyRepository = currencies,
                        maintenance = maintenance,
                        onTransactionsImported = {},
                    )
                }
            }
        }
    }

    @Test
    fun `an exchange that named no currency is completed with a currency and an amount`() =
        runMoneyManagerComposeUiTest {
            val engine = RecordingEngine()
            showScreen(engine)
            waitUntilAtLeastOneExists(hasText("Exchanges with no named currency"))
            onNodeWithText("Create 0 trades").assertExists()

            onNodeWithText("Currency").performClick()
            onNodeWithText("USD - USD").performClick()
            onNodeWithText("Amount").performTextInput("350")
            waitForIdle()
            onNodeWithText("Create 1 trade").performClick()
            waitUntil(timeoutMillis = 5_000) { engine.batches.any { it.tradeAttributeMutations.isNotEmpty() } }

            val created = engine.batches.flatMap { it.trades }.single()
            assertEquals(Money.fromDisplayValue("300", eur), created.fromAmount)
            assertEquals(Money.fromDisplayValue("350", usd), created.toAmount)
            val excluded = engine.batches.flatMap { it.tradeAttributeMutations }.single()
            assertIs<TradeAttributeMutation.Set>(excluded)
            assertEquals(placeholder.id, excluded.tradeId)
        }

    @Test
    fun `the placeholder itself and the known currency are refused`() =
        runMoneyManagerComposeUiTest {
            val engine = RecordingEngine()
            showScreen(engine)
            waitUntilAtLeastOneExists(hasText("Exchanges with no named currency"))

            onNodeWithText("Currency").performClick()
            onNodeWithText("UNKNOWN - UNKNOWN").performClick()
            onNodeWithText("Amount").performTextInput("350")
            waitForIdle()

            onNodeWithText("UNKNOWN is the placeholder, choose the real currency").assertExists()
            onNodeWithText("Create 0 trades").assertExists()
        }
}
