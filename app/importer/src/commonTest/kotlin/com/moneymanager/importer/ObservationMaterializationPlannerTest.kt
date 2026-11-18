package com.moneymanager.importer

import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.observation.AttributeOverride
import com.moneymanager.domain.model.observation.ImportRecordId
import com.moneymanager.domain.model.observation.MaterializedTransaction
import com.moneymanager.domain.model.observation.MaterializedWrite
import com.moneymanager.domain.model.observation.Observation
import com.moneymanager.domain.model.observation.ObservationContent
import com.moneymanager.domain.model.observation.ObservationId
import com.moneymanager.domain.model.observation.ObservationOwner
import com.moneymanager.domain.model.observation.ObservedAssetId
import com.moneymanager.domain.model.observation.ObservedValues
import com.moneymanager.domain.model.observation.TransferField
import com.moneymanager.domain.repository.ObservationReadRepository
import com.moneymanager.domain.repository.PersonOverrideReadRepository
import com.moneymanager.importengineapi.LocalTransactionKey
import com.moneymanager.importengineapi.MaterializationRequest
import com.moneymanager.importengineapi.ObservationMutation
import com.moneymanager.importengineapi.ObservationTransaction
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

class ObservationMaterializationPlannerTest {
    private val existingId = TransferId(1)
    private val existingOwner = ObservationOwner.Record(ImportRecordId(1))
    private val newOwner = ObservationOwner.Record(ImportRecordId(2))
    private val oldContent = content("Old import", "100")
    private val stored = Observation(ObservationId(1), existingOwner, "main", existingId, oldContent)
    private val saved = MaterializedTransaction(content("My edit", "100").values)

    @Test
    fun `the overlay combines changes to an existing slot and shares provisional transactions`() =
        runTest {
            val key = LocalTransactionKey("new")
            val newTransaction = ObservationTransaction.New(key)
            val latest = content("Latest import", "120")
            val added = content("New transfer", "200")
            val plan =
                planner().plan(
                    listOf(
                        upsert(existingOwner, "main", ObservationTransaction.Existing(existingId), content("Intermediate", "110")),
                        upsert(existingOwner, "main", ObservationTransaction.Existing(existingId), latest),
                        upsert(newOwner, "main", newTransaction, added),
                        upsert(newOwner, "detail", newTransaction, content("", "200")),
                    ),
                    MaterializationRequest(Source.Reconciler),
                )

            val provisional = plan.newTransactions.getValue(key)
            assertNotEquals(existingId, provisional)
            assertEquals(setOf(provisional), plan.report.inserted)
            assertEquals(setOf(existingId), plan.report.updated)
            val (updated, inserted) = plan.rows
            assertEquals(MaterializedTransaction(content("My edit", "120").values), assertIs<MaterializedWrite.Update>(updated).after)
            assertEquals(MaterializedTransaction(added.values), assertIs<MaterializedWrite.Insert>(inserted).row)
            assertEquals(
                "Latest import",
                plan.report.conflicts
                    .single()
                    .importAfter,
            )
            val change = plan.observations.first { it.owner == existingOwner }
            assertEquals(stored, change.before)
            assertEquals(stored.id, assertNotNull(change.after).storedId)
            assertEquals(latest, assertNotNull(change.after).content)
            assertEquals(
                listOf(provisional, provisional),
                plan.observations.filter { it.owner == newOwner }.map { it.after?.transactionId },
            )
        }

    @Test
    fun `deleting and readding a slot drops its previous observation identity`() =
        runTest {
            val plan =
                planner().plan(
                    listOf(
                        ObservationMutation.Delete(existingOwner, "main"),
                        upsert(existingOwner, "main", ObservationTransaction.Existing(existingId), oldContent),
                    ),
                    MaterializationRequest(Source.Reconciler),
                )
            val change = plan.observations.single()
            assertEquals(stored, change.before)
            assertNull(assertNotNull(change.after).storedId)
            assertEquals(emptyList(), plan.rows)
        }

    private fun planner() = ObservationMaterializationPlanner(ReadObservations(), Overrides(), null, null, null)

    private fun upsert(
        owner: ObservationOwner,
        slot: String,
        target: ObservationTransaction,
        content: ObservationContent,
    ) = ObservationMutation.Upsert(owner, slot, target, content)

    private fun content(
        description: String,
        amount: String,
    ) = ObservationContent(
        ObservedValues.Transfer(
            Instant.fromEpochMilliseconds(1_000),
            description,
            AccountId(1),
            AccountId(2),
            ObservedAssetId(1),
            BigInteger(amount),
        ),
    )

    private inner class ReadObservations : ObservationReadRepository {
        override suspend fun getObservationsOf(owner: ObservationOwner) = if (owner == existingOwner) listOf(stored) else emptyList()

        override suspend fun getObservationsOfTransaction(transactionId: TransactionId): List<Observation> {
            assertEquals(existingId, transactionId)
            return listOf(stored)
        }

        override suspend fun getMaterialized(transactionId: TransactionId): MaterializedTransaction {
            assertEquals(existingId, transactionId)
            return saved
        }

        override suspend fun getObservation(id: ObservationId): Nothing = error("Unexpected lookup: $id")

        override suspend fun getCreationSourceId(transactionId: TransactionId): Nothing = error("Unexpected lookup: $transactionId")

        override suspend fun getObservationsOnAccounts(accountIds: Collection<AccountId>): Nothing = error("Unexpected lookup: $accountIds")

        override suspend fun getAccountsWithObservations(accountIds: Collection<AccountId>): Nothing =
            error("Unexpected lookup: $accountIds")

        override suspend fun getVacantReserved(): Nothing = error("Unexpected reservation lookup")

        override suspend fun getReserved(): Nothing = error("Unexpected reservation lookup")

        override suspend fun isReserved(id: ObservationId): Nothing = error("Unexpected lookup: $id")
    }

    private inner class Overrides : PersonOverrideReadRepository {
        override suspend fun getTransferOverrides(transferId: TransferId): Map<TransferField, String> {
            assertEquals(existingId, transferId)
            return mapOf(TransferField.DESCRIPTION to "My edit")
        }

        override suspend fun getTransferAttributeOverrides(transferId: TransferId): List<AttributeOverride> {
            assertEquals(existingId, transferId)
            return emptyList()
        }

        override suspend fun getTradeAttributeOverrides(tradeId: TradeId): Nothing = error("Unexpected lookup: $tradeId")

        override suspend fun getPins(observationIds: Collection<ObservationId>): Nothing = error("Unexpected lookup: $observationIds")

        override suspend fun getPinsOnTransactions(transactionIds: Collection<TransactionId>): Nothing =
            error("Unexpected lookup: $transactionIds")

        override suspend fun getAllPins() = emptyList<Nothing>()

        override suspend fun getAllSplits() = emptySet<Nothing>()

        override suspend fun getSplits(observationIds: Collection<ObservationId>): Nothing = error("Unexpected lookup: $observationIds")
    }
}
