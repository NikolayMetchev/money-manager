package com.moneymanager.reconciliation

import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.PendingManualEntry
import com.moneymanager.domain.model.RelationshipRole
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Trade
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportOperation
import com.moneymanager.importengineapi.ImportResult
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.RelationshipMutation
import com.moneymanager.importengineapi.TradeAttributeMutation
import com.moneymanager.tasks.Progress
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class AcceptSuggestionsTest {
    private val btc = Currency(CurrencyId(1), code = "BTC", name = "BTC", scaleFactor = 1_000_000_000_000_000_000L)
    private val unknown = Currency(CurrencyId(3), code = "UNKNOWN", name = "UNKNOWN", scaleFactor = 1_000_000_000_000_000_000L)
    private val gbp = Currency(CurrencyId(4), code = "GBP", name = "GBP", scaleFactor = 1_000_000_000_000_000_000L)
    private val at = Instant.parse("2021-05-26T19:48:36Z")

    private fun money(
        currency: Currency,
        decimal: String,
    ): Money {
        val (whole, fraction) = decimal.split('.').let { it[0] to it.getOrElse(1) { "" } }
        return Money(BigInteger(whole + fraction.padEnd(18, '0')), currency)
    }

    private fun btc(decimal: String): Money = money(btc, decimal)

    private val withdrawal =
        PendingManualEntry(TransferId(1), at, "Withdraw", AccountId(2), "Binance", AccountId(3), "Funding", btc("0.27986608"))
    private val net =
        Transfer(
            id = TransferId(10),
            timestamp = at,
            description = "net",
            sourceAccountId = AccountId(20),
            targetAccountId = AccountId(30),
            amount = btc("0.27931608"),
        )
    private val fee = net.copy(id = TransferId(11), description = "fee", targetAccountId = AccountId(21), amount = btc("0.00055"))

    /**
     * Answers every batch, except that the write of a reconciliation match (members, not a primary link) fails. With
     * [tradesExistAlready] the trades it is asked to create are reported as identical ones already there.
     */
    private class FailingMatchEngine(
        private val tradesExistAlready: Boolean = false,
    ) : ImportEngine {
        val batches = mutableListOf<ImportBatch>()

        override suspend fun import(
            batch: ImportBatch,
            onProgress: (suspend (Progress) -> Unit)?,
            batchSize: Int,
        ): ImportResult {
            batches += batch
            val isMatch =
                batch.relationshipMutations.filterIsInstance<RelationshipMutation.Create>().any { create ->
                    create.members.all {
                        it.role ==
                            RelationshipRole.MEMBER
                    }
                }
            check(!isMatch) { "the match could not be stored" }
            return ImportResult(
                relationshipTypeIds = batch.relationshipTypeNames.associateWith { RelationshipTypeId(3) },
                attributeTypeIds = batch.attributeTypeNames.associateWith { AttributeTypeId(7) },
                createdTradeIds = batch.trades.filter { it.operation == ImportOperation.CREATE }.associate { it.key to TradeId(500) },
                dedupedTradeKeys = if (tradesExistAlready) batch.trades.map { it.key }.toSet() else emptySet(),
                createdTransferIds =
                    batch.transfers
                        .mapIndexedNotNull { index, transfer ->
                            transfer.rowKey?.let {
                                it to
                                    TransferId(600L + index)
                            }
                        }.toMap(),
            )
        }
    }

    @Test
    fun `the fee entries are taken back when their match cannot be stored`() =
        runTest {
            val engine = FailingMatchEngine()
            val suggestion = FeeSuggestion(withdrawal, fee.amount, fee, net)

            assertFailsWith<IllegalStateException> {
                engine.acceptFeeSuggestions(listOf(suggestion), "Withdrawal fee", "fee", excludedFromBalances = false, links = emptyMap())
            }

            val deleted =
                engine.batches
                    .flatMap { it.transfers }
                    .filter { it.operation == ImportOperation.DELETE }
                    .map { it.existingId }
            assertEquals(listOf(TransferId(600)), deleted)
            assertTrue(
                engine.batches.flatMap { it.transfers }.any {
                    it.rowKey is ImportRowKey.Manual &&
                        it.operation == ImportOperation.CREATE
                },
            )
        }

    private val placeholder =
        Trade(
            TradeId(1),
            timestamp = at,
            description = "Transfer",
            fromAccountId = AccountId(2),
            from = btc("1"),
            toAccountId = AccountId(2),
            to = money(unknown, "1"),
        )
    private val sourceTrade =
        Trade(
            TradeId(9),
            timestamp = at,
            description = "trade",
            fromAccountId = AccountId(20),
            from = btc("1"),
            toAccountId = AccountId(20),
            to = money(gbp, "20000"),
        )
    private val tradeSuggestion = OtherSideSuggestion(placeholder, "UNKNOWN", sourceTrade, money(gbp, "20000"))

    private fun List<ImportBatch>.deletedTrades() =
        flatMap {
            it.trades
        }.filter { it.operation == ImportOperation.DELETE }.map { it.existingId }

    @Test
    fun `the placeholders are put back and the new trades deleted when the match cannot be stored`() =
        runTest {
            val engine = FailingMatchEngine()

            assertFailsWith<IllegalStateException> { engine.acceptOtherSideSuggestions(listOf(tradeSuggestion), emptyMap()) }

            assertEquals(listOf<TradeId?>(TradeId(500)), engine.batches.deletedTrades())
            val lifted = engine.batches.flatMap { it.tradeAttributeMutations }.filterIsInstance<TradeAttributeMutation.Remove>()
            assertEquals(listOf(placeholder.id), lifted.map { it.tradeId })
        }

    @Test
    fun `a trade that was already there is left alone when the match cannot be stored`() =
        runTest {
            val engine = FailingMatchEngine(tradesExistAlready = true)

            assertFailsWith<IllegalStateException> { engine.acceptOtherSideSuggestions(listOf(tradeSuggestion), emptyMap()) }

            assertEquals(emptyList(), engine.batches.deletedTrades())
        }
}
