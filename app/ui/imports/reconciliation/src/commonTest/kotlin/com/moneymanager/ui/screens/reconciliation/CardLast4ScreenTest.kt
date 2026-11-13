@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.screens.reconciliation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import com.moneymanager.csvimporter.BulkReimportResult
import com.moneymanager.domain.model.AccountAttribute
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.CsvImportId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.csv.CsvColumn
import com.moneymanager.domain.model.csv.CsvColumnId
import com.moneymanager.domain.model.csv.CsvImport
import com.moneymanager.domain.model.csv.CsvRow
import com.moneymanager.domain.model.csvstrategy.AttributeAccountMatch
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.repository.AccountAttributeReadRepository
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.CsvImportReadRepository
import com.moneymanager.domain.repository.CsvImportStrategyReadRepository
import com.moneymanager.domain.repository.ImportDirectoryReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
import com.moneymanager.presenter.error.ProvideSchemaAwareScope
import com.moneymanager.tasks.TaskManager
import com.moneymanager.ui.tasks.LocalTaskManager
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

class CardLast4ScreenTest {
    private val epoch = Instant.fromEpochMilliseconds(0)

    private val csvImport =
        CsvImport(
            id = CsvImportId(Uuid.random()),
            tableName = "t",
            originalFileName = "Transaction History.csv",
            importTimestamp = epoch,
            rowCount = 3,
            columnCount = 1,
            columns = listOf(CsvColumn(CsvColumnId(Uuid.random()), 0, "Card")),
            deviceInfo = DeviceInfo.Jvm("os", "machine"),
            fileChecksum = "checksum",
            fileLastModified = epoch,
            lastAppliedStrategyId = CsvImportStrategyId(Uuid.random()),
        )

    private fun strategy(fundingMatch: AttributeAccountMatch?) =
        CsvImportStrategy(
            id = csvImport.lastAppliedStrategyId!!,
            name = "Card Aggregator",
            config =
                CsvStrategyConfig(
                    identificationColumns = setOf("Card"),
                    fieldMappings = emptyMap(),
                    fundingAttributeMatch = fundingMatch,
                ),
            createdAt = epoch,
            updatedAt = epoch,
        )

    private val csvImportRepository: CsvImportReadRepository =
        mock {
            every { getAllImports() } returns flowOf(listOf(csvImport))
            every { getImport(any()) } returns flowOf(csvImport)
            everySuspend { getImportRows(any(), any(), any()) } returns
                listOf(
                    CsvRow(rowIndex = 1, values = listOf("4647")),
                    CsvRow(rowIndex = 2, values = listOf(" 4647 ")),
                    CsvRow(rowIndex = 3, values = listOf("")),
                )
        }

    private fun attributeRepository(attributes: List<AccountAttribute>): AccountAttributeReadRepository =
        mock { every { getAll() } returns flowOf(attributes) }

    private val accountRepository: AccountReadRepository =
        mock {
            every { getAllAccounts() } returns flowOf(emptyList())
            everySuspend { getAccountIdsByAttribute(any(), any()) } returns emptySet()
        }

    private val categoryRepository: CategoryReadRepository =
        mock { every { getAllCategories() } returns flowOf(emptyList()) }

    private val personRepository: PersonReadRepository =
        mock { every { getAllPeople() } returns flowOf(emptyList()) }

    private val importDirectoryRepository: ImportDirectoryReadRepository =
        mock {
            every { csvImportDirectories() } returns flowOf(emptyMap())
            every { getAllDirectories() } returns flowOf(emptyList())
        }

    private fun runScreen(
        fundingMatch: AttributeAccountMatch?,
        attributes: List<AccountAttribute> = emptyList(),
        onOpenFile: (CsvImportId, Map<String, Set<String>>) -> Unit = { _, _ -> },
        assertions: ComposeUiTest.() -> Unit,
    ) {
        val strategyRepository: CsvImportStrategyReadRepository =
            mock { every { getAllStrategies() } returns flowOf(listOf(strategy(fundingMatch))) }
        runMoneyManagerComposeUiTest {
            setContent {
                val tasks = remember { TaskManager(CoroutineScope(Dispatchers.Default)) }
                CompositionLocalProvider(LocalTaskManager provides tasks) {
                    ProvideSchemaAwareScope {
                        CardLast4Screen(
                            csvImportRepository = csvImportRepository,
                            importDirectoryRepository = importDirectoryRepository,
                            csvImportStrategyRepository = strategyRepository,
                            accountAttributeRepository = attributeRepository(attributes),
                            accountRepository = accountRepository,
                            categoryRepository = categoryRepository,
                            personRepository = personRepository,
                            prepareReimport = { _, _ -> { BulkReimportResult(0, 0, 0, 0, 0) } },
                            onOpenFile = onOpenFile,
                        )
                    }
                }
            }
            assertions()
        }
    }

    @Test
    fun listsEachUnassignedCardOnceWithItsTransactionCount() =
        runScreen(fundingMatch = AttributeAccountMatch("Card", "card-last4")) {
            waitUntilAtLeastOneExists(hasText("4647"))
            onNodeWithText("2 transactions in 1 file", substring = true).assertIsDisplayed()
            onNodeWithText("Card Aggregator", substring = true).assertIsDisplayed()
            onNodeWithText("Card belongs to").assertIsDisplayed()
            // Nothing is picked yet, so there is nothing to apply.
            onNodeWithText("Apply").assertIsNotEnabled()
        }

    @Test
    fun goToOpensTheFileFilteredToTheCardsRows() {
        val opened = mutableListOf<Pair<CsvImportId, Map<String, Set<String>>>>()
        runScreen(
            fundingMatch = AttributeAccountMatch("Card", "card-last4"),
            onOpenFile = { id, filter -> opened += id to filter },
        ) {
            waitUntilAtLeastOneExists(hasText("4647"))
            onNodeWithText("Transaction History.csv (2 transactions)").assertIsDisplayed()
            onNodeWithText("Go to").performClick()
            waitForIdle()
        }
        // The file's own cells, untrimmed, so its column filter keeps both rows.
        assertEquals(listOf(csvImport.id to mapOf("Card" to setOf("4647", " 4647 "))), opened)
    }

    @Test
    fun cardOwnedByAnAccountIsListedAsAssigned() =
        runScreen(
            fundingMatch = AttributeAccountMatch("Card", "card-last4"),
            attributes =
                listOf(
                    AccountAttribute(
                        id = 1,
                        accountId = AccountId(7),
                        attributeType = AttributeType(AttributeTypeId(-8), "card-last4"),
                        value = "1111 4647",
                    ),
                ),
        ) {
            waitUntilAtLeastOneExists(hasText("Every referenced card is assigned to an account."))
            // Folded until opened.
            onNodeWithText("4647").assertDoesNotExist()
            onNodeWithText("Assigned (1)").performClick()
            waitUntilAtLeastOneExists(hasText("4647"))
            onNodeWithText("4647").assertIsDisplayed()
            onNodeWithText("A pattern on this account", substring = true).assertDoesNotExist()
        }

    @Test
    fun cardMatchedOnlyByAPatternPointsToTheAccountEditor() =
        runScreen(
            fundingMatch = AttributeAccountMatch("Card", "card-last4"),
            attributes =
                listOf(
                    AccountAttribute(
                        id = 1,
                        accountId = AccountId(7),
                        attributeType = AttributeType(AttributeTypeId(-8), "card-last4"),
                        value = "46.*",
                    ),
                ),
        ) {
            waitUntilAtLeastOneExists(hasText("Assigned (1)"))
            onNodeWithText("Assigned (1)").performClick()
            waitUntilAtLeastOneExists(hasText("A pattern on this account", substring = true))
            onNodeWithText("A pattern on this account", substring = true).assertIsDisplayed()
        }

    @Test
    fun explainsWhenNoStrategyReferencesCards() =
        runScreen(fundingMatch = null) {
            waitUntilAtLeastOneExists(hasText("No import strategy references funding cards."))
        }
}
