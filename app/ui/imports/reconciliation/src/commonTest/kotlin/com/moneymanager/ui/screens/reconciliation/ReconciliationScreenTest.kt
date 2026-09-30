@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.screens.reconciliation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.reconciliation.ReconciliationSource
import com.moneymanager.domain.model.reconciliation.ShadowAccount
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.repository.ReconciliationReadRepository
import com.moneymanager.ui.error.ProvideSchemaAwareScope
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
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
        mock {
            every { getSources() } returns flowOf(sources)
            every { getShadowAccounts() } returns flowOf(shadowAccounts)
            every { getLinks() } returns flowOf(emptyList())
            everySuspend { getLegs(any()) } returns emptyList()
        }

    private fun accountRepository(accounts: List<Account>): AccountReadRepository =
        mock {
            every { getAllAccounts() } returns flowOf(accounts)
            everySuspend { getAccountIdsByAttribute(any(), any()) } returns emptySet()
        }

    private val categoryRepository: CategoryReadRepository =
        mock { every { getAllCategories() } returns flowOf(emptyList()) }

    private val personRepository: PersonReadRepository =
        mock { every { getAllPeople() } returns flowOf(emptyList()) }

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
