@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.screens.reconciliation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.moneymanager.bigdecimal.BigDecimal
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.CryptoAsset
import com.moneymanager.domain.model.CryptoId
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.RelationshipId
import com.moneymanager.domain.model.RelationshipMember
import com.moneymanager.domain.model.RelationshipRole
import com.moneymanager.domain.model.RelationshipTypeId
import com.moneymanager.domain.model.Source
import com.moneymanager.domain.model.TradeId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.reconciliation.IGNORED_IN_RECONCILIATION
import com.moneymanager.domain.model.reconciliation.LegTransactionKind
import com.moneymanager.domain.model.reconciliation.ReconciliationLeg
import com.moneymanager.domain.model.reconciliation.ReconciliationLink
import com.moneymanager.domain.model.reconciliation.ReconciliationSource
import com.moneymanager.domain.model.reconciliation.ShadowAccount
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.repository.ReconciliationReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportResult
import com.moneymanager.importengineapi.ReconciliationLinkMutation
import com.moneymanager.importengineapi.RelationshipMutation
import com.moneymanager.importengineapi.TradeAttributeMutation
import com.moneymanager.presenter.error.ProvideSchemaAwareScope
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
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class ReconciliationScreenTest {
    private val epoch = Instant.fromEpochMilliseconds(0)
    private val koinly = ReconciliationSource("Koinly", listOf("Koinly"), linkableAccountPrefix = "Koinly · ")

    private fun reconciliationRepository(
        sources: List<ReconciliationSource>,
        shadowAccounts: List<ShadowAccount> = emptyList(),
        links: Flow<List<ReconciliationLink>> = flowOf(emptyList()),
        legs: (Collection<AccountId>) -> List<ReconciliationLeg> = { emptyList() },
    ): ReconciliationReadRepository =
        mock {
            every { getSources() } returns flowOf(sources)
            every { getShadowAccounts() } returns flowOf(shadowAccounts)
            every { getLinks() } returns links
            everySuspend { getLegs(any()) } calls { (ids: Collection<AccountId>) -> legs(ids) }
        }

    private val transactionRepository: TransactionReadRepository = mock()

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
                        transactionRepository = transactionRepository,
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
                        transactionRepository = transactionRepository,
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
                            transactionRepository = transactionRepository,
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
                        transactionRepository = transactionRepository,
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
                            transactionRepository = transactionRepository,
                            onOpenLeg = {},
                        )
                    }
                }
            }

            waitForIdle()
            assertEquals(0, onAllNodesWithText("Automatic linking failed", substring = true).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun walletControlsAreDisabledWhileCreateAllRuns() {
        runMoneyManagerComposeUiTest {
            val wallet = ShadowAccount(AccountId(100), "Koinly · Ledger", "Koinly")
            setContent {
                CompositionLocalProvider(LocalImportEngine provides importEngine { awaitCancellation() }) {
                    ProvideSchemaAwareScope {
                        ReconciliationScreen(
                            reconciliationRepository = reconciliationRepository(listOf(koinly), listOf(wallet)),
                            accountRepository =
                                accountRepository(listOf(Account(id = wallet.accountId, name = wallet.name, openingDate = epoch))),
                            categoryRepository = categoryRepository,
                            personRepository = personRepository,
                            transactionRepository = transactionRepository,
                            onOpenLeg = {},
                        )
                    }
                }
            }

            waitForIdle()
            onNodeWithText("Create all (1)").performClick()
            waitForIdle()
            onNodeWithText("Create new account").assertIsNotEnabled()
            onNodeWithText("Create all (1)").assertIsNotEnabled()
        }
    }

    private val ada = CryptoAsset(id = CryptoId(1), code = "ADA", name = "Cardano")
    private val t0 = Instant.parse("2024-01-10T12:00:00Z")
    private val wallet = ShadowAccount(AccountId(100), "Koinly · Binance", "Koinly")
    private val binance = AccountId(1)

    private fun leg(
        id: Long,
        account: AccountId,
        amount: String,
        at: Instant,
        trade: Boolean = false,
        matchIds: Set<Long> = emptySet(),
    ) = ReconciliationLeg(
        transactionId = id,
        kind = if (trade) LegTransactionKind.TRADE else LegTransactionKind.TRANSFER,
        timestamp = at,
        description = "leg $id",
        accountId = account,
        counterpartyAccountId = if (trade) null else AccountId(999),
        amount = Money.fromDisplayValue(BigDecimal(amount), ada),
        matchIds = matchIds,
    )

    /** Shows the screen over one linked Binance wallet and records every batch the engine gets. */
    private fun ComposeUiTest.showLegs(
        sourceLegs: List<ReconciliationLeg>,
        realLegs: List<ReconciliationLeg>,
    ): List<ImportBatch> {
        val batches = mutableListOf<ImportBatch>()
        val engine =
            importEngine { batch ->
                batches += batch
                ImportResult()
            }
        val accounts =
            listOf(
                Account(id = binance, name = "Binance", openingDate = epoch),
                Account(id = wallet.accountId, name = wallet.name, openingDate = epoch),
            )
        setContent {
            CompositionLocalProvider(LocalImportEngine provides engine) {
                ProvideSchemaAwareScope {
                    ReconciliationScreen(
                        reconciliationRepository =
                            reconciliationRepository(
                                listOf(koinly),
                                listOf(wallet),
                                flowOf(listOf(ReconciliationLink(wallet.accountId, binance))),
                            ) { ids -> if (wallet.accountId in ids) sourceLegs else realLegs },
                        accountRepository = accountRepository(accounts),
                        categoryRepository = categoryRepository,
                        personRepository = personRepository,
                        transactionRepository = transactionRepository,
                        onOpenLeg = {},
                    )
                }
            }
        }
        waitForIdle()
        return batches
    }

    /** Opens every collapsed wallet and month group of the visible tab. */
    private fun ComposeUiTest.expandAll() {
        val collapsed = hasText("▸", substring = true) and !hasText("Linked wallets", substring = true)
        repeat(MAX_GROUPS) {
            if (!reveal(collapsed)) return
            onAllNodes(collapsed)[0].performClick()
            waitForIdle()
        }
    }

    /** Scrolls the screen's list until a node matching [matcher] is composed; false when there's none. */
    private fun ComposeUiTest.reveal(matcher: SemanticsMatcher): Boolean =
        runCatching { onAllNodes(hasScrollAction())[0].performScrollToNode(matcher) }.isSuccess

    private fun ComposeUiTest.clickText(text: String) {
        reveal(hasText(text))
        onNodeWithText(text).performClick()
        waitForIdle()
    }

    @Test
    fun acceptingANearMatchStoresItAsAMatch() {
        runMoneyManagerComposeUiTest {
            val batches = showLegs(listOf(leg(10, wallet.accountId, "5", t0)), listOf(leg(20, binance, "5", t0 + 1.hours)))
            reveal(hasText("Near matches (1)"))
            onNodeWithText("Near matches (1)").assertIsDisplayed()
            expandAll()

            clickText("Accept")

            assertEquals(
                listOf(
                    RelationshipMutation.Create(
                        RelationshipTypeId(WellKnownIds.RECONCILIATION_MATCH_RELATIONSHIP_TYPE_ID),
                        listOf(
                            RelationshipMember(TransferId(10), RelationshipRole.MEMBER),
                            RelationshipMember(TransferId(20), RelationshipRole.MEMBER),
                        ),
                        Source.Manual,
                    ),
                ),
                batches.single().relationshipMutations,
            )
        }
    }

    private fun match(id: Long) = RelationshipMember(TransferId(id), RelationshipRole.MEMBER)

    @Test
    fun acceptAllAtEveryLevelStoresEveryNearMatchBeneathItInOneBatch() {
        runMoneyManagerComposeUiTest {
            val batches =
                showLegs(
                    listOf(leg(10, wallet.accountId, "5", t0), leg(11, wallet.accountId, "6", t0 + 1.hours)),
                    listOf(leg(20, binance, "5", t0 + 1.hours), leg(21, binance, "6", t0 + 2.hours)),
                )
            reveal(hasText("Near matches (2)"))
            expandAll()
            // The month header's button sits in the same row, so it is composed once the month is revealed.
            reveal(hasText("2024-01 (2)", substring = true))
            val buttons = onAllNodesWithText("Accept all (2)")
            buttons[buttons.fetchSemanticsNodes().size - 1].performClick()
            waitForIdle()

            val create = batches.single().relationshipMutations.filterIsInstance<RelationshipMutation.Create>()
            assertEquals(listOf(listOf(match(10), match(20)), listOf(match(11), match(21))), create.map { it.members })
        }
    }

    @Test
    fun acceptAllStoresTheAutoMatchedRowsToo() {
        runMoneyManagerComposeUiTest {
            // 10↔20 agree to the second (auto-matched), 11↔21 are an hour apart (near).
            val batches =
                showLegs(
                    listOf(leg(10, wallet.accountId, "5", t0), leg(11, wallet.accountId, "6", t0 + 1.hours)),
                    listOf(leg(20, binance, "5", t0), leg(21, binance, "6", t0 + 2.hours)),
                )
            reveal(hasText("Near matches (1)"))

            onAllNodesWithText("Accept all (2)")[0].performClick()
            waitForIdle()

            val create = batches.single().relationshipMutations.filterIsInstance<RelationshipMutation.Create>()
            assertEquals(setOf(listOf(match(10), match(20)), listOf(match(11), match(21))), create.map { it.members }.toSet())
        }
    }

    @Test
    fun theMatchedTabOffersAcceptAllForAutoMatchedRows() {
        runMoneyManagerComposeUiTest {
            val batches = showLegs(listOf(leg(10, wallet.accountId, "5", t0)), listOf(leg(20, binance, "5", t0)))
            clickText("Matched")

            onAllNodesWithText("Accept all (1)")[0].performClick()
            waitForIdle()

            assertEquals(
                listOf(listOf(match(10), match(20))),
                batches
                    .single()
                    .relationshipMutations
                    .filterIsInstance<RelationshipMutation.Create>()
                    .map { it.members },
            )
        }
    }

    @Test
    fun ignoreAllAtTheSectionExcludesEveryMovementBeneathIt() {
        runMoneyManagerComposeUiTest {
            val batches =
                showLegs(
                    listOf(leg(11, wallet.accountId, "7", t0, trade = true), leg(12, wallet.accountId, "8", t0 + 1.hours, trade = true)),
                    // ADA is known on the linked account, so the default hide-unknown-assets filter keeps both rows.
                    listOf(leg(20, binance, "1", t0 + 30.days)),
                )
            reveal(hasText("Only in Koinly (2)"))

            onAllNodesWithText("Ignore all (2)")[0].performScrollTo().performClick()
            waitForIdle()

            assertEquals(
                listOf(11L, 12L).map {
                    TradeAttributeMutation.Set(TradeId(it), AttributeTypeId(WellKnownIds.EXCLUDED_ATTR_TYPE_ID), IGNORED_IN_RECONCILIATION)
                },
                batches.flatMap { it.tradeAttributeMutations },
            )
        }
    }

    @Test
    fun ignoringASourceOnlyTradeExcludesIt() {
        runMoneyManagerComposeUiTest {
            val batches = showLegs(listOf(leg(11, wallet.accountId, "7", t0, trade = true)), listOf(leg(20, binance, "5", t0)))
            reveal(hasText("Only in Koinly (1)"))
            onNodeWithText("Only in Koinly (1)").assertIsDisplayed()
            expandAll()

            clickText("Ignore")
            onAllNodesWithText("Ignoring failed", substring = true).assertCountEquals(0)

            assertEquals(
                listOf(
                    TradeAttributeMutation.Set(TradeId(11), AttributeTypeId(WellKnownIds.EXCLUDED_ATTR_TYPE_ID), IGNORED_IN_RECONCILIATION),
                ),
                batches.single().tradeAttributeMutations,
            )
        }
    }

    @Test
    fun thePickerOffersOnlyUnclaimedLegsAndStoresTheSelection() {
        runMoneyManagerComposeUiTest {
            val batches =
                showLegs(
                    listOf(leg(10, wallet.accountId, "5", t0), leg(11, wallet.accountId, "7", t0 + 2.days, trade = true)),
                    listOf(leg(20, binance, "5", t0 + 1.hours), leg(21, binance, "6.9", t0 + 2.days + 1.hours)),
                )
            expandAll()

            clickText("Select matching transactions…")
            // Leg 20 is the near match for leg 10, so it's listed but can't be picked.
            val picker = onNodeWithTag(PICKER_CANDIDATES_TAG)
            picker.performScrollToNode(isToggleable() and hasText("leg 20", substring = true))
            onNode(isToggleable() and hasText("leg 20", substring = true)).assertIsNotEnabled()
            onNodeWithText("Near match for", substring = true).assertExists()
            picker.performScrollToNode(isToggleable() and hasText("leg 21", substring = true))
            onNode(isToggleable() and hasText("leg 21", substring = true)).performClick()
            waitForIdle()
            onNodeWithText("Match (1)").performClick()
            waitForIdle()

            assertEquals(
                listOf(TradeId(11), TransferId(21)),
                batches
                    .single()
                    .relationshipMutations
                    .filterIsInstance<RelationshipMutation.Create>()
                    .single()
                    .members
                    .map { it.transactionId },
            )
        }
    }

    @Test
    fun aStoredMatchCanBeUnmatched() {
        runMoneyManagerComposeUiTest {
            val batches =
                showLegs(
                    listOf(leg(10, wallet.accountId, "5", t0, matchIds = setOf(42))),
                    listOf(leg(20, binance, "4.8", t0 + 3.days, matchIds = setOf(42))),
                )
            clickText("Matched")
            expandAll()

            reveal(hasText("Matched by hand"))
            onNodeWithText("Matched by hand").assertIsDisplayed()
            clickText("Unmatch")

            assertEquals(listOf(RelationshipMutation.Delete(RelationshipId(42))), batches.single().relationshipMutations)
        }
    }

    private companion object {
        const val MAX_GROUPS = 20
    }
}
