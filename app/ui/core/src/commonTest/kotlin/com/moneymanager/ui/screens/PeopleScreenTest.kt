package com.moneymanager.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import com.moneymanager.domain.model.Person
import com.moneymanager.domain.model.PersonId
import com.moneymanager.presenter.error.ProvideSchemaAwareScope
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.createPerson
import com.moneymanager.ui.screens.people.PeopleScreen
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class PeopleScreenTest : DbTest() {
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
                    )
                }
            }
            waitUntilAtLeastOneExists(hasText("Also known as: Alexander Jones"))
            waitUntilAtLeastOneExists(hasText("Undo merge of Alexander Jones"))
            onNodeWithText("Alex Jones").assertIsDisplayed()
        }
    }
}
