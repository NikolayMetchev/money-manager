package com.moneymanager.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import com.moneymanager.presenter.error.ProvideSchemaAwareScope
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createAccount
import com.moneymanager.test.database.createOwnership
import com.moneymanager.test.database.createPerson
import com.moneymanager.ui.screens.people.PeopleScreen
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class PeopleScreenTest : DbTest() {
    @Test
    fun filtersByNameAndAccountAndOpensAnyOwnedAccount() {
        val savingsId =
            runBlocking {
                openTestDatabase()
                val alex =
                    repositories.personRepository.createPerson(
                        Person(PersonId(0), firstName = "Alex", middleName = null, lastName = "Jones"),
                    )
                val bri =
                    repositories.personRepository.createPerson(
                        Person(PersonId(0), firstName = "Bri", middleName = null, lastName = "Smith"),
                    )
                repositories.personRepository.createPerson(Person(PersonId(0), firstName = "Devon", middleName = null, lastName = "Jones"))
                val current =
                    repositories.accountRepository.createAccount(
                        Account(AccountId(0), name = "Joint current", openingDate = Instant.DISTANT_PAST),
                    )
                val savings =
                    repositories.accountRepository.createAccount(
                        Account(AccountId(0), name = "Savings", openingDate = Instant.DISTANT_PAST),
                    )
                with(repositories.personAccountOwnershipRepository) {
                    createOwnership(alex, current)
                    createOwnership(bri, current)
                    createOwnership(alex, savings)
                }
                savings
            }
        var openedAccountId: AccountId? = null
        runMoneyManagerComposeUiTest {
            setContent {
                ProvideSchemaAwareScope {
                    PeopleScreen(
                        repositories.personRepository,
                        repositories.personAttributeRepository,
                        repositories.personAccountOwnershipRepository,
                        repositories.attributeTypeRepository,
                        repositories.accountRepository,
                        onAccountClick = { openedAccountId = it.id },
                    )
                }
            }
            waitUntilAtLeastOneExists(hasText("2 accounts"))
            onNodeWithText("Devon Jones").assertIsDisplayed()
            onNodeWithText("Account").performTextInput("JOINT")
            onNodeWithText("Bri Smith").assertIsDisplayed()
            onNodeWithText("Devon Jones").assertDoesNotExist()
            onNodeWithText("Name").performTextInput("jones")
            onNodeWithText("Bri Smith").assertDoesNotExist()
            onNodeWithText("2 accounts").performClick()
            onNodeWithText("Joint current").assertIsDisplayed()
            onNodeWithText("Savings").performClick()
            runOnIdle { assertEquals(savingsId, openedAccountId) }
        }
    }

    @Test
    fun mergedPersonAppearsAsAliasWithUndoAction() {
        runBlocking {
            openTestDatabase()
            with(repositories.personRepository) {
                val survivor = createPerson(Person(PersonId(0), firstName = "Alex", middleName = null, lastName = "Jones"))
                val duplicate = createPerson(Person(PersonId(0), firstName = "Alexander", middleName = null, lastName = "Jones"))
                mergePeople(deletedPerson = duplicate, survivingPerson = survivor)
            }
        }
        runMoneyManagerComposeUiTest {
            setContent {
                ProvideSchemaAwareScope {
                    PeopleScreen(
                        repositories.personRepository,
                        repositories.personAttributeRepository,
                        repositories.personAccountOwnershipRepository,
                        repositories.attributeTypeRepository,
                        repositories.accountRepository,
                    )
                }
            }
            waitUntilAtLeastOneExists(hasText("Also known as: Alexander Jones"))
            waitUntilAtLeastOneExists(hasText("Undo merge of Alexander Jones"))
            onNodeWithText("Alex Jones").assertIsDisplayed()
        }
    }
}
