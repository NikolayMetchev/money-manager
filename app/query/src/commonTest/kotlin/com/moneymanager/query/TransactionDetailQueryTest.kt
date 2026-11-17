package com.moneymanager.query

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountRow
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.Relationship
import com.moneymanager.domain.model.RelationshipId
import com.moneymanager.domain.model.RelationshipMember
import com.moneymanager.domain.model.RelationshipRole
import com.moneymanager.domain.model.RelationshipType
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.SourceRecord
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.observation.ImportRecord
import com.moneymanager.domain.model.observation.ImportRecordId
import com.moneymanager.domain.model.observation.ImportRecordKey
import com.moneymanager.domain.model.observation.Observation
import com.moneymanager.domain.model.observation.ObservationContent
import com.moneymanager.domain.model.observation.ObservationId
import com.moneymanager.domain.model.observation.ObservationOwner
import com.moneymanager.domain.model.observation.ObservationPin
import com.moneymanager.domain.model.observation.ObservationSplit
import com.moneymanager.domain.model.observation.ObservedAssetId
import com.moneymanager.domain.model.observation.ObservedValues
import com.moneymanager.domain.model.observation.TransferField
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

class TransactionDetailQueryTest {
    private val at = Instant.parse("2024-03-01T12:00:00Z")
    private val accountId = AccountId(1)

    private fun transfer(id: Long) =
        Transfer(
            id = TransferId(id),
            timestamp = at,
            description = "transfer $id",
            sourceAccountId = AccountId(2),
            targetAccountId = accountId,
            amount = money("1", gbp),
        )

    private val transfers = listOf(10L, 11, 12, 13).associate { TransferId(it) to transfer(it) }

    private fun relationship(
        id: Long,
        type: String,
        vararg members: Pair<Long, RelationshipRole>,
        trade: Long? = null,
    ) = Relationship(
        id = RelationshipId(id),
        relationshipType = RelationshipType(RelationshipTypeId(id), type),
        members =
            members.map { (txn, role) -> RelationshipMember(TransferId(txn), role) } +
                listOfNotNull(trade?.let { RelationshipMember(TradeId(it), RelationshipRole.MEMBER) }),
    )

    @Test
    fun linksEveryOtherTransferMember_outgoingOnlyFromThePrimaryEnd() =
        runTest {
            val relationships =
                listOf(
                    relationship(1, "fee", 10L to RelationshipRole.PRIMARY, 11L to RelationshipRole.RELATED),
                    relationship(2, "reconciled", 12L to RelationshipRole.PRIMARY, 10L to RelationshipRole.RELATED),
                    // An undirected group: every other transfer member is a link, the trade member isn't (it isn't a transfer).
                    relationship(3, "group", 10L to RelationshipRole.MEMBER, 13L to RelationshipRole.MEMBER, trade = 99),
                )
            val query =
                TransactionDetailQuery(
                    transactionRepository =
                        mock {
                            every { getTransactionById(10) } returns flowOf(transfers.getValue(TransferId(10)))
                            everySuspend { getTransactionsByIds(any()) } calls
                                { (ids: Collection<TransferId>) -> transfers.filterKeys { it in ids } }
                        },
                    tradeRepository = mock(),
                    relationshipRepository = mock { every { getByTransaction(TransferId(10)) } returns flowOf(relationships) },
                    sourceRepository = mock { everySuspend { getSourcesForTransaction(any()) } returns emptyList() },
                    observationReads = noObservations(),
                )

            val detail =
                query.detail(
                    AccountRow(
                        transactionId = TransferId(10),
                        timestamp = at,
                        description = "transfer 10",
                        accountId = accountId,
                        transactionAmount = money("1", gbp),
                        runningBalance = money("1", gbp),
                        sourceAccountId = AccountId(2),
                        targetAccountId = accountId,
                    ),
                )!!

            assertEquals(
                listOf(Triple("fee", true, 11L), Triple("reconciled", false, 12L), Triple("group", false, 13L)),
                detail.links.map { Triple(it.relationship, it.outgoing, it.transfer.id.id) },
            )
        }

    @Test
    fun observationsNameTheirOwners_withPinsSplitsAndOverrides() =
        runTest {
            val importId = CsvImportId(Uuid.parse("00000000-0000-0000-0000-000000000001"))
            val record = ImportRecord(ImportRecordId(5), ImportRecordKey.CsvRow(importId, 3), "hash")
            val values =
                ObservedValues.Transfer(at, "card payment", AccountId(2), accountId, ObservedAssetId(gbp.id.id), money("1", gbp).amount)
            val observations =
                listOf(
                    Observation(ObservationId(1), ObservationOwner.Record(record.id), "main", TransferId(10), ObservationContent(values)),
                    Observation(ObservationId(2), ObservationOwner.Manual(7), "main", TransferId(10), ObservationContent(values)),
                    Observation(ObservationId(3), ObservationOwner.Manual(8), "main", TransferId(10), ObservationContent(values)),
                )
            val csvImport =
                CsvImport(
                    id = importId,
                    tableName = "csv_import_1",
                    originalFileName = "monzo.csv",
                    importTimestamp = at,
                    rowCount = 4,
                    columnCount = 1,
                    columns = emptyList(),
                    deviceInfo = DeviceInfo.Jvm("Linux", "host"),
                    fileChecksum = "",
                    fileLastModified = at,
                )
            val reads =
                ObservationReads(
                    observationRepository =
                        mock {
                            everySuspend { getObservationsOfTransaction(TransferId(10)) } returns observations
                            everySuspend { getObservation(ObservationId(99)) } returns
                                observations[0].copy(id = ObservationId(99), transactionId = TransferId(20))
                        },
                    personOverrideRepository =
                        mock {
                            everySuspend { getPinsOnTransactions(any()) } returns listOf(ObservationPin(ObservationId(1), TransferId(10)))
                            everySuspend { getSplits(any()) } returns setOf(ObservationSplit.of(ObservationId(99), ObservationId(1)))
                            everySuspend { getTransferOverrides(TransferId(10)) } returns mapOf(TransferField.DESCRIPTION to "Rent")
                            everySuspend { getTransferAttributeOverrides(TransferId(10)) } returns emptyList()
                        },
                    importRecordRepository =
                        mock {
                            everySuspend { getRecords(any()) } returns listOf(record)
                            everySuspend { getGroupsById(any()) } returns emptyList()
                        },
                    csvImportRepository = mock { every { getImport(importId) } returns flowOf(csvImport) },
                    attributeTypeRepository = mock(),
                    currencyRepository = mock(),
                    cryptoRepository = mock(),
                )

            // A Manual owner whose creation came from the Reconciliation tab was copied there, not typed in.
            val copied = SourceRecord(8, EntityType.TRANSFER, 10, 1, Source.Reconciliation, 1, null, null, at)
            val result = reads.observationsOf(TransferId(10), listOf(gbp), listOf(copied))

            assertEquals(
                listOf(
                    Triple("monzo.csv row 3", "main", true),
                    Triple("by hand", "main", false),
                    Triple("copied in reconciliation", "main", false),
                ),
                result.observations.map { Triple(it.ownerLabel, it.slot, it.pinned) },
            )
            assertEquals(listOf(TransactionLeg(AccountId(2), accountId, money("1", gbp))), result.observations[0].legs)
            assertEquals(listOf(DetailSplit(ObservationId(1), ObservationId(99), TransferId(20))), result.splits)
            assertEquals(listOf(DetailOverride("description", "Rent")), result.overrides)
        }
}
