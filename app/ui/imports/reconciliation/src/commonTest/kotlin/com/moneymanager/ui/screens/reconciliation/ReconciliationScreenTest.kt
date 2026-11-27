@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.screens.reconciliation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
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
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.reconciliation.IGNORED_IN_RECONCILIATION
import com.moneymanager.domain.model.reconciliation.LegTransactionKind
import com.moneymanager.domain.model.reconciliation.ReconciliationFocus
import com.moneymanager.domain.model.reconciliation.ReconciliationLeg
import com.moneymanager.domain.model.reconciliation.ReconciliationLink
import com.moneymanager.domain.model.reconciliation.ReconciliationSource
import com.moneymanager.domain.model.reconciliation.ShadowAccount
import com.moneymanager.domain.model.reconciliation.reconciliationMonth
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.domain.repository.ReconciliationReadRepository
import com.moneymanager.domain.repository.TransactionReadRepository
import com.moneymanager.importengineapi.AccountRef
import com.moneymanager.importengineapi.ImportBatch
import com.moneymanager.importengineapi.ImportEngine
import com.moneymanager.importengineapi.ImportResult
import com.moneymanager.importengineapi.ReconciliationLinkMutation
import com.moneymanager.importengineapi.RelationshipMutation
import com.moneymanager.importengineapi.TradeAttributeMutation
import com.moneymanager.presenter.error.ProvideSchemaAwareScope
import com.moneymanager.tasks.TaskManager
import com.moneymanager.ui.foundation.LocalImportEngine
import com.moneymanager.ui.foundation.ReconciliationFocusState
import com.moneymanager.ui.tasks.LocalTaskManager
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** The schema-aware scope the screen needs, and the task manager an app-level host would provide. */
@Composable
private fun provideScreenScopes(content: @Composable () -> Unit) {
    val tasks = remember { TaskManager(CoroutineScope(Dispatchers.Default)) }
    CompositionLocalProvider(LocalTaskManager provides tasks) { ProvideSchemaAwareScope(content) }
}

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
        mock {
            val writes = MutableStateFlow(0L)
            every { writeVersion } returns writes
            everySuspend { import(any(), any(), any()) } calls { (batch: ImportBatch) ->
                onImport(batch).also { writes.value += 1 }
            }
        }

    private val categoryRepository: CategoryReadRepository =
        mock { every { getAllCategories() } returns flowOf(emptyList()) }

    private val personRepository: PersonReadRepository =
        mock { every { getAllPeople() } returns flowOf(emptyList()) }

    @Test
    fun explainsHowToStartWhenThereAreNoSources() {
        runMoneyManagerComposeUiTest {
            setContent {
                provideScreenScopes {
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
                provideScreenScopes {
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

            val heading = "Needs attention: 1 Koinly wallet(s) not linked to an account"
            val suggestion = "Link to suggested account \"Crypto.com\""
            waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) { reveal(hasText(heading)) }
            onNodeWithText(heading).assertIsDisplayed()
            waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) { reveal(hasText(suggestion)) }
            onNodeWithText(suggestion).assertIsDisplayed()
        }
    }

    @Test
    fun moneyManagerSourceHasDistinctSectionsForTheImportedAndLocalLedgers() {
        runMoneyManagerComposeUiTest {
            val source = ReconciliationSource("Money Manager", listOf("Money Manager"), "Money Manager · ")
            val bank = Account(id = AccountId(1), name = "Checking", openingDate = epoch)
            val wallet = ShadowAccount(AccountId(100), "Money Manager · Checking", source.name)
            val link = ReconciliationLink(wallet.accountId, bank.id)
            val repo =
                reconciliationRepository(listOf(source), listOf(wallet), flowOf(listOf(link))) { ids ->
                    listOf(leg(10, wallet.accountId, "5", t0), leg(20, bank.id, "8", t0)).filter { it.accountId in ids }
                }
            setContent {
                provideScreenScopes {
                    ReconciliationScreen(
                        reconciliationRepository = repo,
                        accountRepository = accountRepository(listOf(bank)),
                        categoryRepository = categoryRepository,
                        personRepository = personRepository,
                        transactionRepository = transactionRepository,
                        onOpenLeg = {},
                    )
                }
            }

            waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) { reveal(hasText("Only in imported Money Manager (1)")) }
            assertTrue(reveal(hasText("Only in Money Manager (1)")))
        }
    }

    @Test
    fun tabsShowOnlyWalletsWithOrWithoutASuggestion() {
        runMoneyManagerComposeUiTest {
            val suggested = ShadowAccount(AccountId(100), "Koinly · Santander Savings", "Koinly")
            val unmapped = ShadowAccount(AccountId(101), "Koinly · Hardware Vault", "Koinly")
            val bank = Account(id = AccountId(1), name = "Santander", openingDate = epoch)
            setContent {
                provideScreenScopes {
                    ReconciliationScreen(
                        reconciliationRepository = reconciliationRepository(listOf(koinly), listOf(suggested, unmapped)),
                        accountRepository = accountRepository(listOf(bank)),
                        categoryRepository = categoryRepository,
                        personRepository = personRepository,
                        transactionRepository = transactionRepository,
                        onOpenLeg = {},
                    )
                }
            }

            waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) { reveal(hasText("Santander Savings")) }
            onNodeWithText("Link all (1)").assertIsDisplayed()
            onNodeWithText("Hardware Vault").assertDoesNotExist()

            clickText("Without suggestion (1)")
            onNodeWithText("Hardware Vault").assertIsDisplayed()
            onNodeWithText("Santander Savings").assertDoesNotExist()
            onNodeWithText("Link all (1)").assertDoesNotExist()
            onNodeWithText("Create all (1)").assertIsDisplayed()

            clickText("With suggestion (1)")
            onNodeWithText("Santander Savings").assertIsDisplayed()
            onNodeWithText("Hardware Vault").assertDoesNotExist()
        }
    }

    @Test
    fun searchesUnlinkedWalletNamesAndLinkedAccountNames() {
        runMoneyManagerComposeUiTest {
            val cash = ShadowAccount(AccountId(100), "Koinly · Cash wallet", "Koinly")
            val spare = ShadowAccount(AccountId(101), "Koinly · Spare wallet", "Koinly")
            val exchange = ShadowAccount(AccountId(102), "Koinly · Exchange", "Koinly")
            val savings = Account(id = AccountId(1), name = "Savings account", openingDate = epoch)
            setContent {
                provideScreenScopes {
                    ReconciliationScreen(
                        reconciliationRepository =
                            reconciliationRepository(
                                listOf(koinly),
                                listOf(cash, spare, exchange),
                                flowOf(listOf(ReconciliationLink(exchange.accountId, savings.id))),
                            ),
                        accountRepository = accountRepository(listOf(savings)),
                        categoryRepository = categoryRepository,
                        personRepository = personRepository,
                        transactionRepository = transactionRepository,
                        onOpenLeg = {},
                    )
                }
            }

            val search = onNode(hasSetTextAction() and hasText("Search accounts"))
            search.performTextReplacement(" CASH ")
            onNodeWithText("Cash wallet").assertIsDisplayed()
            onNodeWithText("Spare wallet").assertDoesNotExist()
            onNodeWithText("Create all (1)").assertIsDisplayed()

            search.performTextReplacement("savings")
            onNodeWithText("Savings account", substring = true).assertIsDisplayed()
            onNodeWithText("Cash wallet").assertDoesNotExist()

            search.performTextReplacement("unknown")
            onNodeWithText("No accounts match your search").assertIsDisplayed()

            search.performTextReplacement("")
            onNodeWithText("Needs attention: 2 Koinly wallet(s) not linked to an account").assertIsDisplayed()
        }
    }

    @Test
    fun reusesWalletPlansAfterReopeningTheScreen() {
        runMoneyManagerComposeUiTest {
            val wallet = ShadowAccount(AccountId(100), "Koinly · Santander Savings", "Koinly")
            val bank = Account(id = AccountId(2), name = "Santander", openingDate = epoch)
            val inputs = WalletLinkInputs(koinly, listOf(wallet), emptyList(), listOf(bank), listOf(bank))
            val cache = ReconciliationCache()
            val visible = mutableStateOf(true)
            val reconciliation = reconciliationRepository(listOf(koinly), listOf(wallet))
            val accounts = accountRepository(listOf(bank))
            setContent {
                if (visible.value) {
                    provideScreenScopes {
                        ReconciliationScreen(
                            reconciliationRepository = reconciliation,
                            accountRepository = accounts,
                            categoryRepository = categoryRepository,
                            personRepository = personRepository,
                            transactionRepository = transactionRepository,
                            onOpenLeg = {},
                            cache = cache,
                        )
                    }
                }
            }

            waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) { reveal(hasText("Link all (1)")) }
            val initial = checkNotNull(cache.getWalletPlans(inputs))
            runOnIdle { visible.value = false }
            waitForIdle()
            runOnIdle { visible.value = true }
            waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) { reveal(hasText("Link all (1)")) }
            assertSame(initial, cache.getWalletPlans(inputs))
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
                CompositionLocalProvider(
                    LocalImportEngine provides engine,
                    LocalTaskManager provides TaskManager(CoroutineScope(Dispatchers.Default)),
                ) {
                    provideScreenScopes {
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
            clickText("Without suggestion (1)")
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
                provideScreenScopes {
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
                    provideScreenScopes {
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
                    provideScreenScopes {
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
    private val eth = CryptoAsset(id = CryptoId(2), code = "ETH", name = "Ether")
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
        opened: MutableList<ReconciliationLeg> = mutableListOf(),
        openedSources: MutableList<ReconciliationLeg> = mutableListOf(),
        focus: ReconciliationFocusState = ReconciliationFocusState(),
    ): List<ImportBatch> {
        val batches = mutableListOf<ImportBatch>()
        val engine =
            importEngine { batch ->
                batches += batch
                ImportResult()
            }
        showLegsWith(engine, sourceLegs, realLegs, opened, openedSources, focus)
        return batches
    }

    private fun ComposeUiTest.showLegsWith(
        engine: ImportEngine,
        sourceLegs: List<ReconciliationLeg>,
        realLegs: List<ReconciliationLeg>,
        opened: MutableList<ReconciliationLeg> = mutableListOf(),
        openedSources: MutableList<ReconciliationLeg> = mutableListOf(),
        focus: ReconciliationFocusState = ReconciliationFocusState(),
        onLoadLegs: () -> Unit = {},
    ) {
        val accounts =
            listOf(
                Account(id = binance, name = "Binance", openingDate = epoch),
                Account(id = wallet.accountId, name = wallet.name, openingDate = epoch),
            )
        setContent {
            CompositionLocalProvider(LocalImportEngine provides engine) {
                provideScreenScopes {
                    ReconciliationScreen(
                        reconciliationRepository =
                            reconciliationRepository(
                                listOf(koinly),
                                listOf(wallet),
                                flowOf(listOf(ReconciliationLink(wallet.accountId, binance))),
                            ) { ids ->
                                onLoadLegs()
                                if (wallet.accountId in ids) sourceLegs else realLegs
                            },
                        accountRepository = accountRepository(accounts),
                        categoryRepository = categoryRepository,
                        personRepository = personRepository,
                        transactionRepository = transactionRepository,
                        onOpenLeg = { opened += it },
                        onOpenSource = { openedSources += it },
                        focus = focus,
                    )
                }
            }
        }
        waitForIdle()
    }

    /** Opens every collapsed wallet and month group of the visible tab. */
    private fun ComposeUiTest.expandAll() {
        waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) { reveal(hasText("Unmatched")) }
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
        // The screen reconciles off the test clock, so idling doesn't wait for what it then shows.
        waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) {
            reveal(hasText(text)) || onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
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
                        pinObservations = true,
                    ),
                ),
                batches.single().relationshipMutations,
            )
        }
    }

    @Test
    fun aFocusRequestOpensTheWalletAndMonthItNamesAndIsConsumed() {
        runMoneyManagerComposeUiTest {
            val focus = ReconciliationFocusState()
            focus.request = ReconciliationFocus(koinly.name, wallet.accountId, reconciliationMonth(t0), 10)

            // A real leg of the same asset, far away, so the source's movement is not hidden as an asset Money Manager never saw.
            showLegs(listOf(leg(10, wallet.accountId, "5", t0)), listOf(leg(20, binance, "9", t0 + 100.days)), focus = focus)

            // Nothing was expanded by hand, yet the movement is in view.
            reveal(hasText("leg 10", substring = true))
            onNodeWithText("leg 10", substring = true).assertIsDisplayed()
            assertEquals(null, focus.request)
        }
    }

    @Test
    fun aFocusRequestOnASettledMovementOpensTheMatchedTab() {
        runMoneyManagerComposeUiTest {
            val focus = ReconciliationFocusState()
            focus.request = ReconciliationFocus(koinly.name, wallet.accountId, reconciliationMonth(t0), 10)

            showLegs(listOf(leg(10, wallet.accountId, "5", t0)), listOf(leg(20, binance, "5", t0)), focus = focus)

            onNodeWithText("leg 10", substring = true).assertIsDisplayed()
            onNodeWithTag(HIGHLIGHTED_TAG).assertIsDisplayed()
            assertEquals(null, focus.request)
        }
    }

    @Test
    fun aFocusRequestScrollsToTheMovementFarDownTheList() {
        runMoneyManagerComposeUiTest {
            val focus = ReconciliationFocusState()
            val sourceLegs = (10L..59L).map { leg(it, wallet.accountId, it.toString(), t0 + it.hours) }
            focus.request = ReconciliationFocus(koinly.name, wallet.accountId, reconciliationMonth(t0), 35)

            showLegs(sourceLegs, listOf(leg(900, binance, "9999", t0 + 100.days)), focus = focus)
            // The comparison runs off the UI thread, which waitForIdle doesn't wait for: wait for the scroll to land.
            waitUntil(timeoutMillis = 10_000) {
                focus.request == null && onAllNodesWithText("leg 35", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            waitForIdle()

            // Fifty rows are open, and the one asked for is marked and in the middle of the screen.
            onNodeWithText("leg 35", substring = true).assertIsDisplayed()
            val row = onNodeWithTag(HIGHLIGHTED_TAG).fetchSemanticsNode().boundsInRoot
            val screen = onRoot().fetchSemanticsNode().boundsInRoot
            val offCentre = abs((row.top + row.bottom) / 2 - (screen.top + screen.bottom) / 2)
            assertTrue(offCentre < screen.height / 4, "the row is $offCentre px from the middle of a ${screen.height} px screen")
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
            // A bulk run is a background task, so wait for its writes.
            waitUntil(timeoutMillis = 10_000) { batches.isNotEmpty() }

            val create = batches.single().relationshipMutations.filterIsInstance<RelationshipMutation.Create>()
            assertEquals(listOf(listOf(match(10), match(20)), listOf(match(11), match(21))), create.map { it.members })
        }
    }

    @Test
    fun acceptAllOnTheNearSectionStoresOnlyTheNearMatchesItLists() {
        runMoneyManagerComposeUiTest {
            // 10↔20 agree to the second (exact, listed under Matched), 11↔21 are an hour apart (near).
            val batches =
                showLegs(
                    listOf(leg(10, wallet.accountId, "5", t0), leg(11, wallet.accountId, "6", t0 + 1.hours)),
                    listOf(leg(20, binance, "5", t0), leg(21, binance, "6", t0 + 2.hours)),
                )
            reveal(hasText("Near matches (1)"))

            onAllNodesWithText("Accept all (1)")[0].performClick()
            // A bulk run is a background task, so wait for its writes.
            waitUntil(timeoutMillis = 10_000) { batches.isNotEmpty() }

            val create = batches.single().relationshipMutations.filterIsInstance<RelationshipMutation.Create>()
            assertEquals(setOf(listOf(match(11), match(21))), create.map { it.members }.toSet())
        }
    }

    @Test
    fun acceptAllReloadsLegsOnlyAfterTheLastChunk() {
        runMoneyManagerComposeUiTest {
            val secondChunkStarted = CompletableDeferred<Unit>()
            val finishSecondChunk = CompletableDeferred<Unit>()
            var chunks = 0
            val engine =
                importEngine {
                    if (++chunks == 2) {
                        secondChunkStarted.complete(Unit)
                        finishSecondChunk.await()
                    }
                    ImportResult()
                }
            val loads = MutableStateFlow(0)
            showLegsWith(
                engine,
                List(51) { leg(100L + it, wallet.accountId, "${it + 1}", t0) },
                List(51) { leg(200L + it, binance, "${it + 1}", t0 + 1.hours) },
                onLoadLegs = { loads.value++ },
            )
            waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) { reveal(hasText("Near matches (51)")) }
            val initialLoads = loads.value

            try {
                onAllNodesWithText("Accept all (51)")[0].performClick()
                waitUntil(timeoutMillis = 10_000) { secondChunkStarted.isCompleted }
                waitForIdle()
                assertEquals(initialLoads, loads.value)
                onNodeWithText("Accepting matches").assertIsDisplayed()
                onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(50f / 51, 0f..1f))).assertIsDisplayed()
            } finally {
                finishSecondChunk.complete(Unit)
            }

            waitUntil(timeoutMillis = 10_000) { loads.value >= initialLoads + 2 }
            waitForIdle()
            assertEquals(initialLoads + 2, loads.value)
            onNodeWithText("Accepting matches").assertDoesNotExist()
        }
    }

    @Test
    fun theMatchedTabOffersAcceptAllForAutoMatchedRows() {
        runMoneyManagerComposeUiTest {
            val batches = showLegs(listOf(leg(10, wallet.accountId, "5", t0)), listOf(leg(20, binance, "5", t0)))
            clickText("Matched")

            onAllNodesWithText("Accept all (1)")[0].performClick()
            // A bulk run is a background task, so wait for its writes.
            waitUntil(timeoutMillis = 10_000) { batches.isNotEmpty() }

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
    fun theNearMatchesAcceptAllOffersOnlyTheNearMatchesItLists() {
        runMoneyManagerComposeUiTest {
            // An exact pair is settled: it is listed under Matched, not here, so this section offers nothing.
            showLegs(listOf(leg(10, wallet.accountId, "5", t0)), listOf(leg(20, binance, "5", t0)))

            onNodeWithText("Near matches (0)").assertExists()
            onAllNodesWithText("Accept all").assertCountEquals(0)
        }
    }

    @Test
    fun theBulkButtonsAreDisabledWhileABulkRunIsInProgress() {
        runMoneyManagerComposeUiTest {
            val gate = CompletableDeferred<Unit>()
            val batches = mutableListOf<ImportBatch>()
            val engine =
                importEngine { batch ->
                    gate.await()
                    batches += batch
                    ImportResult()
                }
            showLegsWith(engine, listOf(leg(10, wallet.accountId, "5", t0)), listOf(leg(20, binance, "5", t0 + 1.hours)))
            reveal(hasText("Near matches (1)"))

            onAllNodesWithText("Accept all (1)")[0].performClick()
            waitForIdle()

            onAllNodesWithText("Accept all (1)")[0].assertIsNotEnabled()
            gate.complete(Unit)
            waitUntil(timeoutMillis = 10_000) { batches.isNotEmpty() }
        }
    }

    @Test
    fun showsUnlinkedEntriesAndLinksOnlyUnambiguousSuggestions() {
        runMoneyManagerComposeUiTest {
            val bank = ShadowAccount(AccountId(101), "Koinly · Santander Savings", "Koinly")
            val first = ShadowAccount(AccountId(102), "Koinly · Coinbase One", "Koinly")
            val second = ShadowAccount(AccountId(103), "Koinly · Coinbase Two", "Koinly")
            val linked = MutableStateFlow(listOf(ReconciliationLink(wallet.accountId, binance)))
            val proposed = ReconciliationLinkMutation.SetLinks(bank.accountId, setOf(AccountId(2)))
            val batches = mutableListOf<ImportBatch>()
            val gate = CompletableDeferred<Unit>()
            val engine =
                importEngine { batch ->
                    batches += batch
                    gate.await()
                    linked.value += ReconciliationLink(bank.accountId, AccountId(2))
                    ImportResult()
                }
            val repo =
                reconciliationRepository(listOf(koinly), listOf(wallet, bank, first, second), linked) { ids ->
                    listOf(leg(10, wallet.accountId, "5", t0), leg(11, bank.accountId, "6", t0), leg(20, binance, "8", t0))
                        .filter { it.accountId in ids }
                }
            val accounts =
                listOf(
                    Account(id = binance, name = "Binance", openingDate = epoch),
                    Account(id = AccountId(2), name = "Santander", openingDate = epoch),
                    Account(id = AccountId(3), name = "Coinbase", openingDate = epoch),
                )
            setContent {
                provideScreenScopes {
                    CompositionLocalProvider(LocalImportEngine provides engine) {
                        ReconciliationScreen(
                            reconciliationRepository = repo,
                            accountRepository = accountRepository(accounts),
                            categoryRepository = categoryRepository,
                            personRepository = personRepository,
                            transactionRepository = transactionRepository,
                            onOpenLeg = {},
                        )
                    }
                }
            }

            waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) { reveal(hasText("Unlinked accounts (1)")) }
            assertTrue(reveal(hasText("Linked accounts")))
            assertTrue(reveal(hasText("Only in Koinly (1)")))
            clickText("Unlinked accounts (1)")
            assertTrue(reveal(hasText("▸ Santander Savings (1)")))
            assertTrue(!reveal(hasText("Only in Koinly (1)")))
            clickText("Link all (1)")
            onNodeWithText("Linking suggested accounts").assertIsDisplayed()
            waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) { batches.isNotEmpty() }
            assertEquals(listOf(proposed), batches.single().reconciliationLinkMutations)
            gate.complete(Unit)
            waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) { reveal(hasText("Unlinked accounts (0)")) }
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
            // A bulk run is a background task, so wait for its writes.
            waitUntil(timeoutMillis = 10_000) { batches.size >= 2 }

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
    fun thePickerBooksTheMissingFeeAndMatchesItWithTheSelection() {
        runMoneyManagerComposeUiTest {
            val batches = mutableListOf<ImportBatch>()
            val engine =
                importEngine { batch ->
                    batches += batch
                    ImportResult(createdTransferIds = batch.transfers.mapNotNull { it.rowKey }.associateWith { TransferId(99) })
                }
            showLegsWith(
                engine,
                listOf(leg(10, wallet.accountId, "-5", t0)),
                listOf(leg(21, binance, "-4.7", t0 + 1.hours)),
            )
            expandAll()

            clickText("Select matching transactions…")
            val picker = onNodeWithTag(PICKER_CANDIDATES_TAG)
            onNodeWithText("Add missing fee", substring = true).assertDoesNotExist()
            picker.performScrollToNode(isToggleable() and hasText("leg 21", substring = true))
            onNode(isToggleable() and hasText("leg 21", substring = true)).performClick()
            waitForIdle()
            onNodeWithText("Add missing fee", substring = true).performClick()
            waitForIdle()

            val fee = batches.first().transfers.single()
            assertEquals(Money.fromDisplayValue(BigDecimal("0.3"), ada), fee.amount)
            assertEquals(AccountRef.Existing(binance), fee.fromAccount)
            assertEquals(
                listOf(TransferId(10), TransferId(21), TransferId(99)),
                batches
                    .last()
                    .relationshipMutations
                    .filterIsInstance<RelationshipMutation.Create>()
                    .single()
                    .members
                    .map { it.transactionId },
            )
        }
    }

    @Test
    fun thePickerCopiesASourceOnlyTradeToTheLinkedAccountAndMatchesIt() {
        runMoneyManagerComposeUiTest {
            val batches = mutableListOf<ImportBatch>()
            val engine =
                importEngine { batch ->
                    batches += batch
                    ImportResult(copiedTransactionIds = batch.reconciliationCopies.map { TradeId(77) })
                }
            showLegsWith(
                engine,
                listOf(
                    leg(30, wallet.accountId, "-5", t0, trade = true),
                    leg(30, wallet.accountId, "2", t0, trade = true).copy(amount = Money.fromDisplayValue(BigDecimal("2"), eth)),
                ),
                listOf(leg(40, binance, "1", t0 + 1.hours)),
            )
            expandAll()

            reveal(hasText("Select matching transactions…"))
            onAllNodesWithText("Select matching transactions…")[0].performClick()
            waitForIdle()
            onNodeWithTag(PICKER_COPY_TAG).performClick()
            waitForIdle()

            // The engine books the copy from the source's own trade, on the account its wallet is linked to (#1475).
            assertEquals(listOf<TransactionId>(TradeId(30)), batches.single { it.reconciliationCopies.isNotEmpty() }.reconciliationCopies)
            assertEquals(
                listOf(TradeId(30), TradeId(77)),
                batches
                    .last()
                    .relationshipMutations
                    .filterIsInstance<RelationshipMutation.Create>()
                    .single()
                    .members
                    .map { it.transactionId },
            )
        }
    }

    @Test
    fun thePickerSaysWhyAMovementCannotBeCopied() {
        runMoneyManagerComposeUiTest {
            val batches = showLegs(listOf(leg(10, wallet.accountId, "-5", t0)), listOf(leg(40, binance, "1", t0 + 1.hours)))
            expandAll()

            clickText("Select matching transactions…")

            onNodeWithTag(PICKER_COPY_TAG).assertIsNotEnabled()
            onNodeWithText("Can't copy: account 999 is linked to no Money Manager account").assertExists()
            assertEquals(emptyList(), batches)
        }
    }

    @Test
    fun thePickerLinksTheSourceMovementAndAMatchedCandidate() {
        runMoneyManagerComposeUiTest {
            val opened = mutableListOf<ReconciliationLeg>()
            val openedSources = mutableListOf<ReconciliationLeg>()
            showLegs(
                listOf(leg(10, wallet.accountId, "5", t0), leg(11, wallet.accountId, "6.9", t0 + 1.hours)),
                listOf(leg(21, binance, "6.9", t0 + 1.hours)),
                opened,
                openedSources,
            )
            expandAll()

            clickText("Select matching transactions…")
            val picker = onNodeWithTag(PICKER_CANDIDATES_TAG)
            val asset = leg(21, binance, "6.9", t0).amount.asset.id
            val movementKey = "10-${wallet.accountId.id}-$asset"
            val candidateKey = "21-${binance.id}-$asset"
            onNodeWithTag("$PICKER_OPEN_SOURCE_TAG-$movementKey").performClick()
            onNodeWithTag("$PICKER_OPEN_TRANSACTION_TAG-$movementKey").performClick()
            picker.performScrollToNode(hasTestTag("$PICKER_OPEN_SOURCE_TAG-$candidateKey"))
            onNodeWithTag("$PICKER_OPEN_SOURCE_TAG-$candidateKey").performClick()
            onNodeWithTag("$PICKER_OPEN_TRANSACTION_TAG-$candidateKey").performClick()
            waitForIdle()

            assertEquals(listOf(10L, 21L), openedSources.map { it.transactionId })
            assertEquals(listOf(10L, 21L), opened.map { it.transactionId })
            // The picker stays open in its state, so back from the followed link finds it as it was left.
            onNodeWithText("Select matching transactions").assertExists()
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

    @Test
    fun matchedAcceptAllStoresOnlyTheNotAcceptedMatchesOnScreen() {
        runMoneyManagerComposeUiTest {
            // 10↔20 agree to the second and are not stored yet; 30↔40 are already accepted (stored as match 42).
            val batches =
                showLegs(
                    listOf(leg(10, wallet.accountId, "5", t0), leg(30, wallet.accountId, "7", t0 + 5.days, matchIds = setOf(42))),
                    listOf(leg(20, binance, "5", t0), leg(40, binance, "7", t0 + 5.days, matchIds = setOf(42))),
                )
            clickText("Matched")

            onNodeWithText("Accepted (1)").assertExists()
            onNodeWithText("Not accepted (1)").assertExists()
            onAllNodesWithText("Accept all (1)")[0].performClick()
            waitUntil(timeoutMillis = 10_000) { batches.isNotEmpty() }

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
    fun linksAWalletToAnAccountAnotherWalletAlreadyMirrors() {
        runMoneyManagerComposeUiTest {
            val coinbase = ShadowAccount(AccountId(100), "Koinly · Coinbase", "Koinly")
            val pro = ShadowAccount(AccountId(101), "Koinly · Coinbase Pro", "Koinly")
            val accounts =
                listOf(Account(id = AccountId(1), name = "Coinbase", openingDate = epoch)) +
                    listOf(coinbase, pro).map { Account(id = it.accountId, name = it.name, openingDate = epoch) }
            val batches = mutableListOf<ImportBatch>()
            setContent {
                provideScreenScopes {
                    CompositionLocalProvider(
                        LocalImportEngine provides
                            importEngine { batch ->
                                ImportResult().also { batches += batch }
                            },
                    ) {
                        ReconciliationScreen(
                            reconciliationRepository =
                                reconciliationRepository(
                                    listOf(koinly),
                                    listOf(coinbase, pro),
                                    links = flowOf(listOf(ReconciliationLink(coinbase.accountId, AccountId(1)))),
                                ),
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
            onNodeWithText("Select...").performClick()
            waitForIdle()
            waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) {
                onAllNodesWithText("Coinbase", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Coinbase", useUnmergedTree = true).performClick()
            waitForIdle()

            waitUntil(timeoutMillis = 10_000) { batches.any { it.reconciliationLinkMutations.isNotEmpty() } }
            assertEquals(
                listOf(ReconciliationLinkMutation.SetLinks(pro.accountId, setOf(AccountId(1)))),
                batches.flatMap { it.reconciliationLinkMutations },
            )
        }
    }

    @Test
    fun showsARefusedLinkOnTheWalletItWasFor() {
        runMoneyManagerComposeUiTest {
            val wallet = ShadowAccount(AccountId(100), "Koinly · Ledger", "Koinly")
            val accounts =
                listOf(
                    Account(id = AccountId(1), name = "Cold storage", openingDate = epoch),
                    Account(id = wallet.accountId, name = wallet.name, openingDate = epoch),
                )
            val engine =
                importEngine { batch ->
                    require(batch.reconciliationLinkMutations.isEmpty()) { "refused" }
                    ImportResult()
                }
            setContent {
                provideScreenScopes {
                    CompositionLocalProvider(LocalImportEngine provides engine) {
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
            }

            waitForIdle()
            onNodeWithText("Select...").performClick()
            waitForIdle()
            waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) {
                onAllNodesWithText("Cold storage", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Cold storage", useUnmergedTree = true).performClick()

            waitUntil(
                timeoutMillis = 10_000,
            ) { onAllNodes(hasTestTag("link-error"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Linking failed: refused", useUnmergedTree = true).assertIsDisplayed()
        }
    }

    private companion object {
        const val MAX_GROUPS = 20
        const val LOAD_TIMEOUT_MS = 10_000L
    }
}
