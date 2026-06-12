@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository

import com.moneymanager.database.DatabaseConfig
import com.moneymanager.database.SampleGeneratorSourceRecorder
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.NewRelationship
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Verifies that an import which reconciles a cross-source duplicate persists the link as a
 * `reconciled` transfer_relationship row (not as an id embedded in the excluded attribute), while
 * the duplicate still carries the plain `excluded` attribute so it stays out of balances.
 */
class TransferRelationshipReconciliationTest : DbTest() {
    private val baseTime = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private suspend fun gbp(): Currency =
        repositories.currencyRepository
            .getAllCurrencies()
            .first()
            .first { it.code == "GBP" }

    private suspend fun createAccount(name: String): AccountId {
        repositories.accountRepository.createAccount(Account(id = AccountId(0), name = name, openingDate = baseTime))
        return repositories.accountRepository
            .getAllAccounts()
            .first()
            .first { it.name == name }
            .id
    }

    /**
     * Imports an original transfer and a reconciled duplicate of it (between the same accounts),
     * returning their ids as (original, duplicate). The duplicate carries the plain excluded attribute
     * and a `reconciled` relationship pointing back to the original.
     */
    private suspend fun importReconciledPair(
        currency: Currency,
        sourceId: AccountId,
        targetId: AccountId,
    ): Pair<TransferId, TransferId> {
        val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))

        fun recorder() = SampleGeneratorSourceRecorder(transferSourceQueries, deviceId)

        fun transfer() =
            Transfer(
                id = TransferId(-1),
                timestamp = baseTime,
                description = "Coffee",
                sourceAccountId = sourceId,
                targetAccountId = targetId,
                amount = Money(500, currency),
            )

        val originalId =
            repositories.transactionRepository
                .importTransfers(
                    transfers = listOf(transfer()),
                    newAttributes = emptyMap(),
                    sourceRecorder = recorder(),
                    updates = emptyList(),
                    updateSourceRecorder = recorder(),
                ).single()

        val excludedTypeId = AttributeTypeId(DatabaseConfig.EXCLUDED_ATTR_TYPE_ID)
        val reconciledTypeId = RelationshipTypeId(DatabaseConfig.RECONCILED_RELATIONSHIP_TYPE_ID)
        val duplicateId =
            repositories.transactionRepository
                .importTransfers(
                    transfers = listOf(transfer()),
                    newAttributes = mapOf(TransferId(-1) to listOf(NewAttribute(excludedTypeId, "reconciled"))),
                    newRelationships =
                        mapOf(TransferId(-1) to listOf(NewRelationship(relatedTransferId = originalId, typeId = reconciledTypeId))),
                    sourceRecorder = recorder(),
                    updates = emptyList(),
                    updateSourceRecorder = recorder(),
                ).single()

        return originalId to duplicateId
    }

    @Test
    fun reconciledDuplicate_persistsRelationshipAndExclusion() =
        runTest {
            val currency = gbp()
            val sourceId = createAccount("Checking")
            val targetId = createAccount("Coffee Shop")

            val (originalId, duplicateId) = importReconciledPair(currency, sourceId, targetId)

            // The link lives in the relationship table: duplicate (id1) -> original (id2), type reconciled.
            val relationships = repositories.transferRelationshipRepository.getByTransfer(duplicateId).first()
            assertEquals(1, relationships.size)
            val relationship = relationships.single()
            assertEquals(duplicateId, relationship.id1)
            assertEquals(originalId, relationship.id2)
            assertEquals("reconciled", relationship.relationshipType.name)

            // Looking it up from the original side finds the same row.
            assertEquals(relationships, repositories.transferRelationshipRepository.getByTransfer(originalId).first())

            // The duplicate keeps the plain excluded attribute so balances still leave it out.
            val attributes = repositories.transferAttributeRepository.getByTransaction(duplicateId).first()
            assertEquals("reconciled", attributes.single { it.attributeType.name == "excluded" }.value)
        }

    @Test
    fun runningBalanceRows_flagBothSidesOfReconciledPairAsReconciled() =
        runTest {
            val currency = gbp()
            val checkingId = createAccount("Checking")
            val coffeeShopId = createAccount("Coffee Shop")
            val groceriesId = createAccount("Groceries")

            val (originalId, duplicateId) = importReconciledPair(currency, checkingId, coffeeShopId)

            // An ordinary, unrelated transfer that must NOT be flagged reconciled.
            val plain =
                createTransfer(
                    Transfer(
                        id = TransferId(-1),
                        timestamp = baseTime,
                        description = "Groceries",
                        sourceAccountId = checkingId,
                        targetAccountId = groceriesId,
                        amount = Money(700, currency),
                    ),
                )

            repositories.maintenanceService.fullRefreshMaterializedViews()

            val rows =
                repositories.transactionRepository
                    .getRunningBalanceByAccountPaginated(checkingId, pageSize = 50, pagingInfo = null)
                    .items
                    .associateBy { it.transactionId }

            // Both sides of the reconciled pair (original id2 and duplicate id1) are flagged reconciled.
            assertEquals(true, rows.getValue(originalId).isReconciled)
            assertEquals(true, rows.getValue(duplicateId).isReconciled)
            // The unrelated transfer is not.
            assertEquals(false, rows.getValue(plain.id).isReconciled)
        }
}
