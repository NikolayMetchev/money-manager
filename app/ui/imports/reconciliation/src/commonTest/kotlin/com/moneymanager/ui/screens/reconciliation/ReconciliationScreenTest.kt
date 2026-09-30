@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.screens.reconciliation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.reconciliation.ReconciliationLink
import com.moneymanager.domain.model.reconciliation.ReconciliationSource
import com.moneymanager.domain.model.reconciliation.ShadowAccount
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.repository.ReconciliationReadRepository
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportResult
import com.moneymanager.importengineapi.ReconciliationLinkMutation
import com.moneymanager.ui.error.ProvideSchemaAwareScope
import com.moneymanager.ui.foundation.LocalImportEngine
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ReconciliationScreenTest {
    private val epoch = Instant.fromEpochMilliseconds(0)
    private val koinly = ReconciliationSource("Koinly", listOf("Koinly"), linkableAccountPrefix = "Koinly · ")

    private fun reconciliationRepository(
        sources: List<ReconciliationSource>,
        shadowAccounts: List<ShadowAccount> = emptyList(),
        links: Flow<List<ReconciliationLink>> = flowOf(emptyList()),
    ): ReconciliationReadRepository =
        mock {
            every { getSources() } returns flowOf(sources)
            every { getShadowAccounts() } returns flowOf(shadowAccounts)
            every { getLinks() } returns links
            everySuspend { getLegs(any()) } returns emptyList()
        }

    private fun accountRepository(accounts: List<Account>): AccountReadRepository =
        mock {
            every { getAllAccounts() } returns flowOf(accounts)
            everySuspend { getAccountIdsByAttribute(any(), any()) } returns emptySet()
        }

    private fun importEngine(onImport: suspend (ImportBatch) -> ImportResult): ImportEngine =
        mock { everySuspend { import(any(), any(), any()) } calls { (batch: ImportBatch) -> onImport(batch) } }

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

    @Test
    fun createAllCreatesAndLinksAnAccountPerWallet() {
        runMoneyManagerComposeUiTest {
            val wallets =
                listOf(
                    ShadowAccount(AccountId(100), "Koinly · Ledger", "Koinly"),
                    // An account already carries this name, so "Create all" leaves it for the user.
                    ShadowAccount(AccountId(101), "Koinly · Binance", "Koinly"),
                )
            val accounts =
                listOf(Account(id = AccountId(1), name = "Binance", openingDate = epoch)) +
                    wallets.map { Account(id = it.accountId, name = it.name, openingDate = epoch) }
            val batches = mutableListOf<ImportBatch>()
            val engine =
                importEngine { batch ->
                    batches += batch
                    ImportResult(createdAccountIds = batch.accountsToCreate.associate { it.key to AccountId(500) })
                }
            // The links flow is static, so the Binance wallet stays unlinked despite its exact match.
            setContent {
                CompositionLocalProvider(LocalImportEngine provides engine) {
                    ProvideSchemaAwareScope {
                        ReconciliationScreen(
                            reconciliationRepository = reconciliationRepository(listOf(koinly), wallets),
                            accountRepository = accountRepository(accounts),
                            categoryRepository = categoryRepository,
                            personRepository = personRepository,
                            onOpenLeg = {},
                        )
                    }
                }
            }

            waitForIdle()
            onNodeWithText("Create all (1)").performClick()
            waitForIdle()

            val created = batches.flatMap { it.accountsToCreate }
            assertEquals(listOf("Ledger"), created.map { it.name })
            assertEquals(
                listOf(ReconciliationLinkMutation.SetLinks(AccountId(100), setOf(AccountId(500)))),
                batches.last().reconciliationLinkMutations,
            )
        }
    }

    @Test
    fun createNewAccountOpensTheDialogPrefilledWithTheWalletName() {
        runMoneyManagerComposeUiTest {
            val wallet = ShadowAccount(AccountId(100), "Koinly · Ledger", "Koinly")
            setContent {
                ProvideSchemaAwareScope {
                    ReconciliationScreen(
                        reconciliationRepository = reconciliationRepository(listOf(koinly), listOf(wallet)),
                        accountRepository =
                            accountRepository(
                                listOf(Account(id = wallet.accountId, name = wallet.name, openingDate = epoch)),
                            ),
                        categoryRepository = categoryRepository,
                        personRepository = personRepository,
                        onOpenLeg = {},
                    )
                }
            }

            waitForIdle()
            onNodeWithText("Create new account").performClick()
            waitForIdle()
            onNodeWithText("Create New Account").assertIsDisplayed()
            onNode(hasSetTextAction() and hasText("Ledger")).assertIsDisplayed()
        }
    }

    @Test
    fun anAutoLinkInterruptedByItsOwnLinkUpdateIsNotReportedAsAFailure() {
        runMoneyManagerComposeUiTest {
            val wallet = ShadowAccount(AccountId(100), "Koinly · Binance", "Koinly")
            val accounts =
                listOf(
                    Account(id = AccountId(1), name = "Binance", openingDate = epoch),
                    Account(id = wallet.accountId, name = wallet.name, openingDate = epoch),
                )
            val links = MutableStateFlow(emptyList<ReconciliationLink>())
            // Like the real engine, the links flow emits the new link before import() has returned.
            val engine =
                importEngine {
                    links.value = listOf(ReconciliationLink(wallet.accountId, AccountId(1)))
                    awaitCancellation()
                }
            setContent {
                CompositionLocalProvider(LocalImportEngine provides engine) {
                    ProvideSchemaAwareScope {
                        ReconciliationScreen(
                            reconciliationRepository = reconciliationRepository(listOf(koinly), listOf(wallet), links),
                            accountRepository = accountRepository(accounts),
                            categoryRepository = categoryRepository,
                            personRepository = personRepository,
                            onOpenLeg = {},
                        )
                    }
                }
            }

            waitForIdle()
            assertEquals(0, onAllNodesWithText("Automatic linking failed", substring = true).fetchSemanticsNodes().size)
        }
    }
}
