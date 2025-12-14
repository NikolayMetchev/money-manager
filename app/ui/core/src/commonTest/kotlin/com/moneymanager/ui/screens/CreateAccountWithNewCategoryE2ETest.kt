package com.moneymanager.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilDoesNotExist
import androidx.compose.ui.test.waitUntilExactlyOneExists
import com.moneymanager.database.DatabaseManager
import com.moneymanager.database.DbLocation
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.createTestDatabaseManager
import com.moneymanager.test.database.deleteTestDatabase
import com.moneymanager.ui.MoneyManagerApp
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * End-to-end test that reproduces the bug where creating a new category from
 * the Create Account dialog and then creating an account fails with a
 * FOREIGN KEY constraint error.
 *
 * The test flow:
 * 1. Open the app with a fresh database
 * 2. Navigate to create a new account
 * 3. From the category dropdown, create a new category
 * 4. Create the account with the newly created category
 * 5. Verify the account is created successfully (no FK error)
 */
@OptIn(ExperimentalTestApi::class)
class CreateAccountWithNewCategoryE2ETest {
    private var testDbLocation: DbLocation? = null

    @AfterTest
    fun cleanup() {
        testDbLocation?.let { location ->
            deleteTestDatabase(location)
        }
    }

    @Test
    fun createAccount_withNewlyCreatedCategory_shouldSucceed() =
        runComposeUiTest {
            // Given: Create a fresh test database
            testDbLocation = createTestDatabaseLocation()
            val databaseManager = createTestDatabaseManager()

            setContent {
                MoneyManagerApp(
                    databaseManager =
                        TestDatabaseManager(
                            databaseManager = databaseManager,
                            testLocation = testDbLocation!!,
                        ),
                    appVersion = AppVersion("1.0.0-test"),
                )
            }

            // Wait for app to load
            waitForIdle()
            waitUntilExactlyOneExists(hasText("Your Accounts"), timeoutMillis = 10000)

            // Step 1: Click "Add Account" to open the Create Account dialog
            onNodeWithText("+ Add Account").performClick()
            waitUntilExactlyOneExists(hasText("Create New Account"), timeoutMillis = 5000)
            onNodeWithText("Create New Account").assertIsDisplayed()

            // Step 2: Enter account name
            onNodeWithText("Account Name").performTextInput("Test Checking Account")

            // Step 3: Open the category dropdown and click "Create New Category"
            onNodeWithText("Uncategorized").performClick()
            waitForIdle()
            onNodeWithText("+ Create New Category").performClick()

            // Step 4: Wait for Create Category dialog to appear (title is "Create New Category")
            waitUntilExactlyOneExists(hasText("Category Name"), timeoutMillis = 5000)

            // Step 5: Enter category name and create the category
            onNodeWithText("Category Name").performTextInput("My New Category")

            // Wait for idle before clicking to ensure button is ready
            waitForIdle()

            // Click the "Create" button - on Android the last one (index 1) might be the category dialog's button
            // due to different rendering order. Try clicking Create buttons until Category Name disappears.
            val createButtons = onAllNodesWithText("Create").fetchSemanticsNodes()
            if (createButtons.size > 1) {
                // Multiple Create buttons - click the last one (category dialog is on top on Android)
                onAllNodesWithText("Create")[createButtons.size - 1].performClick()
            } else {
                onAllNodesWithText("Create")[0].performClick()
            }

            // Wait for category dialog to close (Category Name field should disappear)
            waitUntilDoesNotExist(hasText("Category Name"), timeoutMillis = 10000)

            // Give UI time to update after dialog dismissal
            waitForIdle()

            // Step 6: Verify we're still in the Create Account dialog
            waitUntilExactlyOneExists(hasText("Create New Account"), timeoutMillis = 5000)

            // Step 7: Create the account - this should NOT fail with FK constraint error
            // The category should be selected (either visible or through internal state)
            onNodeWithText("Create").performClick()

            // Wait for account dialog to close (indicates success)
            // If the bug is present, the dialog will stay open with an error message
            waitUntilDoesNotExist(hasText("Create New Account"), timeoutMillis = 10000)

            // Step 8: Verify the account was created and appears in the list
            onNodeWithText("Test Checking Account").assertIsDisplayed()
        }
}

/**
 * Wrapper around DatabaseManager that overrides getDefaultLocation
 * to return the test database location.
 */
private class TestDatabaseManager(
    private val databaseManager: DatabaseManager,
    private val testLocation: DbLocation,
) : DatabaseManager by databaseManager {
    override fun getDefaultLocation(): DbLocation = testLocation
}
