package com.moneymanager.ui.screens.transactions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilExactlyOneExists
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Relationship
import com.moneymanager.domain.model.RelationshipId
import com.moneymanager.domain.model.RelationshipMember
import com.moneymanager.domain.model.RelationshipRole
import com.moneymanager.domain.model.RelationshipType
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.RelationshipReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportResult
import com.moneymanager.importengineapi.RelationshipMutation
import com.moneymanager.presenter.error.ProvideSchemaAwareScope
import com.moneymanager.ui.foundation.LocalImportEngine
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import dev.mokkery.MockMode
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class ReversalLinkSectionTest {
    private val gbp = CryptoAsset(id = CryptoId(1), code = "GBX", name = "Test pound")
    private val monzo = Account(id = AccountId(1), name = "Monzo", openingDate = Instant.parse("2020-01-01T00:00:00Z"))
    private val marcus = Account(id = AccountId(2), name = "Marcus", openingDate = Instant.parse("2020-01-01T00:00:00Z"))
    private val goldman = Account(id = AccountId(3), name = "Goldman", openingDate = Instant.parse("2020-01-01T00:00:00Z"))
    private val t0 = Instant.parse("2022-11-14T22:54:00Z")
    private val reversalType = RelationshipType(RelationshipTypeId(WellKnownIds.REVERSAL_RELATIONSHIP_TYPE_ID), "reversal")

    private fun transfer(
        id: Long,
        from: AccountId,
        to: AccountId,
        after: Duration = Duration.ZERO,
    ) = Transfer(
        id = TransferId(id),
        timestamp = t0 + after,
        description = "",
        sourceAccountId = from,
        targetAccountId = to,
        amount = Money(1000, gbp),
    )

    private val payment = transfer(1, monzo.id, marcus.id)
    private val returned = transfer(2, goldman.id, monzo.id, after = 1.hours)

    private fun accountRepository(): AccountReadRepository =
        mock<AccountReadRepository>(MockMode.autoUnit) {
            every { getAllAccounts() } returns flowOf(listOf(monzo, marcus, goldman))
        }

    private fun relationshipRepository(relationships: List<Relationship>): RelationshipReadRepository =
        mock<RelationshipReadRepository>(MockMode.autoUnit) {
            every { getByTransaction(any()) } returns flowOf(relationships)
        }

    private fun transactionRepository(
        candidates: List<Transfer> = emptyList(),
        linked: Map<TransferId, Transfer> = emptyMap(),
    ): TransactionReadRepository =
        mock<TransactionReadRepository>(MockMode.autoUnit) {
            everySuspend { getUnreversedTransfersTouchingAccounts(any(), any(), any(), any()) } returns candidates
            everySuspend { getTransactionsByIds(any()) } returns linked
        }

    private fun maintenance(): Maintenance =
        mock<Maintenance>(MockMode.autoUnit) {
            everySuspend { refreshMaterializedViews() } returns Duration.ZERO
        }

    @Composable
    private fun Section(
        transfer: Transfer,
        transactions: TransactionReadRepository,
        relationships: RelationshipReadRepository,
        importEngine: ImportEngine,
        onChanged: () -> Unit,
    ) {
        val accounts = remember { accountRepository() }
        val maintenance = remember { maintenance() }
        CompositionLocalProvider(LocalImportEngine provides importEngine) {
            ProvideSchemaAwareScope {
                ReversalLinkSection(
                    transfer = transfer,
                    transactionRepository = transactions,
                    relationshipRepository = relationships,
                    accountRepository = accounts,
                    maintenance = maintenance,
                    enabled = true,
                    onChanged = onChanged,
                )
            }
        }
    }

    private fun recordingEngine(batches: MutableList<ImportBatch>): ImportEngine {
        val engine = mock<ImportEngine>(MockMode.autoUnit)
        everySuspend { engine.import(any()) } calls { (batch: ImportBatch) ->
            batches += batch
            ImportResult()
        }
        return engine
    }

    @Test
    fun pickingAReturnLinksItAsTheReversalAndExcludesBothByDefault() {
        runMoneyManagerComposeUiTest {
            val batches = mutableListOf<ImportBatch>()
            var changed = false
            setContent {
                Section(
                    transfer = payment,
                    transactions = transactionRepository(candidates = listOf(returned)),
                    relationships = relationshipRepository(emptyList()),
                    importEngine = recordingEngine(batches),
                    onChanged = { changed = true },
                )
            }

            onNodeWithText("Link as reversal…").performClick()
            waitUntilExactlyOneExists(hasTestTag("reversalCandidate-2"))
            onNodeWithTag("reversalCandidate-2").performClick()
            onNodeWithText("Link").performClick()
            waitUntil(timeoutMillis = 5_000) { changed }

            val batch = batches.single()
            val link = assertIs<RelationshipMutation.Create>(batch.relationshipMutations.single())
            assertEquals(RelationshipTypeId(WellKnownIds.REVERSAL_RELATIONSHIP_TYPE_ID), link.typeId)
            assertEquals(
                listOf(
                    RelationshipMember(TransferId(2), RelationshipRole.PRIMARY),
                    RelationshipMember(TransferId(1), RelationshipRole.RELATED),
                ),
                link.members,
            )
            assertEquals(2, batch.transfers.size)
        }
    }

    @Test
    fun untickingExcludeBothLinksWithoutExcludingAnything() {
        runMoneyManagerComposeUiTest {
            val batches = mutableListOf<ImportBatch>()
            var changed = false
            setContent {
                Section(
                    transfer = payment,
                    transactions = transactionRepository(candidates = listOf(returned)),
                    relationships = relationshipRepository(emptyList()),
                    importEngine = recordingEngine(batches),
                    onChanged = { changed = true },
                )
            }

            onNodeWithText("Link as reversal…").performClick()
            waitUntilExactlyOneExists(hasTestTag("reversalCandidate-2"))
            onNodeWithTag("reversalCandidate-2").performClick()
            onNodeWithTag("reversalExcludeBoth").performClick()
            onNodeWithText("Link").performClick()
            waitUntil(timeoutMillis = 5_000) { changed }

            val batch = batches.single()
            assertIs<RelationshipMutation.Create>(batch.relationshipMutations.single())
            assertTrue(batch.transfers.isEmpty())
        }
    }

    @Test
    fun pickerWithNoMatchingTransferSaysSo() {
        runMoneyManagerComposeUiTest {
            setContent {
                Section(
                    transfer = payment,
                    transactions = transactionRepository(candidates = emptyList()),
                    relationships = relationshipRepository(emptyList()),
                    importEngine = recordingEngine(mutableListOf()),
                    onChanged = {},
                )
            }

            onNodeWithText("Link as reversal…").performClick()
            waitUntilExactlyOneExists(hasText("No transfer of", substring = true))
        }
    }

    @Test
    fun existingReversalShowsItsOtherSideAndUnlinkDeletesIt() {
        runMoneyManagerComposeUiTest {
            val batches = mutableListOf<ImportBatch>()
            var changed = false
            val link =
                Relationship(
                    id = RelationshipId(9),
                    relationshipType = reversalType,
                    members =
                        listOf(
                            RelationshipMember(TransferId(2), RelationshipRole.PRIMARY),
                            RelationshipMember(TransferId(1), RelationshipRole.RELATED),
                        ),
                )
            setContent {
                Section(
                    transfer = payment,
                    transactions =
                        transactionRepository(
                            linked = mapOf(TransferId(2) to returned),
                        ),
                    relationships = relationshipRepository(listOf(link)),
                    importEngine = recordingEngine(batches),
                    onChanged = { changed = true },
                )
            }

            waitUntilExactlyOneExists(hasText("Reversed by", substring = true))
            onNodeWithTag("unlinkReversalButton").performClick()
            waitUntil(timeoutMillis = 5_000) { changed }

            val batch = batches.single()
            val delete = assertIs<RelationshipMutation.Delete>(batch.relationshipMutations.single())
            assertEquals(RelationshipId(9), delete.id)
        }
    }
}
