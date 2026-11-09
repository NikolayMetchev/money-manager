@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.screens.csvstrategy.editor

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.CsvImportStrategyId
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CsvStrategyConfig
import com.moneymanager.domain.model.csvstrategy.FieldMapping
import com.moneymanager.domain.model.csvstrategy.HardCodedAccountMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.repository.AccountReadRepository
import com.moneymanager.domain.repository.CategoryReadRepository
import com.moneymanager.domain.repository.PersonReadRepository
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

class AccountsTabTest {
    private val now = Instant.fromEpochMilliseconds(1_000)

    private fun strategy(source: FieldMapping?) =
        CsvImportStrategy(
            id = CsvImportStrategyId(Uuid.random()),
            name = "Bank",
            config =
                CsvStrategyConfig(
                    identificationColumns = setOf("Date"),
                    fieldMappings = listOfNotNull(source).associateBy { it.fieldType },
                ),
            createdAt = now,
            updatedAt = now,
        )

    private fun ComposeUiTest.show(state: CsvStrategyEditorState) {
        val accounts: AccountReadRepository =
            mock {
                every { getAllAccounts() } returns flowOf(emptyList())
                everySuspend { getAccountIdsByAttribute(any(), any()) } returns emptySet()
            }
        val categories: CategoryReadRepository = mock { every { getAllCategories() } returns flowOf(emptyList()) }
        val people: PersonReadRepository = mock { every { getAllPeople() } returns flowOf(emptyList()) }
        setContent {
            MaterialTheme {
                AccountsTab(state, enabled = true, accountRepository = accounts, categoryRepository = categories, personRepository = people)
            }
        }
    }

    @Test
    fun `a strategy naming its source account says it can not be left for each import`() =
        runMoneyManagerComposeUiTest {
            val state = CsvStrategyEditorState(strategy(HardCodedAccountMapping(TransferField.SOURCE_ACCOUNT, AccountId(7))), setOf("Date"))
            state.selectedAccountId = null
            show(state)

            onNodeWithText("Source Account").assertIsDisplayed()
            onNodeWithText("can't be left for each import to choose", substring = true).assertIsDisplayed()
        }

    @Test
    fun `a strategy without one leaves it to each import`() =
        runMoneyManagerComposeUiTest {
            show(CsvStrategyEditorState(strategy(null), setOf("Date")))

            onNodeWithText("Source Account (Optional)").assertIsDisplayed()
            onNodeWithText("Can also be chosen each time you apply this strategy").assertIsDisplayed()
        }
}
