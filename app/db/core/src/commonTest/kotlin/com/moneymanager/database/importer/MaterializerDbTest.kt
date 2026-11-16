package com.moneymanager.database.importer

import com.moneymanager.bigdecimal.BigInteger
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.RelationshipMember
import com.moneymanager.domain.model.RelationshipRole
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.observation.AttributeOverride
import com.moneymanager.domain.model.observation.ImportRecordId
import com.moneymanager.domain.model.observation.ImportRecordKey
import com.moneymanager.domain.model.observation.ObservationAttribute
import com.moneymanager.domain.model.observation.ObservationContent
import com.moneymanager.domain.model.observation.ObservationEnd
import com.moneymanager.domain.model.observation.ObservationFacts
import com.moneymanager.domain.model.observation.ObservationOwner
import com.moneymanager.domain.model.observation.ObservedAssetId
import com.moneymanager.domain.model.observation.ObservedValues
import com.moneymanager.domain.model.observation.TransferField
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportResult
import com.moneymanager.importengineapi.LocalTransactionKey
import com.moneymanager.importengineapi.MaterializationConflict
import com.moneymanager.importengineapi.MaterializationRequest
import com.moneymanager.importengineapi.Materializer
import com.moneymanager.importengineapi.ObservationMutation
import com.moneymanager.importengineapi.ObservationTransaction
import com.moneymanager.importengineapi.RelationshipMutation
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.upsertCurrencyByCode
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

// Test names in this package are dexed for the Android device test run: no commas or apostrophes.
class MaterializerDbTest : DbTest() {
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val lastModified = Instant.fromEpochMilliseconds(1_690_000_000_000L)
    private val key = LocalTransactionKey("t")

    private suspend fun account(name: String): AccountId =
        repositories.accountRepository.createAccount(Account(id = AccountId(0), name = name, openingDate = now), Source.SampleGenerator)

    private suspend fun gbp(): Currency {
        val id = repositories.currencyRepository.upsertCurrencyByCode("GBP", "GBP")
        return repositories.currencyRepository.getCurrencyById(id).first()!!
    }

    private suspend fun csvImport(rows: Int): CsvImportId =
        repositories.csvImportRepository.createImport(
            fileName = "statement.csv",
            headers = listOf("Date", "Amount"),
            rows = (1..rows).map { listOf("2024-01-0$it", "$it.00") },
            fileChecksum = "checksum-$rows",
            fileLastModified = lastModified,
        )

    private suspend fun records(count: Int): List<ImportRecordId> {
        val importId = csvImport(count)
        return (1..count).map { repositories.importRecordRepository.insertRecord(ImportRecordKey.CsvRow(importId, it.toLong()), "h$it") }
    }

    private fun content(
        source: AccountId,
        target: AccountId,
        gbp: Currency,
        amount: String = "3500000000000000000",
        description: String = "Coffee",
        attributes: Set<ObservationAttribute> = emptySet(),
        facts: ObservationFacts = ObservationFacts(),
    ) = ObservationContent(
        values =
            ObservedValues.Transfer(
                timestamp = now,
                description = description,
                sourceAccountId = source,
                targetAccountId = target,
                assetId = ObservedAssetId(gbp.id.id),
                amount = BigInteger(amount),
            ),
        attributes = attributes,
        facts = facts,
    )

    private suspend fun materialize(
        vararg mutations: ObservationMutation,
        transactionIds: Set<TransactionId> = emptySet(),
        candidateAccountIds: Set<AccountId> = emptySet(),
    ): ImportResult =
        repositories.importEngine.import(
            ImportBatch(
                transfers = emptyList(),
                observationMutations = mutations.toList(),
                materialization = MaterializationRequest(Source.SampleGenerator, transactionIds, candidateAccountIds),
            ),
        )

    private fun upsert(
        record: ImportRecordId,
        transaction: ObservationTransaction,
        content: ObservationContent,
    ) = ObservationMutation.Upsert(ObservationOwner.Record(record), "transfer", transaction, content)

    private suspend fun transfer(id: TransactionId) = repositories.transactionRepository.getTransactionById(id.id).first()

    @Test
    fun `materializing twice writes nothing and keeps the id`() =
        runDbTest {
            val gbp = gbp()
            val bank = account("Bank")
            val shop = account("Shop")
            val (record) = records(1)
            val created = materialize(upsert(record, ObservationTransaction.New(key), content(bank, shop, gbp))).materialization
            val id = created.newTransactionIds.getValue(key)
            assertEquals(setOf(id), created.inserted)
            val first = assertNotNull(transfer(id))
            assertEquals(bank, first.sourceAccountId)
            assertEquals("Coffee", first.description)

            val again = materialize(upsert(record, ObservationTransaction.Existing(id), content(bank, shop, gbp))).materialization
            assertFalse(again.wroteRows)
            assertEquals(first, transfer(id))
        }

    @Test
    fun `a changed observation updates the row in place as a new revision`() =
        runDbTest {
            val gbp = gbp()
            val bank = account("Bank")
            val shop = account("Shop")
            val type = repositories.attributeTypeRepository.getOrCreate("reference")
            val (record) = records(1)
            val id =
                materialize(
                    upsert(
                        record,
                        ObservationTransaction.New(key),
                        content(bank, shop, gbp, attributes = setOf(ObservationAttribute(type, "", "ref-1"))),
                    ),
                ).materialization.newTransactionIds
                    .getValue(key)
            assertEquals(listOf("ref-1"), transfer(id)!!.attributes.map { it.value })

            val report =
                materialize(
                    upsert(record, ObservationTransaction.Existing(id), content(bank, shop, gbp, amount = "4000000000000000000")),
                ).materialization

            assertEquals(setOf(id), report.updated)
            val updated = transfer(id)!!
            assertEquals(id, updated.id)
            assertEquals(2L, updated.revisionId)
            assertEquals(BigInteger("4000000000000000000"), updated.amount.amount)
            // The owner stopped reporting the attribute, so it goes.
            assertTrue(updated.attributes.isEmpty())
        }

    @Test
    fun `an override wins and a changed import value under it is reported`() =
        runDbTest {
            val gbp = gbp()
            val bank = account("Bank")
            val shop = account("Shop")
            val type = repositories.attributeTypeRepository.getOrCreate("category")
            val (record) = records(1)
            val id =
                materialize(
                    upsert(record, ObservationTransaction.New(key), content(bank, shop, gbp)),
                ).materialization.newTransactionIds.getValue(key)
                    as TransferId
            repositories.personOverrideRepository.setTransferOverride(id, TransferField.DESCRIPTION, "Mine")
            repositories.personOverrideRepository.setTransferAttributeOverride(id, AttributeOverride.Set(type, "", "Food"))
            materialize(transactionIds = setOf(id))
            assertEquals("Mine", transfer(id)!!.description)
            assertEquals(listOf("Food"), transfer(id)!!.attributes.map { it.value })

            val report =
                materialize(
                    upsert(record, ObservationTransaction.Existing(id), content(bank, shop, gbp, description = "Coffee shop")),
                ).materialization

            assertFalse(report.wroteRows)
            assertEquals("Mine", transfer(id)!!.description)
            assertEquals(
                listOf(
                    MaterializationConflict(
                        id.id,
                        MaterializationConflict.Subject.Field(TransferField.DESCRIPTION),
                        "Mine",
                        "Coffee",
                        "Coffee shop",
                    ),
                ),
                report.conflicts,
            )
        }

    @Test
    fun `a transaction without observations goes and what pointed at it is reported`() =
        runDbTest {
            val gbp = gbp()
            val bank = account("Bank")
            val shop = account("Shop")
            val shopFees = account("Shop Fees")
            repositories.accountAttributeRepository.insert(
                shopFees,
                AttributeTypeId(WellKnownIds.ACCOUNT_FEE_ACCOUNT_FOR_ATTR_TYPE_ID),
                shop.id.toString(),
            )
            val (first, second) = records(2)
            val doomed =
                materialize(
                    upsert(first, ObservationTransaction.New(key), content(bank, shop, gbp)),
                ).materialization.newTransactionIds.getValue(key)
            val kept =
                materialize(upsert(second, ObservationTransaction.New(LocalTransactionKey("k")), content(bank, account("Cafe"), gbp)))
                    .materialization.newTransactionIds
                    .getValue(LocalTransactionKey("k"))
            repositories.importEngine.import(
                ImportBatch(
                    transfers = emptyList(),
                    relationshipMutations =
                        listOf(
                            RelationshipMutation.Create(
                                RelationshipTypeId(WellKnownIds.RECONCILIATION_MATCH_RELATIONSHIP_TYPE_ID),
                                listOf(
                                    RelationshipMember(doomed, RelationshipRole.PRIMARY),
                                    RelationshipMember(kept, RelationshipRole.RELATED),
                                ),
                                Source.Manual,
                            ),
                        ),
                ),
            )

            val report =
                materialize(
                    ObservationMutation.Delete(ObservationOwner.Record(first), "transfer"),
                    candidateAccountIds = setOf(bank, shop),
                ).materialization

            assertEquals(setOf(doomed), report.deleted)
            assertNull(transfer(doomed))
            assertNotNull(transfer(kept))
            assertEquals(1, report.orphanedMatches.size)
            assertTrue(report.orphanedRelationships.isEmpty())
            // Bank still holds the kept transfer; Shop is empty, and its fee account follows it.
            assertEquals(setOf(shop, shopFees), report.deletedAccountIds)
            assertNull(repositories.accountRepository.getAccountById(shop).first())
            assertNull(repositories.accountRepository.getAccountById(shopFees).first())
            assertNotNull(repositories.accountRepository.getAccountById(bank).first())
        }

    @Test
    fun `merge and unmerge move observation ends`() =
        runDbTest {
            val gbp = gbp()
            val bank = account("Bank")
            val shop = account("Shop")
            val store = account("Store")
            val (record) = records(1)
            val id =
                materialize(
                    upsert(record, ObservationTransaction.New(key), content(bank, shop, gbp)),
                ).materialization.newTransactionIds.getValue(key)

            val mergeId = repositories.accountRepository.mergeAccounts(deletedAccount = shop, survivingAccount = store)
            val merged = repositories.observationRepository.getObservationsOfTransaction(id).single()
            assertEquals(store, (merged.content.values as ObservedValues.Transfer).targetAccountId)
            assertFalse(materialize(transactionIds = setOf(id)).materialization.wroteRows)

            repositories.accountRepository.unmergeAccount(mergeId)
            val unmerged = repositories.observationRepository.getObservationsOfTransaction(id).single()
            assertEquals(shop, (unmerged.content.values as ObservedValues.Transfer).targetAccountId)
        }

    @Test
    fun `the merge does not depend on the order of the observations`() =
        runDbTest {
            val gbp = gbp()
            val bank = AccountId(1)
            val shop = AccountId(2)
            val placeholder = AccountId(3)
            // A card statement: exact amount, approximate time, an unnamed payee.
            val card =
                content(
                    bank,
                    placeholder,
                    gbp,
                    description = "",
                    facts = ObservationFacts(approximateTime = true, placeholderEnds = setOf(ObservationEnd.TARGET)),
                )
            // The shop's receipt: exact time, names the shop, but reports the gross amount.
            val receipt =
                content(
                    placeholder,
                    shop,
                    gbp,
                    amount = "4000000000000000000",
                    facts = ObservationFacts(placeholderEnds = setOf(ObservationEnd.SOURCE), grossAmount = true),
                )

            val merged = assertNotNull(Materializer.merge(listOf(card, receipt)))
            assertEquals(merged, Materializer.merge(listOf(receipt, card)))
            val values = merged.values as ObservedValues.Transfer
            assertEquals(bank, values.sourceAccountId)
            assertEquals(shop, values.targetAccountId)
            assertEquals(BigInteger("3500000000000000000"), values.amount)
            assertEquals("Coffee", values.description)
        }
}
