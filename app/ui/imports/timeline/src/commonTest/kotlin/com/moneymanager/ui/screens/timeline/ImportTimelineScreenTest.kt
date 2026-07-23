@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.ui.screens.timeline

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountMerge
import com.moneymanager.domain.model.AccountMergeContext
import com.moneymanager.domain.model.MergeId
import com.moneymanager.domain.model.MergeMovedTransfer
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonAccountOwnership
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.timeline.ImportFileDateRange
import com.moneymanager.domain.model.timeline.TimelineSourceKind
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.ImportTimelineReadRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.ui.error.ProvideSchemaAwareScope
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class ImportTimelineScreenTest {
    private fun createRepository(ranges: List<ImportFileDateRange>): ImportTimelineReadRepository =
        object : ImportTimelineReadRepository {
            override fun getCsvImportDateRanges(): Flow<List<ImportFileDateRange>> = flowOf(emptyList())

            override fun getQifImportDateRanges(): Flow<List<ImportFileDateRange>> = flowOf(emptyList())

            override fun getApiSessionDateRanges(): Flow<List<ImportFileDateRange>> = flowOf(emptyList())

            override fun getManualDateRange(): Flow<ImportFileDateRange?> = flowOf(null)

            override fun getAllDateRanges(): Flow<List<ImportFileDateRange>> = flowOf(ranges)

            override fun getCsvImportAccountRanges(): Flow<List<ImportFileDateRange>> = flowOf(emptyList())

            override fun getQifImportAccountRanges(): Flow<List<ImportFileDateRange>> = flowOf(emptyList())

            override fun getApiSessionAccountRanges(): Flow<List<ImportFileDateRange>> = flowOf(emptyList())

            override fun getManualAccountRanges(): Flow<List<ImportFileDateRange>> = flowOf(emptyList())

            override fun getAllAccountRanges(): Flow<List<ImportFileDateRange>> = flowOf(emptyList())
        }

    private fun createAccountRepository(accounts: List<Account> = emptyList()): AccountReadRepository =
        object : AccountReadRepository {
            override fun getAllAccounts(): Flow<List<Account>> = flowOf(accounts)

            override fun getAccountById(id: AccountId): Flow<Account?> = flowOf(accounts.find { it.id == id })

            override suspend fun getPreviousAccountNames(): Map<String, AccountId> = emptyMap()

            override suspend fun countTransfersByAccount(accountId: AccountId): Long = 0

            override suspend fun accountsWithTransfers(accountIds: Collection<AccountId>): Set<AccountId> = emptySet()

            override suspend fun getTransfersBetweenAccounts(
                accountA: AccountId,
                accountB: AccountId,
            ): List<Transfer> = emptyList()

            override fun getReversibleMerges(): Flow<List<AccountMerge>> = flowOf(emptyList())

            override fun getMergesForSurvivingAccount(accountId: AccountId): Flow<List<AccountMerge>> = flowOf(emptyList())

            override suspend fun getMergesForDeletedAccount(accountId: AccountId): List<AccountMergeContext> = emptyList()

            override suspend fun getMergeMovedTransfers(mergeId: MergeId): List<MergeMovedTransfer> = emptyList()
        }

    private fun createPersonRepository(people: List<Person> = emptyList()): PersonReadRepository =
        object : PersonReadRepository {
            override fun getAllPeople(): Flow<List<Person>> = flowOf(people)

            override fun getPersonById(id: PersonId): Flow<Person?> = flowOf(people.find { it.id == id })
        }

    private fun createPersonAccountOwnershipRepository(
        ownerships: List<PersonAccountOwnership> = emptyList(),
    ): PersonAccountOwnershipReadRepository =
        object : PersonAccountOwnershipReadRepository {
            override fun getOwnershipsByPerson(personId: PersonId): Flow<List<PersonAccountOwnership>> =
                flowOf(ownerships.filter { it.personId == personId })

            override fun getOwnershipsByAccount(accountId: AccountId): Flow<List<PersonAccountOwnership>> =
                flowOf(ownerships.filter { it.accountId == accountId })

            override fun getAllOwnerships(): Flow<List<PersonAccountOwnership>> = flowOf(ownerships)

            override fun getOwnershipById(id: Long): Flow<PersonAccountOwnership?> = flowOf(ownerships.find { it.id == id })
        }

    private fun range(
        strategyName: String?,
        kind: TimelineSourceKind = TimelineSourceKind.CSV,
        fileName: String = "file.csv",
    ): ImportFileDateRange =
        ImportFileDateRange(
            kind = kind,
            fileId = "$kind-$fileName",
            fileName = fileName,
            strategyName = strategyName,
            earliest = Instant.parse("2024-01-01T00:00:00Z"),
            latest = Instant.parse("2024-03-01T00:00:00Z"),
            transactionCount = 42,
        )

    @Test
    fun timelineScreen_displaysEmptyState_whenNoRanges() =
        runMoneyManagerComposeUiTest {
            setContent {
                ProvideSchemaAwareScope {
                    ImportTimelineScreen(
                        importTimelineRepository = createRepository(emptyList()),
                        accountRepository = createAccountRepository(),
                        personRepository = createPersonRepository(),
                        personAccountOwnershipRepository = createPersonAccountOwnershipRepository(),
                    )
                }
            }

            onNodeWithText("No imported transactions yet").assertIsDisplayed()
        }

    @Test
    fun timelineScreen_displaysStrategyRowsAndManualRow() =
        runMoneyManagerComposeUiTest {
            val ranges =
                listOf(
                    range(strategyName = "Monzo", kind = TimelineSourceKind.CSV, fileName = "monzo.csv"),
                    range(strategyName = "Monzo", kind = TimelineSourceKind.QIF, fileName = "monzo.qif"),
                    range(strategyName = "Crypto.com Card", kind = TimelineSourceKind.CSV, fileName = "card.csv"),
                    range(strategyName = null, kind = TimelineSourceKind.MANUAL, fileName = "Manual entries"),
                )

            setContent {
                ProvideSchemaAwareScope {
                    ImportTimelineScreen(
                        importTimelineRepository = createRepository(ranges),
                        accountRepository = createAccountRepository(),
                        personRepository = createPersonRepository(),
                        personAccountOwnershipRepository = createPersonAccountOwnershipRepository(),
                    )
                }
            }

            // Labels can appear both as a timeline row and in the gaps table below it.
            onAllNodesWithText("Monzo").onFirst().assertIsDisplayed()
            onAllNodesWithText("Crypto.com Card").onFirst().assertIsDisplayed()
            onAllNodesWithText("Manual entries").onFirst().assertIsDisplayed()
            // Monzo groups the CSV and QIF files into one row.
            onNodeWithText("2 files").assertIsDisplayed()
            // Every row's data stops before today, so the gaps table lists them.
            onNodeWithText("Gaps").assertIsDisplayed()
        }
}
