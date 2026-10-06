@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.screens.reconciliation

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.waitUntilAtLeastOneExists
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
import com.moneymanager.domain.repository.PersonReadRepository
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

    private fun runScreen(
        fundingMatch: AttributeAccountMatch?,
        attributes: List<AccountAttribute> = emptyList(),
        assertions: ComposeUiTest.() -> Unit,
    ) {
        val strategyRepository: CsvImportStrategyReadRepository =
            mock { every { getAllStrategies() } returns flowOf(listOf(strategy(fundingMatch))) }
        runMoneyManagerComposeUiTest {
            setContent {
                ProvideSchemaAwareScope {
                    CardLast4Screen(
                        csvImportRepository = csvImportRepository,
                        csvImportStrategyRepository = strategyRepository,
                        accountAttributeRepository = attributeRepository(attributes),
                        accountRepository = accountRepository,
                        categoryRepository = categoryRepository,
                        personRepository = personRepository,
                        rerunFundingReconciles = { 0 },
                    )
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
        }

    @Test
    fun cardOwnedByAnAccountIsNotListed() =
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
        }

    @Test
    fun explainsWhenNoStrategyReferencesCards() =
        runScreen(fundingMatch = null) {
            waitUntilAtLeastOneExists(hasText("No import strategy references funding cards."))
        }
}
