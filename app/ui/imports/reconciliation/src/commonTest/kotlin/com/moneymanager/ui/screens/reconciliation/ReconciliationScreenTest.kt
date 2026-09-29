@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.screens.reconciliation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountMerge
import com.moneymanager.domain.model.AccountMergeContext
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.CategoryBalance
import com.moneymanager.domain.model.MergeId
import com.moneymanager.domain.model.MergeMovedTransfer
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.reconciliation.ReconciliationLeg
import com.moneymanager.domain.model.reconciliation.ReconciliationLink
import com.moneymanager.domain.model.reconciliation.ReconciliationSource
import com.moneymanager.domain.model.reconciliation.ShadowAccount
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.repository.ReconciliationReadRepository
import com.moneymanager.ui.error.ProvideSchemaAwareScope
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.time.Instant

class ReconciliationScreenTest {
    private val epoch = Instant.fromEpochMilliseconds(0)
    private val koinly = ReconciliationSource("Koinly", listOf("Koinly"), linkableAccountPrefix = "Koinly · ")

    private fun reconciliationRepository(
        sources: List<ReconciliationSource>,
        shadowAccounts: List<ShadowAccount> = emptyList(),
    ): ReconciliationReadRepository =
        object : ReconciliationReadRepository {
            override fun getSources(): Flow<List<ReconciliationSource>> = flowOf(sources)

            override fun getShadowAccounts(): Flow<List<ShadowAccount>> = flowOf(shadowAccounts)

            override fun getLinks(): Flow<List<ReconciliationLink>> = flowOf(emptyList())

            override suspend fun getLegs(accountIds: Collection<AccountId>): List<ReconciliationLeg> = emptyList()
        }

    private fun accountRepository(accounts: List<Account>): AccountReadRepository =
        object : AccountReadRepository {
            override fun getAllAccounts(): Flow<List<Account>> = flowOf(accounts)

            override fun getAccountById(id: AccountId): Flow<Account?> = flowOf(accounts.find { it.id == id })

            override suspend fun getPreviousAccountNames(): Map<String, AccountId> = emptyMap()

            override suspend fun getAccountIdsByAttribute(
                attributeTypeId: Long,
                value: String?,
            ): Set<AccountId> = emptySet()

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

    private val categoryRepository =
        object : CategoryReadRepository {
            override fun getAllCategories(): Flow<List<Category>> = flowOf(emptyList())

            override fun getCategoryBalances(): Flow<List<CategoryBalance>> = flowOf(emptyList())

            override fun getCategoryById(id: Long): Flow<Category?> = flowOf(null)

            override fun getTopLevelCategories(): Flow<List<Category>> = flowOf(emptyList())

            override fun getCategoriesByParent(parentId: Long): Flow<List<Category>> = flowOf(emptyList())
        }

    private val personRepository =
        object : PersonReadRepository {
            override fun getAllPeople(): Flow<List<Person>> = flowOf(emptyList())

            override fun getPersonById(id: PersonId): Flow<Person?> = flowOf(null)
        }

    @Test
    fun explainsHowToStartWhenThereAreNoSources() {
        runMoneyManagerComposeUiTest {
            setContent {
                ProvideSchemaAwareScope {
                    ReconciliationScreen(
                        reconciliationRepository = reconciliationRepository(emptyList()),
                        accountRepository = accountRepository(emptyList()),
                        categoryRepository = categoryRepository,
                        personRepository = personRepository,
                        onOpenLeg = {},
                    )
                }
            }

            onNodeWithText("No reconciliation sources yet", substring = true).assertIsDisplayed()
        }
    }

    @Test
    fun listsAWalletItCannotLinkWithASuggestion() {
        runMoneyManagerComposeUiTest {
            val wallet = ShadowAccount(AccountId(100), "Koinly · Crypto.com App", "Koinly")
            val accounts =
                listOf(
                    Account(id = AccountId(1), name = "Crypto.com", openingDate = epoch),
                    Account(id = wallet.accountId, name = wallet.name, openingDate = epoch),
                )
            setContent {
                ProvideSchemaAwareScope {
                    ReconciliationScreen(
                        reconciliationRepository = reconciliationRepository(listOf(koinly), listOf(wallet)),
                        accountRepository = accountRepository(accounts),
                        categoryRepository = categoryRepository,
                        personRepository = personRepository,
                        onOpenLeg = {},
                    )
                }
            }

            waitForIdle()
            onNodeWithText("Needs attention: 1 Koinly wallet(s) not linked to an account").assertIsDisplayed()
            onNodeWithText("Link to suggested account \"Crypto.com\"").assertIsDisplayed()
        }
    }
}
