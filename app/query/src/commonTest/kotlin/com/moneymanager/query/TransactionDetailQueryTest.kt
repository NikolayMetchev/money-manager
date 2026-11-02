package com.moneymanager.query

import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountRow
import com.moneymanager.domain.model.Relationship
import com.moneymanager.domain.model.RelationshipId
import com.moneymanager.domain.model.RelationshipMember
import com.moneymanager.domain.model.RelationshipRole
import com.moneymanager.domain.model.RelationshipType
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
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
}
