@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.repository
import com.moneymanager.database.DatabaseConfig
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.NewRelationship
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createAccount
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
                    sources = listOf(Source.SampleGenerator),
                    updates = emptyList(),
                    updateSources = emptyList(),
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
                    sources = listOf(Source.SampleGenerator),
                    updates = emptyList(),
                    updateSources = emptyList(),
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
    fun deletingTransfer_cascadeDeletesItsRelationships() =
        runTest {
            val currency = gbp()
            val sourceId = createAccount("Checking")
            val targetId = createAccount("Coffee Shop")

            val (originalId, duplicateId) = importReconciledPair(currency, sourceId, targetId)
            assertEquals(
                1,
                repositories.transferRelationshipRepository
                    .getByTransfer(originalId)
                    .first()
                    .size,
            )

            // Deleting one side of the pair must remove the relationship (FK transfer(id) ON DELETE CASCADE),
            // leaving no stale reconciliation link on the surviving transfer.
            repositories.transactionRepository.deleteTransaction(duplicateId.id)

            assertEquals(emptyList(), repositories.transferRelationshipRepository.getByTransfer(originalId).first())
            assertEquals(emptyList(), repositories.transferRelationshipRepository.getByTransfer(duplicateId).first())
        }

    @Test
    fun passThroughTransfer_runningBalanceRowsLinkFundingAndSpendLegs() =
        runTest {
            val currency = gbp()
            val cardId = createAccount("Crypto.com")
            val conduitId = createAccount("Curve")
            val merchantId = createAccount("National Lottery")
            val passThroughTypeId = RelationshipTypeId(DatabaseConfig.PASS_THROUGH_RELATIONSHIP_TYPE_ID)
            // Funding leg (card -> conduit) is id1; spend leg (conduit -> merchant) is id2, linked via the
            // pass-through relationship. Both created in one batch with temp ids resolved by the engine.
            val funding =
                Transfer(
                    id = TransferId(-1),
                    timestamp = baseTime,
                    description = "Curve",
                    sourceAccountId = cardId,
                    targetAccountId = conduitId,
                    amount = Money(1010, currency),
                )
            val spend =
                Transfer(
                    id = TransferId(-2),
                    timestamp = baseTime,
                    description = "National Lottery",
                    sourceAccountId = conduitId,
                    targetAccountId = merchantId,
                    amount = Money(1010, currency),
                )

            val createdIds =
                repositories.transactionRepository.importTransfers(
                    transfers = listOf(funding, spend),
                    newAttributes = emptyMap(),
                    newRelationships =
                        mapOf(
                            TransferId(-1) to
                                listOf(NewRelationship(relatedTransferId = TransferId(-2), typeId = passThroughTypeId)),
                        ),
                    sources = List(2) { Source.SampleGenerator },
                    updates = emptyList(),
                    updateSources = emptyList(),
                )
            val fundingId = createdIds[0]
            val spendId = createdIds[1]

            // The conduit account touches both legs; its running-balance rows link each leg to the other.
            repositories.maintenanceService.fullRefreshMaterializedViews()
            val conduitRows =
                repositories.transactionRepository
                    .getRunningBalanceByAccountPaginated(conduitId, pageSize = 50, pagingInfo = null)
                    .items
                    .associateBy { it.transactionId }
            assertEquals(spendId, conduitRows.getValue(fundingId).passThroughSpendId)
            assertEquals(null, conduitRows.getValue(fundingId).passThroughFundingId)
            assertEquals(fundingId, conduitRows.getValue(spendId).passThroughFundingId)
            assertEquals(null, conduitRows.getValue(spendId).passThroughSpendId)
        }

    @Test
    fun feeTransfer_inBatchRelationshipResolvesToSiblingsRealId() =
        runTest {
            val currency = gbp()
            val checkingId = createAccount("Checking")
            val shopId = createAccount("Coffee Shop")
            val feeAccountId = createAccount("Monzo Fees")
            val feeTypeId = RelationshipTypeId(DatabaseConfig.FEE_RELATIONSHIP_TYPE_ID)
            // Both the main transfer and its fee are created in the same batch with temp ids; the fee
            // relationship on the main (id1) references the fee's temp id (-2), which the two-pass insert
            // must resolve to the fee's real generated id (id2).
            val main =
                Transfer(
                    id = TransferId(-1),
                    timestamp = baseTime,
                    description = "Coffee",
                    sourceAccountId = checkingId,
                    targetAccountId = shopId,
                    amount = Money(500, currency),
                )
            val fee =
                Transfer(
                    id = TransferId(-2),
                    timestamp = baseTime,
                    description = "Fee",
                    sourceAccountId = checkingId,
                    targetAccountId = feeAccountId,
                    amount = Money(29, currency),
                )

            val createdIds =
                repositories.transactionRepository.importTransfers(
                    transfers = listOf(main, fee),
                    newAttributes = emptyMap(),
                    newRelationships =
                        mapOf(TransferId(-1) to listOf(NewRelationship(relatedTransferId = TransferId(-2), typeId = feeTypeId))),
                    sources = List(2) { Source.SampleGenerator },
                    updates = emptyList(),
                    updateSources = emptyList(),
                )
            assertEquals(2, createdIds.size)
            val mainId = createdIds[0]
            val feeId = createdIds[1]

            // The link is main (id1) -> fee (id2), type fee, with the temp id resolved to the real fee id.
            val relationship =
                repositories.transferRelationshipRepository
                    .getByTransfer(mainId)
                    .first()
                    .single()
            assertEquals(mainId, relationship.id1)
            assertEquals(feeId, relationship.id2)
            assertEquals("fee", relationship.relationshipType.name)

            // The fee transfer is a real movement: it carries no excluded attribute, so it counts in balances.
            val feeAttributes = repositories.transferAttributeRepository.getByTransaction(feeId).first()
            assertEquals(true, feeAttributes.none { it.attributeType.name == "excluded" })

            // The running-balance rows distinguish the two sides for the UI: the main transaction links
            // forward to its fee transfer, and the fee transfer links back to its main transaction.
            repositories.maintenanceService.fullRefreshMaterializedViews()
            val checkingRows =
                repositories.transactionRepository
                    .getRunningBalanceByAccountPaginated(checkingId, pageSize = 50, pagingInfo = null)
                    .items
                    .associateBy { it.transactionId }
            assertEquals(feeId, checkingRows.getValue(mainId).feeTransferId)
            assertEquals(null, checkingRows.getValue(mainId).feeParentTransferId)
            assertEquals(mainId, checkingRows.getValue(feeId).feeParentTransferId)
            assertEquals(null, checkingRows.getValue(feeId).feeTransferId)
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
