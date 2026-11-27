@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.moneymanager.ui.screens.accounts

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountAttribute
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.repository.write.AccountAttributeWriteRepository
import com.moneymanager.domain.repository.write.AccountWriteRepository
import com.moneymanager.domain.repository.write.AttributeTypeWriteRepository
import com.moneymanager.domain.repository.write.CategoryWriteRepository
import com.moneymanager.domain.repository.write.PersonAccountOwnershipWriteRepository
import com.moneymanager.domain.repository.write.PersonWriteRepository
import com.moneymanager.domain.repository.write.TransactionWriteRepository
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.time.Clock

class ReconciliationAccountCardTest {
    @Test
    fun reconciliationAccountsShowAnIndicatorWhenIncluded() {
        runMoneyManagerComposeUiTest {
            val account = Account(id = AccountId(1), name = "Shadow wallet", openingDate = Clock.System.now())
            val attribute =
                AccountAttribute(
                    1L,
                    account.id,
                    AttributeType(AttributeTypeId(WellKnownIds.ACCOUNT_RECONCILIATION_SOURCE_ATTR_TYPE_ID), "reconciliation-source"),
                    "Source",
                    "group",
                )
            val accounts: AccountWriteRepository = mock { every { getAllAccounts() } returns flowOf(listOf(account)) }
            val attributes: AccountAttributeWriteRepository = mock { every { getByType(any()) } returns flowOf(listOf(attribute)) }
            val categories: CategoryWriteRepository = mock { every { getAllCategories() } returns flowOf(emptyList()) }
            val transactions: TransactionWriteRepository = mock { every { getAccountBalances() } returns flowOf(emptyList()) }
            val people: PersonWriteRepository = mock { every { getAllPeople() } returns flowOf(emptyList()) }
            val ownerships: PersonAccountOwnershipWriteRepository = mock { every { getAllOwnerships() } returns flowOf(emptyList()) }
            val types: AttributeTypeWriteRepository = mock(MockMode.autoUnit)
            val maintenance: Maintenance = mock(MockMode.autoUnit)

            setContent {
                AccountsScreen(
                    accountRepository = accounts,
                    accountAttributeRepository = attributes,
                    attributeTypeRepository = types,
                    categoryRepository = categories,
                    transactionRepository = transactions,
                    personRepository = people,
                    personAccountOwnershipRepository = ownerships,
                    maintenance = maintenance,
                    scrollToAccountId = account.id,
                    onAccountClick = {},
                )
            }

            onNodeWithText("Shadow wallet").assertDoesNotExist()
            onNode(isToggleable()).performClick()
            onNodeWithText("Shadow wallet").assertIsDisplayed()
            onNodeWithContentDescription("Reconciliation account").assertIsDisplayed()
        }
    }
}
