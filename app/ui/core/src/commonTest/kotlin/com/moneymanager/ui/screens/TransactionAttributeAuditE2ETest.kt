package com.moneymanager.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilDoesNotExist
import androidx.compose.ui.test.waitUntilExactlyOneExists
import com.moneymanager.database.DatabaseManager
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.createTestDatabaseManager
import com.moneymanager.test.database.deleteTestDatabase
import com.moneymanager.ui.test.TestMoneyManagerApp
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * End-to-end test that verifies attributes added during initial transaction creation
 * appear in the audit history at revision 1 (the INSERT entry).
 *
 * Test flow:
 * 1. Open app with fresh database
 * 2. Create source account
 * 3. Create target account
 * 4. Navigate to source account's transactions
 * 5. Create a new transaction with an attribute
 * 6. Open audit history for that transaction
 * 7. Verify the attribute appears in revision 1
 */
@OptIn(ExperimentalTestApi::class)
class TransactionAttributeAuditE2ETest {
    private var testDbLocation: DbLocation? = null

    @AfterTest
    fun cleanup() {
        testDbLocation?.let { location ->
            deleteTestDatabase(location)
        }
    }

    @Test
    fun createTransaction_withAttribute_shouldShowAttributeInAuditRevision1() =
        runComposeUiTest {
            // Given: Create a fresh test database
            testDbLocation = createTestDatabaseLocation()
            val databaseManager = createTestDatabaseManager()

            setContent {
                TestMoneyManagerApp(
                    databaseManager =
                        AttributeTestDatabaseManager(
                            databaseManager = databaseManager,
                            testLocation = testDbLocation!!,
                        ),
                    appVersion = AppVersion("1.0.0-test"),
                )
            }

            // Wait for app to load
            waitForIdle()
            waitUntilExactlyOneExists(hasText("Your Accounts"), timeoutMillis = 10000)

            // Step 1: Create source account (Checking)
            onNodeWithText("+ Add Account").performClick()
            waitUntilExactlyOneExists(hasText("Create New Account"), timeoutMillis = 5000)
            onNodeWithText("Account Name").performTextInput("Checking Account")
            onNodeWithText("Create").performClick()
            waitUntilDoesNotExist(hasText("Create New Account"), timeoutMillis = 10000)
            waitForIdle()

            // Verify source account was created
            onNodeWithText("Checking Account").assertIsDisplayed()

            // Step 2: Create target account (Savings)
            onNodeWithText("+ Add Account").performClick()
            waitUntilExactlyOneExists(hasText("Create New Account"), timeoutMillis = 5000)
            onNodeWithText("Account Name").performTextInput("Savings Account")
            onNodeWithText("Create").performClick()
            waitUntilDoesNotExist(hasText("Create New Account"), timeoutMillis = 10000)
            waitForIdle()

            // Verify target account was created
            onNodeWithText("Savings Account").assertIsDisplayed()

            // Step 3: Navigate to Checking Account's transactions
            onNodeWithText("Checking Account").performClick()
            waitForIdle()

            // Wait for transactions screen to load
            waitUntilExactlyOneExists(hasText("Checking Account", substring = true), timeoutMillis = 5000)

            // Step 4: Create a new transaction with an attribute
            // Click the FAB to create a new transaction
            onNodeWithText("+").performClick()
            waitForIdle()

            // Wait for transaction entry dialog - look for the dialog title
            waitUntilExactlyOneExists(hasText("Create New Transaction"), timeoutMillis = 5000)
            waitForIdle()

            // Verify Create button is initially disabled (form invalid - no fields filled)
            onAllNodesWithText("Create")[0].assertIsNotEnabled()

            // Enter amount first (scroll to it if needed)
            onNodeWithText("Amount").performScrollTo()
            waitForIdle()
            onNodeWithText("Amount").performTextInput("100")
            waitForIdle()

            // Create button should still be disabled (not all required fields filled)
            onAllNodesWithText("Create")[0].assertIsNotEnabled()

            // Enter description
            onNodeWithText("Description").performScrollTo()
            waitForIdle()
            onNodeWithText("Description").performTextInput("Test Transfer with Attribute")
            waitForIdle()

            // Create button should still be disabled (no accounts or currency selected)
            onAllNodesWithText("Create")[0].assertIsNotEnabled()

            // Select source account (From Account) - Checking Account
            onNodeWithText("From Account", substring = true).performScrollTo()
            waitForIdle()
            onNodeWithText("From Account", substring = true).performClick()
            waitForIdle()
            mainClock.advanceTimeBy(500)
            waitForIdle()
            // Select Checking Account from dropdown - use last matching node (dropdown item)
            val checkingNodes = onAllNodesWithText("Checking Account").fetchSemanticsNodes()
            onAllNodesWithText("Checking Account")[checkingNodes.size - 1].performClick()
            waitForIdle()
            // Verify dialog still open after selecting From Account
            onNodeWithText("Create New Transaction").assertIsDisplayed()

            // Select target account (To Account) - Savings Account
            onNodeWithText("To Account", substring = true).performScrollTo()
            waitForIdle()
            onNodeWithText("To Account", substring = true).performClick()
            waitForIdle()
            mainClock.advanceTimeBy(500)
            waitForIdle()
            // Select Savings Account from dropdown - use last matching node (dropdown item)
            val savingsNodes = onAllNodesWithText("Savings Account").fetchSemanticsNodes()
            onAllNodesWithText("Savings Account")[savingsNodes.size - 1].performClick()
            waitForIdle()
            // Verify dialog still open after selecting To Account
            onNodeWithText("Create New Transaction").assertIsDisplayed()

            // Select currency - USD is seeded in fresh database
            onNodeWithText("Currency", substring = true).performScrollTo()
            waitForIdle()
            onNodeWithText("Currency", substring = true).performClick()
            waitForIdle()
            mainClock.advanceTimeBy(500)
            waitForIdle()
            // Type "USD" in the search field to filter
            onNodeWithText("Type to search...", substring = true).performTextInput("USD")
            waitForIdle()
            mainClock.advanceTimeBy(500)
            waitForIdle()
            // Select "USD - US Dollar" from the dropdown (exact match)
            onNodeWithText("USD - US Dollar").performClick()
            waitForIdle()
            mainClock.advanceTimeBy(500)
            waitForIdle()

            // Verify the Create Transaction dialog is still open after selecting currency
            onNodeWithText("Create New Transaction").assertIsDisplayed()

            // After selecting all required fields, Create button should now be enabled
            onAllNodesWithText("Create")[0].assertIsEnabled()

            // Step 5: Add an attribute
            // Wait for UI to settle and ensure Attributes section is visible
            mainClock.advanceTimeBy(1000)
            waitForIdle()

            // Verify dialog is still open before adding attribute
            waitUntilExactlyOneExists(hasText("Create New Transaction"), timeoutMillis = 5000)

            // Try to find the Attributes section header first
            onNodeWithText("Attributes").assertIsDisplayed()

            // The button might be below the visible area, so scroll to it first
            onNodeWithText("+ Add Attribute").performScrollTo()
            waitForIdle()
            mainClock.advanceTimeBy(500)
            waitForIdle()
            onNodeWithText("+ Add Attribute").performClick()
            waitForIdle()
            mainClock.advanceTimeBy(500)
            waitForIdle()

            // Wait for attribute input fields to appear
            waitUntilExactlyOneExists(hasText("Type"), timeoutMillis = 5000)

            // Enter attribute type name
            onNodeWithText("Type").performTextInput("Reference Number")
            waitForIdle()
            mainClock.advanceTimeBy(300)
            waitForIdle()

            // Enter attribute value
            val valueFields = onAllNodesWithText("Value").fetchSemanticsNodes()
            if (valueFields.isNotEmpty()) {
                onAllNodesWithText("Value")[0].performClick()
                waitForIdle()
                onAllNodesWithText("Value")[0].performTextInput("REF-001")
                waitForIdle()
                mainClock.advanceTimeBy(300)
                waitForIdle()
            }

            // Step 6: Create the transaction
            // Verify dialog is still open
            waitUntilExactlyOneExists(hasText("Create New Transaction"), timeoutMillis = 5000)

            // Verify Create button is enabled (all required fields should be filled)
            onAllNodesWithText("Create")[0].assertIsEnabled()

            // Debug: Verify description is still filled
            onNodeWithText("Test Transfer with Attribute").assertIsDisplayed()

            // Click the Create button in the transaction dialog
            onAllNodesWithText("Create")[0].performClick()
            waitForIdle()
            mainClock.advanceTimeBy(2000)
            waitForIdle()

            // Wait for the dialog to close (Create New Transaction should disappear)
            waitUntilDoesNotExist(hasText("Create New Transaction"), timeoutMillis = 15000)
            waitForIdle()

            // Wait for the transaction to appear in the list (confirms transaction was created)
            waitUntilExactlyOneExists(hasText("Test Transfer with Attribute", substring = true), timeoutMillis = 15000)
            waitForIdle()

            // Step 7: Find the transaction and open audit history
            onNodeWithText("Test Transfer with Attribute", substring = true).assertIsDisplayed()

            // Click the audit history button (📜 emoji)
            onNodeWithText("\uD83D\uDCDC").performClick()
            waitForIdle()

            // Wait for audit history dialog to appear
            waitUntilExactlyOneExists(hasText("Audit History:", substring = true), timeoutMillis = 5000)
            waitForIdle()

            // Step 8: Verify the audit history shows exactly one revision (Rev 1) with the attribute
            // Wait for audit entries to load (they load asynchronously)
            mainClock.advanceTimeBy(2000)
            waitForIdle()
            waitUntilExactlyOneExists(hasText("Rev 1", substring = true), timeoutMillis = 10000)
            onNodeWithText("Rev 1", substring = true).assertIsDisplayed()

            // Verify the attribute appears in the audit history at revision 1
            onNodeWithText("Reference Number", substring = true).assertIsDisplayed()
            onNodeWithText("REF-001", substring = true).assertIsDisplayed()

            // Verify there is NO "Rev 2" - confirming only one audit entry exists
            // This ensures the attribute didn't cause an extra revision bump
            val rev2Nodes = onAllNodesWithText("Rev 2", substring = true).fetchSemanticsNodes()
            assert(rev2Nodes.isEmpty()) {
                "Expected no 'Rev 2' entry, but found ${rev2Nodes.size}. " +
                    "Attribute should be part of revision 1, not create a new revision."
            }
        }
}

/**
 * Wrapper around DatabaseManager that overrides getDefaultLocation
 * to return the test database location.
 */
private class AttributeTestDatabaseManager(
    private val databaseManager: DatabaseManager,
    private val testLocation: DbLocation,
) : DatabaseManager by databaseManager {
    override fun getDefaultLocation(): DbLocation = testLocation
}
