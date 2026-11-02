package com.moneymanager.database.repository
import app.cash.sqldelight.async.coroutines.awaitAsList
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.NewRelationship
import com.moneymanager.domain.model.RelationshipMember
import com.moneymanager.domain.model.RelationshipRole
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.SourceType
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.repository.write.NewTrade
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createAccount
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Verifies that an import which reconciles a cross-source duplicate persists the link as a
 * `reconciled` relationship (not as an id embedded in the excluded attribute), while
 * the duplicate still carries the plain `excluded` attribute so it stays out of balances.
 */
class RelationshipDbTest : DbTest() {
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

        val excludedTypeId = AttributeTypeId(WellKnownIds.EXCLUDED_ATTR_TYPE_ID)
        val reconciledTypeId = RelationshipTypeId(WellKnownIds.RECONCILED_RELATIONSHIP_TYPE_ID)
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
        runDbTest {
            val currency = gbp()
            val sourceId = createAccount("Checking")
            val targetId = createAccount("Coffee Shop")

            val (originalId, duplicateId) = importReconciledPair(currency, sourceId, targetId)

            // The link lives in the relationship table: duplicate (primary) -> original (related), type reconciled.
            val relationships = repositories.relationshipRepository.getByTransaction(duplicateId).first()
            assertEquals(1, relationships.size)
            val relationship = relationships.single()
            assertEquals(duplicateId, relationship.primary)
            assertEquals(originalId, relationship.related)
            assertEquals("reconciled", relationship.relationshipType.name)

            // Looking it up from the original side finds the same row.
            assertEquals(relationships, repositories.relationshipRepository.getByTransaction(originalId).first())

            // The duplicate keeps the plain excluded attribute so balances still leave it out.
            val attributes = repositories.transferAttributeRepository.getByTransaction(duplicateId).first()
            assertEquals("reconciled", attributes.single { it.attributeType.name == "excluded" }.value)
        }

    @Test
    fun deletingTransfer_cascadeDeletesItsRelationships() =
        runDbTest {
            val currency = gbp()
            val sourceId = createAccount("Checking")
            val targetId = createAccount("Coffee Shop")

            val (originalId, duplicateId) = importReconciledPair(currency, sourceId, targetId)
            assertEquals(
                1,
                repositories.relationshipRepository
                    .getByTransaction(originalId)
                    .first()
                    .size,
            )

            // Deleting one side of the pair must remove the relationship (the transfer delete trigger),
            // leaving no stale reconciliation link on the surviving transfer.
            repositories.transactionRepository.deleteTransactions(listOf(duplicateId))

            assertEquals(emptyList(), repositories.relationshipRepository.getByTransaction(originalId).first())
            assertEquals(emptyList(), repositories.relationshipRepository.getByTransaction(duplicateId).first())
        }

    @Test
    fun passThroughTransfer_runningBalanceRowsLinkFundingAndSpendLegs() =
        runDbTest {
            val currency = gbp()
            val cardId = createAccount("Crypto.com")
            val conduitId = createAccount("Curve")
            val merchantId = createAccount("National Lottery")
            val passThroughTypeId = RelationshipTypeId(WellKnownIds.PASS_THROUGH_RELATIONSHIP_TYPE_ID)
            // Funding leg (card -> conduit) is primary; spend leg (conduit -> merchant) is related, linked via the
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
    fun passThroughChain_readsReconciled_whenTheFundingLegIsReconciled() =
        runDbTest {
            // A Curve funding-card reconcile links the conduit's export spend to the FUNDING leg
            // (card -> conduit). The sibling SPEND leg (conduit -> merchant, the "Via conduit" row) must
            // then also read as reconciled — the whole chain is reconciled, not just the funding leg.
            val currency = gbp()
            val cardId = createAccount("Crypto.com Card")
            val conduitId = createAccount("Curve")
            val merchantId = createAccount("Sainsburys London")
            val curveMerchantId = createAccount("SAINSBURYS")
            val passThroughTypeId = RelationshipTypeId(WellKnownIds.PASS_THROUGH_RELATIONSHIP_TYPE_ID)

            fun funding() =
                Transfer(
                    id = TransferId(-1),
                    timestamp = baseTime,
                    description = "Curve",
                    sourceAccountId = cardId,
                    targetAccountId = conduitId,
                    amount = Money(2293, currency),
                )

            fun spend() =
                Transfer(
                    id = TransferId(-2),
                    timestamp = baseTime,
                    description = "Sainsburys London",
                    sourceAccountId = conduitId,
                    targetAccountId = merchantId,
                    amount = Money(2293, currency),
                )
            val chainIds =
                repositories.transactionRepository.importTransfers(
                    transfers = listOf(funding(), spend()),
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
            val fundingId = chainIds[0]
            val spendId = chainIds[1]

            // The Curve export's own record of the spend, reconciled (excluded + linked) against the funding leg.
            val excludedTypeId = AttributeTypeId(WellKnownIds.EXCLUDED_ATTR_TYPE_ID)
            val reconciledTypeId = RelationshipTypeId(WellKnownIds.RECONCILED_RELATIONSHIP_TYPE_ID)
            val curveSpendId =
                repositories.transactionRepository
                    .importTransfers(
                        transfers =
                            listOf(
                                Transfer(
                                    id = TransferId(-1),
                                    timestamp = baseTime,
                                    description = "SAINSBURYS",
                                    sourceAccountId = conduitId,
                                    targetAccountId = curveMerchantId,
                                    amount = Money(2293, currency),
                                ),
                            ),
                        newAttributes = mapOf(TransferId(-1) to listOf(NewAttribute(excludedTypeId, "reconciled"))),
                        newRelationships =
                            mapOf(TransferId(-1) to listOf(NewRelationship(relatedTransferId = fundingId, typeId = reconciledTypeId))),
                        sources = listOf(Source.SampleGenerator),
                        updates = emptyList(),
                        updateSources = emptyList(),
                    ).single()

            repositories.maintenanceService.fullRefreshMaterializedViews()
            val conduitRows =
                repositories.transactionRepository
                    .getRunningBalanceByAccountPaginated(conduitId, pageSize = 50, pagingInfo = null)
                    .items
                    .associateBy { it.transactionId }

            assertEquals(true, conduitRows.getValue(fundingId).isReconciled, "funding leg is directly reconciled")
            assertEquals(true, conduitRows.getValue(spendId).isReconciled, "spend leg reads reconciled via its funding leg")
            assertEquals(true, conduitRows.getValue(curveSpendId).isReconciled, "the Curve export spend is reconciled")
        }

    @Test
    fun feeTransfer_inBatchRelationshipResolvesToSiblingsRealId() =
        runDbTest {
            val currency = gbp()
            val checkingId = createAccount("Checking")
            val shopId = createAccount("Coffee Shop")
            val feeAccountId = createAccount("Monzo Fees")
            val feeTypeId = RelationshipTypeId(WellKnownIds.FEE_RELATIONSHIP_TYPE_ID)
            // Both the main transfer and its fee are created in the same batch with temp ids; the fee
            // relationship on the main (primary) references the fee's temp id (-2), which the two-pass insert
            // must resolve to the fee's real generated id (related).
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

            // The link is main (primary) -> fee (related), type fee, with the temp id resolved to the real fee id.
            val relationship =
                repositories.relationshipRepository
                    .getByTransaction(mainId)
                    .first()
                    .single()
            assertEquals(mainId, relationship.primary)
            assertEquals(feeId, relationship.related)
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
        runDbTest {
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

            // Both sides of the reconciled pair (original related and duplicate primary) are flagged reconciled.
            assertEquals(true, rows.getValue(originalId).isReconciled)
            assertEquals(true, rows.getValue(duplicateId).isReconciled)
            // The unrelated transfer is not.
            assertEquals(false, rows.getValue(plain.id).isReconciled)
        }

    private suspend fun plainTransfer(
        currency: Currency,
        from: AccountId,
        to: AccountId,
        amount: Long,
    ): TransferId =
        createTransfer(
            Transfer(
                id = TransferId(-1),
                timestamp = baseTime,
                description = "Leg $amount",
                sourceAccountId = from,
                targetAccountId = to,
                amount = Money(amount, currency),
            ),
        ).id

    private suspend fun relationshipSources(relationshipId: Long): List<Long> =
        database
            .rawQuery(
                "SELECT source_type_id FROM entity_source WHERE entity_type_id = ${EntityType.RELATIONSHIP.id} " +
                    "AND entity_id = $relationshipId AND matched = 0",
            ) { it.getLong(0)!! }
            .awaitAsList()

    @Test
    fun oneRelationship_joinsTransfersAndTrades_andTriggersPruneIt() =
        runDbTest {
            val gbp = gbp()
            val usd =
                repositories.currencyRepository
                    .getAllCurrencies()
                    .first()
                    .first { it.code == "USD" }
            val wallet = createAccount("Wallet")
            val bank = createAccount("Bank")
            val first = plainTransfer(gbp, bank, wallet, 100)
            val second = plainTransfer(gbp, bank, wallet, 200)
            val trade =
                repositories.tradeRepository
                    .createTrades(
                        listOf(
                            NewTrade(
                                timestamp = baseTime,
                                description = "Buy USD",
                                fromAccountId = wallet,
                                fromAmount = Money(300, gbp),
                                toAccountId = wallet,
                                toAmount = Money(400, usd),
                                source = Source.SampleGenerator,
                            ),
                        ),
                    ).single()
                    .id
            val typeId = RelationshipTypeId(WellKnownIds.RECONCILED_RELATIONSHIP_TYPE_ID)
            val members = listOf(trade, first, second).map { RelationshipMember(it, RelationshipRole.MEMBER) }

            val id = assertNotNull(repositories.relationshipRepository.create(typeId, members, Source.Manual))
            assertNull(repositories.relationshipRepository.create(typeId, members, Source.Manual), "the same relationship twice")

            val read =
                repositories.relationshipRepository
                    .getByTransaction(trade)
                    .first()
                    .single()
            assertEquals(id, read.id)
            assertEquals(members.toSet(), read.members.toSet(), "members keep their kind: a TradeId and two TransferIds")
            assertEquals(listOf(SourceType.MANUAL.id.toLong()), relationshipSources(id.id))

            // Deleting the trade drops only its membership; two members still relate.
            repositories.tradeRepository.deleteTrades(listOf(trade))
            assertEquals(
                setOf<Any>(first, second),
                repositories.relationshipRepository
                    .getByTransaction(first)
                    .first()
                    .single()
                    .members
                    .map { it.transactionId }
                    .toSet(),
            )

            // One member alone relates nothing, so the relationship goes with the second deletion.
            repositories.transactionRepository.deleteTransactions(listOf(first))
            assertEquals(emptyList(), repositories.relationshipRepository.getByTransaction(second).first())
        }

    @Test
    fun importedRelationship_recordsItsOwningTransfersSource() =
        runDbTest {
            val (originalId, duplicateId) = importReconciledPair(gbp(), createAccount("Checking"), createAccount("Coffee Shop"))

            val relationship =
                repositories.relationshipRepository
                    .getByTransaction(originalId)
                    .first()
                    .single()
            assertEquals(duplicateId, relationship.primary)
            assertEquals(listOf(SourceType.SAMPLE_GENERATOR.id.toLong()), relationshipSources(relationship.id.id))
        }

    @Test
    fun getByTransactions_returnsEachRelationshipOnceWithAllItsMembers() =
        runDbTest {
            val (originalId, duplicateId) = importReconciledPair(gbp(), createAccount("Checking"), createAccount("Coffee Shop"))

            val relationships = repositories.relationshipRepository.getByTransactions(listOf(originalId, duplicateId))

            assertEquals(1, relationships.size)
            assertEquals(
                setOf<Any>(originalId, duplicateId),
                relationships
                    .single()
                    .members
                    .map { it.transactionId }
                    .toSet(),
            )
        }
}
