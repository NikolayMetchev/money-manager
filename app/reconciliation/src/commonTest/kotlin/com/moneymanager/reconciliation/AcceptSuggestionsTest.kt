package com.moneymanager.reconciliation

import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.PendingManualEntry
import com.moneymanager.domain.model.RelationshipRole
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportOperation
import com.moneymanager.importengineapi.ImportResult
import com.moneymanager.importengineapi.ImportRowKey
import com.moneymanager.importengineapi.RelationshipMutation
import com.moneymanager.tasks.Progress
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class AcceptSuggestionsTest {
    private val btc = Currency(CurrencyId(1), code = "BTC", name = "BTC", scaleFactor = 1_000_000_000_000_000_000L)
    private val at = Instant.parse("2021-05-26T19:48:36Z")

    private fun btc(decimal: String): Money {
        val (whole, fraction) = decimal.split('.').let { it[0] to it.getOrElse(1) { "" } }
        return Money(BigInteger(whole + fraction.padEnd(18, '0')), btc)
    }

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

    /** Answers every batch, except that the write of a reconciliation match (members, not a primary link) fails. */
    private class FailingMatchEngine : ImportEngine {
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
}
