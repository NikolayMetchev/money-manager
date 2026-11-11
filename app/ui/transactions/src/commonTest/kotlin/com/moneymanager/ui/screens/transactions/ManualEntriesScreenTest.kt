@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.screens.transactions

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.CurrencyScaleFactors
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.PendingManualEntry
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Trade
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.LegAssembly
import com.moneymanager.domain.model.csvstrategy.LegGroupRule
import com.moneymanager.domain.model.reconciliation.ReconciliationFocus
import com.moneymanager.domain.model.reconciliation.ReconciliationLeg
import com.moneymanager.domain.model.reconciliation.ReconciliationSource
import com.moneymanager.domain.model.reconciliation.ShadowAccount
import com.moneymanager.domain.model.rules.RuleAction
import com.moneymanager.domain.model.rules.StrategyRule
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.CurrencyReadRepository
import com.moneymanager.domain.repository.ReconciliationReadRepository
import com.moneymanager.domain.repository.TradeReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportResult
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.RelationshipMutation
import com.moneymanager.presenter.error.ProvideSchemaAwareScope
import com.moneymanager.ui.foundation.LocalImportEngine
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The suggestions Manual Entries offers from a reconciliation source's own records, and what taking one writes. */
class ManualEntriesScreenTest {
    private val epoch = Instant.parse("2021-05-26T19:49:05Z")

    private fun currency(
        id: Long,
        code: String,
    ) = Currency(CurrencyId(id), code = code, name = code, scaleFactor = CurrencyScaleFactors.DEFAULT_SCALE_FACTOR)

    private val gbp = currency(1, "GBP")
    private val btc = currency(2, "BTC")
    private val unknown = currency(3, "UNKNOWN")
    private val revolut = AccountId(1)
    private val wallet = AccountId(20)
    private val walletFees = AccountId(21)

    private fun strategy(entry: RuleAction.ManualEntry? = null): CsvImportStrategy =
        CsvImportStrategy(
            id = CsvImportStrategyId(Uuid.random()),
            name = "Exchange CSV",
            config =
                CsvStrategyConfig(
                    identificationColumns = emptySet(),
                    fieldMappings = emptyMap(),
                    rules = listOfNotNull(entry?.let { StrategyRule(conditions = emptyList(), actions = listOf(it)) }),
                    legGroups =
                        listOf(
                            LegGroupRule(
                                name = "exchange",
                                assembly = LegAssembly.Trade(acrossFiles = true, unpairedAsset = "UNKNOWN"),
                            ),
                        ),
                ),
            createdAt = epoch,
            updatedAt = epoch,
        )

    /** Records every batch and answers with an id for whatever the batch creates. */
    private class RecordingEngine : ImportEngine {
        val batches = mutableListOf<ImportBatch>()

        override suspend fun import(
            batch: ImportBatch,
            onProgress: (suspend (com.moneymanager.tasks.Progress) -> Unit)?,
            batchSize: Int,
        ): ImportResult {
            batches += batch
            return ImportResult(
                attributeTypeIds = batch.attributeTypeNames.associateWith { AttributeTypeId(7) },
                relationshipTypeIds = batch.relationshipTypeNames.associateWith { RelationshipTypeId(3) },
                createdTradeIds = batch.trades.mapIndexed { index, trade -> trade.key to TradeId(500L + index) }.toMap(),
                createdTransferIds =
                    batch.transfers
                        .mapIndexedNotNull {
                            index,
                            transfer,
                            ->
                            transfer.rowKey?.let { it to TransferId(600L + index) }
                        }.toMap(),
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

    private val strategies: (CsvImportStrategy) -> CsvImportStrategyReadRepository = { strategy ->
        mock(MockMode.autoUnit) { every { getAllStrategies() } returns flowOf(listOf(strategy)) }
    }

    private val reconciliation: ReconciliationReadRepository =
        mock(MockMode.autoUnit) {
            every { getShadowAccounts() } returns
                flowOf(
                    listOf(
                        ShadowAccount(wallet, "Koinly · Binance", "Koinly"),
                        ShadowAccount(walletFees, "Koinly · Binance Fees", "Koinly", feeAccountFor = wallet),
                    ),
                )
            every { getLinks() } returns flowOf(emptyList())
            every { getSources() } returns flowOf(listOf(ReconciliationSource("Koinly", listOf("Koinly CSV"), "Koinly · ")))
        }

    private val accounts: AccountReadRepository =
        mock(MockMode.autoUnit) { every { getAllAccounts() } returns flowOf(emptyList()) }

    private val currencies: CurrencyReadRepository =
        mock(MockMode.autoUnit) { every { getAllCurrencies() } returns flowOf(listOf(gbp, btc, unknown)) }

    @Test
    fun `taking the suggested other side of an unnamed exchange books it and stores the match`() =
        runMoneyManagerComposeUiTest {
            val placeholder =
                Trade(
                    TradeId(1),
                    timestamp = epoch,
                    description = "Transfer to Revolut Ltd",
                    fromAccountId = revolut,
                    from = Money.fromDisplayValue("1000", gbp),
                    toAccountId = revolut,
                    to = Money.fromDisplayValue("1000", unknown),
                )
            val koinly =
                Trade(
                    TradeId(2),
                    timestamp = epoch,
                    description = "trade",
                    fromAccountId = wallet,
                    from = Money.fromDisplayValue("1000.0", gbp),
                    toAccountId = wallet,
                    to = Money.fromDisplayValue("0.1330735", btc),
                )
            val trades: TradeReadRepository =
                mock(MockMode.autoUnit) {
                    every { getPendingManualTrades(any(), any()) } returns flowOf(listOf(placeholder))
                    everySuspend { getTradesByAccountsAndDateRange(any(), any(), any()) } returns listOf(koinly)
                }
            val transactions: TransactionReadRepository =
                mock(MockMode.autoUnit) { every { getPendingManualEntries(any(), any()) } returns flowOf(emptyList()) }
            val engine = RecordingEngine()
            val cache = ManualEntrySuggestionCache()
            val opened = mutableListOf<ReconciliationLeg>()
            val focused = mutableListOf<ReconciliationFocus>()

            setContent {
                ProvideSchemaAwareScope {
                    CompositionLocalProvider(LocalImportEngine provides engine) {
                        ManualEntriesScreen(
                            csvImportStrategyRepository = strategies(strategy()),
                            transactionRepository = transactions,
                            tradeRepository = trades,
                            currencyRepository = currencies,
                            reconciliationRepository = reconciliation,
                            accountRepository = accounts,
                            suggestionCache = cache,
                            onOpenSource = { opened += it },
                            onOpenReconciliation = { focused += it },
                            maintenance = maintenance,
                            onTransactionsImported = {},
                        )
                    }
                }
            }
            waitUntilAtLeastOneExists(hasText("Koinly (Koinly · Binance) records the other side", substring = true))

            onNodeWithText("Open the Koinly file row").performClick()
            onNodeWithText("Show in Reconciliation").performClick()
            waitForIdle()
            assertEquals(listOf(koinly.id.id), opened.map { it.transactionId })
            assertEquals(listOf(ReconciliationFocus("Koinly", wallet, "2021-05")), focused)

            onNodeWithText("and match it", substring = true).performClick()
            waitUntil(timeoutMillis = 5_000) { engine.batches.any { it.relationshipMutations.isNotEmpty() } }

            val created = engine.batches.flatMap { it.trades }.single()
            assertEquals(revolut, created.fromAccountId)
            assertEquals(placeholder.from, created.fromAmount)
            assertEquals(koinly.to, created.toAmount)
            val match = engine.batches.flatMap { it.relationshipMutations }.single() as RelationshipMutation.Create
            assertEquals(setOf(500L, 2L), match.members.map { it.transactionId.id }.toSet())
        }

    @Test
    fun `taking the suggested fee of a withdrawal books it and stores the match`() =
        runMoneyManagerComposeUiTest {
            val entry = RuleAction.ManualEntry(name = "Withdrawal fee", description = "Withdrawal fee", relationshipType = "fee")
            val withdrawal =
                PendingManualEntry(
                    transferId = TransferId(10),
                    timestamp = epoch,
                    description = "Withdraw",
                    sourceAccountId = AccountId(2),
                    sourceAccountName = "Binance",
                    targetAccountId = AccountId(3),
                    targetAccountName = "Binance Funding",
                    amount = Money.fromDisplayValue("0.27986608", btc),
                )
            val net =
                Transfer(
                    TransferId(11),
                    timestamp = Instant.parse("2021-05-26T19:48:36Z"),
                    description = "net",
                    sourceAccountId = wallet,
                    targetAccountId = AccountId(30),
                    amount = Money.fromDisplayValue("0.27931608", btc),
                )
            val fee =
                net.copy(
                    id = TransferId(12),
                    description = "fee",
                    targetAccountId = walletFees,
                    amount = Money.fromDisplayValue("0.00055", btc),
                )
            val transactions: TransactionReadRepository =
                mock(MockMode.autoUnit) {
                    every { getPendingManualEntries(any(), any()) } returns flowOf(listOf(withdrawal))
                    every { getTransactionsByDateRange(any(), any()) } returns flowOf(listOf(net, fee))
                }
            val trades: TradeReadRepository =
                mock(MockMode.autoUnit) { every { getPendingManualTrades(any(), any()) } returns flowOf(emptyList()) }
            val engine = RecordingEngine()
            val cache = ManualEntrySuggestionCache()
            val opened = mutableListOf<ReconciliationLeg>()
            val focused = mutableListOf<ReconciliationFocus>()

            setContent {
                ProvideSchemaAwareScope {
                    CompositionLocalProvider(LocalImportEngine provides engine) {
                        ManualEntriesScreen(
                            csvImportStrategyRepository = strategies(strategy(entry)),
                            transactionRepository = transactions,
                            tradeRepository = trades,
                            currencyRepository = currencies,
                            reconciliationRepository = reconciliation,
                            accountRepository = accounts,
                            suggestionCache = cache,
                            onOpenSource = { opened += it },
                            onOpenReconciliation = { focused += it },
                            maintenance = maintenance,
                            onTransactionsImported = {},
                        )
                    }
                }
            }
            waitUntilAtLeastOneExists(hasText("Koinly (Koinly · Binance) charged", substring = true))

            onNodeWithText("Open the Koinly file row").performClick()
            waitForIdle()
            assertEquals(listOf(net.id.id), opened.map { it.transactionId })

            onNodeWithText("and match it", substring = true).performClick()
            waitUntil(timeoutMillis = 5_000) { engine.batches.flatMap { it.relationshipMutations }.size >= 2 }

            val created = engine.batches.flatMap { it.transfers }.single()
            assertEquals(fee.amount, created.amount)
            val matches = engine.batches.flatMap { it.relationshipMutations }.filterIsInstance<RelationshipMutation.Create>()
            assertTrue(matches.any { match -> match.members.map { it.transactionId.id }.toSet() == setOf(600L, 12L) })
            assertTrue(created.rowKey is ImportRowKey.Manual)
        }
}
