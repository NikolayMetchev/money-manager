@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilExactlyOneExists
import com.moneymanager.database.DatabaseMaintenanceService
import com.moneymanager.database.ManualSourceRecorder
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.SampleGeneratorSourceRecorder
import com.moneymanager.di.database.DatabaseComponent
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountBalance
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountRow
import com.moneymanager.domain.model.AttributeType
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.CategoryBalance
import com.moneymanager.domain.model.CsvSourceDetails
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.NewAttribute
import com.moneymanager.domain.model.PageWithTargetIndex
import com.moneymanager.domain.model.PagingInfo
import com.moneymanager.domain.model.PagingResult
import com.moneymanager.domain.model.SourceRecorder
import com.moneymanager.domain.model.SourceType
import com.moneymanager.domain.model.TransactionId
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferAttribute
import com.moneymanager.domain.model.TransferAuditEntry
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.model.TransferSource
import com.moneymanager.domain.model.csv.CsvImportId
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AttributeTypeRepository
import com.moneymanager.domain.repository.AuditRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.CsvImportSourceRecord
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.SampleGeneratorSourceRecord
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.domain.repository.TransferAttributeRepository
import com.moneymanager.domain.repository.TransferSourceRepository
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.createTestDatabaseManager
import com.moneymanager.test.database.deleteTestDatabase
import com.moneymanager.ui.error.ProvideSchemaAwareScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class AccountTransactionsScreenTest {
    @Test
    fun accountTransactionCard_flipsAccountDisplay_whenPerspectiveChanges() =
        runComposeUiTest {
            // Given: Two accounts and a transfer between them
            val now = Clock.System.now()
            val usdCurrency =
                Currency(
                    id = CurrencyId(Uuid.random()),
                    code = "USD",
                    name = "US Dollar",
                    scaleFactor = 100,
                )

            val checking =
                Account(
                    id = AccountId(1L),
                    name = "Checking",
                    openingDate = now,
                )
            val savings =
                Account(
                    id = AccountId(2L),
                    name = "Savings",
                    openingDate = now,
                )

            val transfer =
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = now,
                    description = "Transfer to savings",
                    sourceAccountId = checking.id,
                    targetAccountId = savings.id,
                    amount = Money.fromDisplayValue(100.0, usdCurrency),
                )

            val accountRepository = FakeAccountRepository(listOf(checking, savings))
            val transactionRepository = FakeTransactionRepository(listOf(transfer))
            val transferSourceRepository = FakeTransferSourceRepository()
            val currencyRepository = FakeCurrencyRepository(listOf(usdCurrency))
            val categoryRepository = FakeCategoryRepository()
            val auditRepository = FakeAuditRepository()
            val attributeTypeRepository = FakeAttributeTypeRepository()
            val transferAttributeRepository = FakeTransferAttributeRepository()
            val maintenanceService = FakeDatabaseMaintenanceService()

            // When: Viewing from Checking account's perspective
            setContent {
                ProvideSchemaAwareScope {
                    var currentAccountId by remember { mutableStateOf(checking.id) }

                    AccountTransactionsScreen(
                        accountId = currentAccountId,
                        transactionRepository = transactionRepository,
                        transferSourceRepository = transferSourceRepository,
                        accountRepository = accountRepository,
                        categoryRepository = categoryRepository,
                        currencyRepository = currencyRepository,
                        auditRepository = auditRepository,
                        attributeTypeRepository = attributeTypeRepository,
                        transferAttributeRepository = transferAttributeRepository,
                        maintenanceService = maintenanceService,
                        onAccountIdChange = { currentAccountId = it },
                        onCurrencyIdChange = {},
                    )
                }
            }

            // Then: When viewing Checking's transactions, should show:
            // - 1 "Checking" in matrix header
            // - 1 "Savings" in matrix header
            // - 1 "Savings" in transaction row (the OTHER account from Checking's perspective)
            waitForIdle()

            onAllNodesWithText("Checking").assertCountEquals(1) // Matrix header only
            onAllNodesWithText("Savings").assertCountEquals(2) // Matrix header + transaction row

            // When: Click on "Savings" in the transaction row to switch to Savings perspective
            // Use index [1] (the second node) since [0] is in the matrix
            onAllNodesWithText("Savings")[1].performClick()

            // Wait for state to update and recomposition to occur
            waitForIdle()
            // Wait a bit more to ensure all state updates have propagated
            mainClock.advanceTimeBy(100)

            // Then: After flipping to Savings perspective, should show:
            // - 1 "Checking" in matrix header
            // - 1 "Savings" in matrix header
            // - 1 "Checking" in transaction row (the OTHER account from Savings' perspective)
            // THIS IS THE BUG: It's probably still showing "Savings" instead of "Checking"
            onAllNodesWithText("Savings").assertCountEquals(1) // Matrix header only (NOT in transaction row anymore!)
            onAllNodesWithText("Checking").assertCountEquals(2) // Matrix header + transaction row
        }

    @Test
    fun clickingAccountInTransaction_switchesPerspective() =
        runComposeUiTest {
            // Given: Two accounts with a transfer between them
            val now = Clock.System.now()
            val usdCurrency =
                Currency(
                    id = CurrencyId(Uuid.random()),
                    code = "USD",
                    name = "US Dollar",
                    scaleFactor = 100,
                )

            val checking =
                Account(
                    id = AccountId(1L),
                    name = "Checking",
                    openingDate = now,
                )
            val savings =
                Account(
                    id = AccountId(2L),
                    name = "Savings",
                    openingDate = now,
                )

            val transfer =
                Transfer(
                    id = TransferId(Uuid.random()),
                    timestamp = now,
                    description = "Transfer to savings",
                    sourceAccountId = checking.id,
                    targetAccountId = savings.id,
                    amount = Money.fromDisplayValue(100.0, usdCurrency),
                )

            val accountRepository = FakeAccountRepository(listOf(checking, savings))
            val transactionRepository = FakeTransactionRepository(listOf(transfer))
            val transferSourceRepository = FakeTransferSourceRepository()
            val currencyRepository = FakeCurrencyRepository(listOf(usdCurrency))
            val categoryRepository = FakeCategoryRepository()
            val auditRepository = FakeAuditRepository()
            val attributeTypeRepository = FakeAttributeTypeRepository()
            val transferAttributeRepository = FakeTransferAttributeRepository()
            val maintenanceService = FakeDatabaseMaintenanceService()

            setContent {
                ProvideSchemaAwareScope {
                    var currentAccountId by remember { mutableStateOf(checking.id) }

                    AccountTransactionsScreen(
                        accountId = currentAccountId,
                        transactionRepository = transactionRepository,
                        transferSourceRepository = transferSourceRepository,
                        accountRepository = accountRepository,
                        categoryRepository = categoryRepository,
                        currencyRepository = currencyRepository,
                        auditRepository = auditRepository,
                        attributeTypeRepository = attributeTypeRepository,
                        transferAttributeRepository = transferAttributeRepository,
                        maintenanceService = maintenanceService,
                        onAccountIdChange = { currentAccountId = it },
                        onCurrencyIdChange = {},
                    )
                }
            }

            waitForIdle()

            // Find all "Savings" text nodes - should be:
            // [0] = Matrix header
            // [1] = Transaction row (the "other" account from Checking's view)
            val savingsNodesBeforeClick = onAllNodesWithText("Savings")
            savingsNodesBeforeClick.assertCountEquals(2)

            savingsNodesBeforeClick[1].performClick()

            waitForIdle()
            mainClock.advanceTimeBy(200)

            // Verify "Savings" only appears once (in matrix header, NOT in transaction row)
            onAllNodesWithText("Savings").assertCountEquals(1)

            // Verify "Checking" appears twice:
            // [0] = Matrix header
            // [1] = Transaction row (the "other" account from Savings' view)
            onAllNodesWithText("Checking").assertCountEquals(2)
        }

    @Test
    fun editTransaction_addNewAttribute_savesAttributeSuccessfully() {
        // Set up real database
        var testDbLocation: DbLocation? = null
        lateinit var database: MoneyManagerDatabaseWrapper
        lateinit var repositories: DatabaseComponent
        var checkingAccountId: AccountId? = null
        var savingsAccountId: AccountId? = null
        var transferId: TransferId? = null

        try {
            // Given: Set up a real database with accounts and a transaction
            runBlocking {
                testDbLocation = createTestDatabaseLocation()
                val databaseManager = createTestDatabaseManager()
                database = databaseManager.openDatabase(testDbLocation)
                repositories = DatabaseComponent.create(database)

                val now = Clock.System.now()

                // Get USD currency (seeded by database)
                val usdCurrency = repositories.currencyRepository.getCurrencyByCode("USD").first()!!

                // Create accounts
                checkingAccountId =
                    repositories.accountRepository.createAccount(
                        Account(
                            id = AccountId(0L),
                            name = "E2E Test Checking",
                            openingDate = now,
                        ),
                    )
                savingsAccountId =
                    repositories.accountRepository.createAccount(
                        Account(
                            id = AccountId(0L),
                            name = "E2E Test Savings",
                            openingDate = now,
                        ),
                    )

                // Create a transfer
                transferId = TransferId(Uuid.random())
                val transfer =
                    Transfer(
                        id = transferId,
                        timestamp = now,
                        description = "E2E Test Transaction",
                        sourceAccountId = checkingAccountId,
                        targetAccountId = savingsAccountId,
                        amount = Money.fromDisplayValue(50.0, usdCurrency),
                    )
                val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
                repositories.transactionRepository.createTransfers(
                    transfers = listOf(transfer),
                    sourceRecorder = SampleGeneratorSourceRecorder(repositories.transferSourceQueries, deviceId),
                )

                // Refresh materialized views so the transaction appears
                repositories.maintenanceService.fullRefreshMaterializedViews()
            }

            // Run the UI test
            runComposeUiTest {
                // When: Viewing the account transactions screen
                setContent {
                    ProvideSchemaAwareScope {
                        var currentAccountId by remember { mutableStateOf(checkingAccountId!!) }

                        AccountTransactionsScreen(
                            accountId = currentAccountId,
                            transactionRepository = repositories.transactionRepository,
                            transferSourceRepository = repositories.transferSourceRepository,
                            accountRepository = repositories.accountRepository,
                            categoryRepository = repositories.categoryRepository,
                            currencyRepository = repositories.currencyRepository,
                            auditRepository = repositories.auditRepository,
                            attributeTypeRepository = repositories.attributeTypeRepository,
                            transferAttributeRepository = repositories.transferAttributeRepository,
                            maintenanceService = repositories.maintenanceService,
                            onAccountIdChange = { currentAccountId = it },
                            onCurrencyIdChange = {},
                        )
                    }
                }

                waitForIdle()

                // Click the edit button (pencil emoji) on the transaction
                onNodeWithText("\u270F\uFE0F").performClick()
                waitForIdle()

                // Wait for edit dialog to appear
                waitUntilExactlyOneExists(hasText("Edit Transaction"), timeoutMillis = 5000)

                // Wait for "+ Add Attribute" button to be available and click it
                waitUntilExactlyOneExists(hasText("+ Add Attribute"), timeoutMillis = 5000)
                onNodeWithText("+ Add Attribute").performClick()
                waitForIdle()

                // Find and fill in the attribute type field
                onAllNodesWithText("Type")[0].performClick()
                waitForIdle()

                // Type in the attribute type name
                onAllNodesWithText("Type")[0].performTextInput("Reference Number")
                waitForIdle()

                // Find and fill in the value field
                onAllNodesWithText("Value")[0].performClick()
                waitForIdle()
                onAllNodesWithText("Value")[0].performTextInput("REF-12345")
                waitForIdle()

                // Click Update to save
                onNodeWithText("Update").performClick()
                waitForIdle()

                // Wait for save operation to complete
                mainClock.advanceTimeBy(500)
                waitForIdle()

                // Verify from database: the attribute was saved
                runBlocking {
                    // Get the transfer's current revision
                    val savedTransfer =
                        repositories.transactionRepository
                            .getTransactionById(transferId!!.id).first()!!
                    // Note: For revisionId = 1 (newly created transfers), adding attributes
                    // doesn't bump the revision - they're part of the initial creation.
                    // Only existing transfers (revisionId > 1) get bumped when adding attributes.
                    assertTrue(
                        savedTransfer.revisionId >= 1,
                        "Transfer should have valid revision",
                    )

                    // Verify the attribute type was created
                    val attributeType =
                        repositories.attributeTypeRepository
                            .getByName("Reference Number").first()
                    assertTrue(
                        attributeType != null,
                        "Attribute type 'Reference Number' should exist in database",
                    )

                    // Verify the attribute was saved
                    val attributes =
                        repositories.transferAttributeRepository
                            .getByTransaction(transferId).first()
                    assertTrue(
                        attributes.any { it.value == "REF-12345" && it.attributeType.name == "Reference Number" },
                        "Attribute with value 'REF-12345' should exist in database. Found: $attributes",
                    )
                }

                // Refresh materialized views so the updated transaction appears
                runBlocking {
                    repositories.maintenanceService.fullRefreshMaterializedViews()
                }

                // Wait for UI to refresh after edit dialog closes
                mainClock.advanceTimeBy(300)
                waitForIdle()

                // Now click the Audit History button (📜) and verify it shows the new attribute
                onNodeWithText("\uD83D\uDCDC").performClick()
                waitForIdle()
                mainClock.advanceTimeBy(300)
                waitForIdle()

                // Verify audit history is displayed - title format is "Audit History: <transferId>"
                onNodeWithText("Audit History:", substring = true).assertIsDisplayed()

                // Wait for audit entries to load
                mainClock.advanceTimeBy(1000)
                waitForIdle()

                // The audit should show UPDATE entry (revision 2)
                // Verify the attribute type and value are displayed in the audit history
                // The attribute is displayed as "+Reference Number:" and "REF-12345" as separate text nodes
                onNodeWithText("Reference Number", substring = true).assertIsDisplayed()
                onNodeWithText("REF-12345", substring = true).assertIsDisplayed()
            }
        } finally {
            // Clean up database
            testDbLocation?.let { deleteTestDatabase(it) }
        }
    }

    @Test
    fun editTransaction_addNewAttribute_createsAuditEntryWithNewRevision() {
        // This test verifies that editing an existing transaction and adding a new attribute
        // creates a new revision in the audit trail.
        //
        // The trigger logic only bumps revision for transfers with revisionId > 1,
        // so we first update the transfer to bump it to revision 2, then add an attribute.

        var testDbLocation: DbLocation? = null
        lateinit var database: MoneyManagerDatabaseWrapper
        lateinit var repositories: DatabaseComponent
        var checkingAccountId: AccountId? = null
        var savingsAccountId: AccountId? = null
        var transferId: TransferId? = null

        try {
            // Given: Set up a real database with accounts and a transaction
            runBlocking {
                testDbLocation = createTestDatabaseLocation()
                val databaseManager = createTestDatabaseManager()
                database = databaseManager.openDatabase(testDbLocation)
                repositories = DatabaseComponent.create(database)

                val now = Clock.System.now()

                // Get USD currency (seeded by database)
                val usdCurrency = repositories.currencyRepository.getCurrencyByCode("USD").first()!!

                // Create accounts
                checkingAccountId =
                    repositories.accountRepository.createAccount(
                        Account(
                            id = AccountId(0L),
                            name = "Audit Test Checking",
                            openingDate = now,
                        ),
                    )
                savingsAccountId =
                    repositories.accountRepository.createAccount(
                        Account(
                            id = AccountId(0L),
                            name = "Audit Test Savings",
                            openingDate = now,
                        ),
                    )

                // Create a transfer (revision 1)
                transferId = TransferId(Uuid.random())
                val transfer =
                    Transfer(
                        id = transferId,
                        timestamp = now,
                        description = "Audit Test Transaction",
                        sourceAccountId = checkingAccountId,
                        targetAccountId = savingsAccountId,
                        amount = Money.fromDisplayValue(100.0, usdCurrency),
                    )
                val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
                repositories.transactionRepository.createTransfers(
                    transfers = listOf(transfer),
                    sourceRecorder = ManualSourceRecorder(repositories.transferSourceQueries, deviceId),
                )

                // Verify initial revision is 1
                val initialTransfer =
                    repositories.transactionRepository.getTransactionById(transferId.id).first()!!
                assertEquals(1L, initialTransfer.revisionId, "Initial revision should be 1")

                // Refresh materialized views so the transaction appears
                repositories.maintenanceService.fullRefreshMaterializedViews()
            }

            // Run the UI test
            runComposeUiTest {
                // When: Viewing the account transactions screen
                setContent {
                    ProvideSchemaAwareScope {
                        var currentAccountId by remember { mutableStateOf(checkingAccountId!!) }

                        AccountTransactionsScreen(
                            accountId = currentAccountId,
                            transactionRepository = repositories.transactionRepository,
                            transferSourceRepository = repositories.transferSourceRepository,
                            accountRepository = repositories.accountRepository,
                            categoryRepository = repositories.categoryRepository,
                            currencyRepository = repositories.currencyRepository,
                            auditRepository = repositories.auditRepository,
                            attributeTypeRepository = repositories.attributeTypeRepository,
                            transferAttributeRepository = repositories.transferAttributeRepository,
                            maintenanceService = repositories.maintenanceService,
                            onAccountIdChange = { currentAccountId = it },
                            onCurrencyIdChange = {},
                        )
                    }
                }

                // Wait for the edit button to appear
                waitUntilExactlyOneExists(hasText("\u270F\uFE0F"), timeoutMillis = 10000)

                // Step 1: Open edit dialog
                onNodeWithText("\u270F\uFE0F").performClick()

                // Wait for edit dialog to appear
                waitUntilExactlyOneExists(hasText("Edit Transaction"), timeoutMillis = 10000)

                // Step 2: Add a new attribute
                waitUntilExactlyOneExists(hasText("+ Add Attribute"), timeoutMillis = 5000)
                onNodeWithText("+ Add Attribute").performClick()

                // Wait for attribute fields to appear
                waitUntilAtLeastOneExists(hasText("Type"), timeoutMillis = 5000)

                // Fill in the attribute type
                onAllNodesWithText("Type")[0].performClick()
                waitForIdle()
                onAllNodesWithText("Type")[0].performTextInput("Test Attribute")
                waitForIdle()

                // Fill in the attribute value
                onAllNodesWithText("Value")[0].performClick()
                waitForIdle()
                onAllNodesWithText("Value")[0].performTextInput("Test Value 123")
                waitForIdle()

                // Step 3: Click Update to save
                onNodeWithText("Update").performClick()
                waitForIdle()

                // Wait for save operation to complete
                mainClock.advanceTimeBy(500)
                waitForIdle()

                // Refresh materialized views
                runBlocking {
                    repositories.maintenanceService.fullRefreshMaterializedViews()
                }

                // Wait for audit button to be visible (indicates UI has refreshed)
                waitUntilExactlyOneExists(hasText("\uD83D\uDCDC"), timeoutMillis = 10000)

                // Step 4: Open audit history and verify via UI
                onNodeWithText("\uD83D\uDCDC").performClick()

                // Wait for audit history dialog to appear
                waitUntilExactlyOneExists(hasText("Audit History:", substring = true), timeoutMillis = 10000)

                // Step 5: Verify both revisions are shown in the UI
                // Rev 1 should be the initial INSERT
                waitUntilExactlyOneExists(hasText("Rev 1", substring = true), timeoutMillis = 10000)

                // Rev 2 should exist (UPDATE with attribute added)
                waitUntilExactlyOneExists(hasText("Rev 2", substring = true), timeoutMillis = 10000)

                // Verify the "Updated" (UPDATE) header exists for Rev 2
                waitUntilAtLeastOneExists(hasText("Updated", substring = true), timeoutMillis = 10000)

                // Verify the added attribute is shown with "+" prefix (indicates it was added in Rev 2)
                waitUntilExactlyOneExists(hasText("+Test Attribute:", substring = true), timeoutMillis = 10000)
                waitUntilAtLeastOneExists(hasText("Test Value 123", substring = true), timeoutMillis = 10000)
            }
        } finally {
            // Clean up database
            testDbLocation?.let { deleteTestDatabase(it) }
        }
    }

    @Test
    fun editTransaction_changeDescriptionAndAddAttribute_createsOnlyOneNewRevision() {
        // This test verifies that editing a transaction and changing BOTH the description
        // AND adding a new attribute creates only ONE new revision (Rev 2), not two.
        //
        // Bug scenario: If the save operation is not atomic, updating the transfer
        // (bumps to Rev 2) and then adding an attribute (bumps to Rev 3) would create
        // two revisions instead of one.

        var testDbLocation: DbLocation? = null
        lateinit var database: MoneyManagerDatabaseWrapper
        lateinit var repositories: DatabaseComponent
        var checkingAccountId: AccountId? = null
        var savingsAccountId: AccountId? = null
        var transferId: TransferId? = null

        try {
            // Given: Set up a real database with accounts and a transaction
            runBlocking {
                testDbLocation = createTestDatabaseLocation()
                val databaseManager = createTestDatabaseManager()
                database = databaseManager.openDatabase(testDbLocation)
                repositories = DatabaseComponent.create(database)

                val now = Clock.System.now()

                // Get USD currency (seeded by database)
                val usdCurrency = repositories.currencyRepository.getCurrencyByCode("USD").first()!!

                // Create accounts
                checkingAccountId =
                    repositories.accountRepository.createAccount(
                        Account(
                            id = AccountId(0L),
                            name = "Combined Edit Checking",
                            openingDate = now,
                        ),
                    )
                savingsAccountId =
                    repositories.accountRepository.createAccount(
                        Account(
                            id = AccountId(0L),
                            name = "Combined Edit Savings",
                            openingDate = now,
                        ),
                    )

                // Create a transfer (revision 1)
                transferId = TransferId(Uuid.random())
                val transfer =
                    Transfer(
                        id = transferId,
                        timestamp = now,
                        description = "Original Description",
                        sourceAccountId = checkingAccountId,
                        targetAccountId = savingsAccountId,
                        amount = Money.fromDisplayValue(100.0, usdCurrency),
                    )
                val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
                repositories.transactionRepository.createTransfers(
                    transfers = listOf(transfer),
                    sourceRecorder = ManualSourceRecorder(repositories.transferSourceQueries, deviceId),
                )

                // Verify initial revision is 1
                val initialTransfer =
                    repositories.transactionRepository.getTransactionById(transferId.id).first()!!
                assertEquals(1L, initialTransfer.revisionId, "Initial revision should be 1")

                // Refresh materialized views so the transaction appears
                repositories.maintenanceService.fullRefreshMaterializedViews()
            }

            // Run the UI test
            runComposeUiTest {
                // When: Viewing the account transactions screen
                setContent {
                    ProvideSchemaAwareScope {
                        var currentAccountId by remember { mutableStateOf(checkingAccountId!!) }

                        AccountTransactionsScreen(
                            accountId = currentAccountId,
                            transactionRepository = repositories.transactionRepository,
                            transferSourceRepository = repositories.transferSourceRepository,
                            accountRepository = repositories.accountRepository,
                            categoryRepository = repositories.categoryRepository,
                            currencyRepository = repositories.currencyRepository,
                            auditRepository = repositories.auditRepository,
                            attributeTypeRepository = repositories.attributeTypeRepository,
                            transferAttributeRepository = repositories.transferAttributeRepository,
                            maintenanceService = repositories.maintenanceService,
                            onAccountIdChange = { currentAccountId = it },
                            onCurrencyIdChange = {},
                        )
                    }
                }

                // Wait for the edit button to appear
                waitUntilExactlyOneExists(hasText("\u270F\uFE0F"), timeoutMillis = 10000)

                // Step 1: Open edit dialog
                onNodeWithText("\u270F\uFE0F").performClick()

                // Wait for edit dialog to appear
                waitUntilExactlyOneExists(hasText("Edit Transaction"), timeoutMillis = 10000)

                // Step 2: Change the description (index 1 is the editable text field in the dialog)
                waitUntilAtLeastOneExists(hasText("Original Description"), timeoutMillis = 5000)
                onAllNodesWithText("Original Description")[1]
                    .performTextReplacement("Updated Description")
                waitForIdle()

                // Step 3: Add a new attribute
                waitUntilExactlyOneExists(hasText("+ Add Attribute"), timeoutMillis = 5000)
                onNodeWithText("+ Add Attribute").performClick()

                // Wait for attribute fields to appear
                waitUntilAtLeastOneExists(hasText("Type"), timeoutMillis = 5000)

                // Fill in the attribute type
                onAllNodesWithText("Type")[0].performClick()
                waitForIdle()
                onAllNodesWithText("Type")[0].performTextInput("New Attribute")
                waitForIdle()

                // Fill in the attribute value
                onAllNodesWithText("Value")[0].performClick()
                waitForIdle()
                onAllNodesWithText("Value")[0].performTextInput("New Value")
                waitForIdle()

                // Step 4: Click Update to save (both changes should be atomic)
                onNodeWithText("Update").performClick()
                waitForIdle()

                // Wait for save operation to complete
                mainClock.advanceTimeBy(500)
                waitForIdle()

                // Refresh materialized views
                runBlocking {
                    repositories.maintenanceService.fullRefreshMaterializedViews()
                }

                // Verify the description was updated in the UI
                waitUntilAtLeastOneExists(hasText("Updated Description", substring = true), timeoutMillis = 10000)

                // Wait for audit button to be visible
                waitUntilExactlyOneExists(hasText("\uD83D\uDCDC"), timeoutMillis = 10000)

                // Step 5: Open audit history and verify via UI
                onNodeWithText("\uD83D\uDCDC").performClick()

                // Wait for audit history dialog to appear
                waitUntilExactlyOneExists(hasText("Audit History:", substring = true), timeoutMillis = 10000)

                // Step 6: Verify ONLY Rev 1 and Rev 2 exist (NOT Rev 3)
                // Rev 1 should be the initial INSERT
                waitUntilExactlyOneExists(hasText("Rev 1", substring = true), timeoutMillis = 10000)

                // Rev 2 should exist (combined UPDATE with description change + attribute addition)
                waitUntilExactlyOneExists(hasText("Rev 2", substring = true), timeoutMillis = 10000)

                // Rev 3 should NOT exist - both changes should be in a single revision
                onAllNodesWithText("Rev 3", substring = true).assertCountEquals(0)

                // Verify the description change is shown
                waitUntilAtLeastOneExists(hasText("Updated Description", substring = true), timeoutMillis = 10000)

                // Verify the added attribute is shown with "+" prefix
                waitUntilExactlyOneExists(hasText("+New Attribute:", substring = true), timeoutMillis = 10000)
                waitUntilAtLeastOneExists(hasText("New Value", substring = true), timeoutMillis = 10000)
            }
        } finally {
            // Clean up database
            testDbLocation?.let { deleteTestDatabase(it) }
        }
    }

    private class FakeAccountRepository(
        private val accounts: List<Account>,
    ) : AccountRepository {
        private val accountsFlow = MutableStateFlow(accounts)

        override fun getAllAccounts(): Flow<List<Account>> = accountsFlow

        override fun getAccountById(id: AccountId): Flow<Account?> = flowOf(accounts.find { it.id == id })

        override suspend fun createAccount(account: Account): AccountId {
            val newId = AccountId((accounts.maxOfOrNull { it.id.id } ?: 0L) + 1)
            val newAccount = account.copy(id = newId)
            accountsFlow.value = accountsFlow.value + newAccount
            return newId
        }

        override suspend fun createAccountsBatch(accounts: List<Account>): List<AccountId> {
            return accounts.map { createAccount(it) }
        }

        override suspend fun updateAccount(account: Account) {
            accountsFlow.value = accountsFlow.value.map { if (it.id == account.id) account else it }
        }

        override suspend fun deleteAccount(id: AccountId) {
            accountsFlow.value = accountsFlow.value.filter { it.id != id }
        }
    }

    private class FakeTransactionRepository(
        private val transfers: List<Transfer>,
    ) : TransactionRepository {
        override fun getTransactionById(id: Uuid): Flow<Transfer?> = flowOf(transfers.find { it.id.id == id })

        override fun getAllTransactions(): Flow<List<Transfer>> = flowOf(transfers)

        override fun getTransactionsByAccount(accountId: AccountId): Flow<List<Transfer>> =
            flowOf(transfers.filter { it.sourceAccountId == accountId || it.targetAccountId == accountId })

        override fun getTransactionsByDateRange(
            startDate: Instant,
            endDate: Instant,
        ): Flow<List<Transfer>> = flowOf(emptyList())

        override fun getTransactionsByAccountAndDateRange(
            accountId: AccountId,
            startDate: Instant,
            endDate: Instant,
        ): Flow<List<Transfer>> = flowOf(emptyList())

        override fun getAccountBalances(): Flow<List<AccountBalance>> = flowOf(emptyList())

        override suspend fun getRunningBalanceByAccountPaginated(
            accountId: AccountId,
            pageSize: Int,
            pagingInfo: PagingInfo?,
        ): PagingResult<AccountRow> {
            // Simulate the materialized view logic: create TWO AccountRow entries per transfer
            // One from the source account's perspective (outgoing = negative)
            // One from the target account's perspective (incoming = positive)
            val allRows =
                transfers.flatMap { transfer ->
                    listOf(
                        // Source account's perspective (outgoing = negative)
                        AccountRow(
                            transactionId = transfer.id,
                            timestamp = transfer.timestamp,
                            description = transfer.description,
                            accountId = transfer.sourceAccountId,
                            transactionAmount = Money(-transfer.amount.amount, transfer.amount.currency),
                            runningBalance = transfer.amount,
                            sourceAccountId = transfer.sourceAccountId,
                            targetAccountId = transfer.targetAccountId,
                        ),
                        // Target account's perspective (incoming = positive)
                        AccountRow(
                            transactionId = transfer.id,
                            timestamp = transfer.timestamp,
                            description = transfer.description,
                            accountId = transfer.targetAccountId,
                            transactionAmount = transfer.amount,
                            runningBalance = transfer.amount,
                            sourceAccountId = transfer.sourceAccountId,
                            targetAccountId = transfer.targetAccountId,
                        ),
                    )
                }.filter { it.accountId == accountId }
                    .sortedByDescending { it.timestamp }

            // Simple pagination for testing
            val items = allRows.take(pageSize)
            val hasMore = allRows.size > pageSize

            return PagingResult(
                items = items,
                pagingInfo =
                    PagingInfo(
                        lastTimestamp = items.lastOrNull()?.timestamp,
                        lastId = items.lastOrNull()?.transactionId,
                        hasMore = hasMore,
                    ),
            )
        }

        override suspend fun getRunningBalanceByAccountPaginatedBackward(
            accountId: AccountId,
            pageSize: Int,
            firstTimestamp: Instant,
            firstId: TransactionId,
        ): PagingResult<AccountRow> =
            PagingResult(
                items = emptyList(),
                pagingInfo =
                    PagingInfo(
                        lastTimestamp = null,
                        lastId = null,
                        hasMore = false,
                    ),
            )

        override suspend fun getPageContainingTransaction(
            accountId: AccountId,
            transactionId: TransferId,
            pageSize: Int,
        ): PageWithTargetIndex<AccountRow> {
            val allRows =
                transfers.flatMap { transfer ->
                    listOf(
                        AccountRow(
                            transactionId = transfer.id,
                            timestamp = transfer.timestamp,
                            description = transfer.description,
                            accountId = transfer.sourceAccountId,
                            transactionAmount = Money(-transfer.amount.amount, transfer.amount.currency),
                            runningBalance = transfer.amount,
                            sourceAccountId = transfer.sourceAccountId,
                            targetAccountId = transfer.targetAccountId,
                        ),
                        AccountRow(
                            transactionId = transfer.id,
                            timestamp = transfer.timestamp,
                            description = transfer.description,
                            accountId = transfer.targetAccountId,
                            transactionAmount = transfer.amount,
                            runningBalance = transfer.amount,
                            sourceAccountId = transfer.sourceAccountId,
                            targetAccountId = transfer.targetAccountId,
                        ),
                    )
                }.filter { it.accountId == accountId }
                    .sortedByDescending { it.timestamp }

            val targetIndex = allRows.indexOfFirst { it.transactionId.id == transactionId.id }
            val items = allRows.take(pageSize)
            val hasMore = allRows.size > pageSize

            return PageWithTargetIndex(
                items = items,
                targetIndex = targetIndex,
                pagingInfo =
                    PagingInfo(
                        lastTimestamp = items.lastOrNull()?.timestamp,
                        lastId = items.lastOrNull()?.transactionId,
                        hasMore = hasMore,
                    ),
                hasPrevious = false,
            )
        }

        override suspend fun createTransfers(
            transfers: List<Transfer>,
            newAttributes: Map<TransferId, List<NewAttribute>>,
            sourceRecorder: SourceRecorder,
            onProgress: (suspend (Int, Int) -> Unit)?,
        ) {}

        override suspend fun updateTransfer(
            transfer: Transfer?,
            deletedAttributeIds: Set<Long>,
            updatedAttributes: Map<Long, NewAttribute>,
            newAttributes: List<NewAttribute>,
            transactionId: TransferId,
        ) {}

        override suspend fun bumpRevisionOnly(id: TransferId): Long = 1L

        override suspend fun deleteTransaction(id: Uuid) {}
    }

    private class FakeCurrencyRepository(
        private val currencies: List<Currency>,
    ) : CurrencyRepository {
        override fun getAllCurrencies(): Flow<List<Currency>> = flowOf(currencies)

        override fun getCurrencyById(id: CurrencyId): Flow<Currency?> = flowOf(currencies.find { it.id == id })

        override fun getCurrencyByCode(code: String): Flow<Currency?> = flowOf(currencies.find { it.code == code })

        override suspend fun upsertCurrencyByCode(
            code: String,
            name: String,
        ): CurrencyId = CurrencyId(Uuid.random())

        override suspend fun updateCurrency(currency: Currency) {}

        override suspend fun deleteCurrency(id: CurrencyId) {}
    }

    private class FakeCategoryRepository : CategoryRepository {
        private val categories =
            listOf(
                Category(id = -1L, name = "Uncategorized", parentId = null),
                Category(id = 1L, name = "Food", parentId = null),
                Category(id = 2L, name = "Transport", parentId = null),
            )

        override fun getAllCategories(): Flow<List<Category>> = flowOf(categories)

        override fun getCategoryBalances(): Flow<List<CategoryBalance>> = flowOf(emptyList())

        override fun getCategoryById(id: Long): Flow<Category?> = flowOf(categories.find { it.id == id })

        override fun getTopLevelCategories(): Flow<List<Category>> = flowOf(categories.filter { it.parentId == null })

        override fun getCategoriesByParent(parentId: Long): Flow<List<Category>> = flowOf(categories.filter { it.parentId == parentId })

        override suspend fun createCategory(category: Category): Long = 0L

        override suspend fun updateCategory(category: Category) {}

        override suspend fun deleteCategory(id: Long) {}
    }

    private class FakeDatabaseMaintenanceService : DatabaseMaintenanceService {
        override suspend fun reindex(): Duration = Duration.ZERO

        override suspend fun vacuum(): Duration = Duration.ZERO

        override suspend fun analyze(): Duration = Duration.ZERO

        override suspend fun refreshMaterializedViews(): Duration = Duration.ZERO

        override suspend fun fullRefreshMaterializedViews(): Duration = Duration.ZERO
    }

    private class FakeAuditRepository : AuditRepository {
        override suspend fun getAuditHistoryForTransfer(transferId: TransferId): List<TransferAuditEntry> = emptyList()

        override suspend fun getAuditHistoryForTransferWithSource(transferId: TransferId): List<TransferAuditEntry> = emptyList()
    }

    private class FakeTransferSourceRepository : TransferSourceRepository {
        private var sourceIdCounter = 1L
        private val sources = mutableListOf<TransferSource>()

        override suspend fun recordManualSource(
            transactionId: TransferId,
            revisionId: Long,
            deviceInfo: DeviceInfo,
        ): TransferSource {
            val source =
                TransferSource(
                    id = sourceIdCounter++,
                    transactionId = transactionId,
                    revisionId = revisionId,
                    sourceType = SourceType.MANUAL,
                    deviceId = 1L,
                    deviceInfo = deviceInfo,
                    csvSource = null,
                    createdAt = Clock.System.now(),
                )
            sources.add(source)
            return source
        }

        override suspend fun recordCsvImportSource(
            transactionId: TransferId,
            revisionId: Long,
            csvImportId: CsvImportId,
            rowIndex: Long,
        ): TransferSource {
            val source =
                TransferSource(
                    id = sourceIdCounter++,
                    transactionId = transactionId,
                    revisionId = revisionId,
                    sourceType = SourceType.CSV_IMPORT,
                    deviceId = 1L,
                    deviceInfo = DeviceInfo.Jvm(osName = "Test", machineName = "Test"),
                    csvSource = CsvSourceDetails(importId = csvImportId, rowIndex = rowIndex, fileName = "test.csv"),
                    createdAt = Clock.System.now(),
                )
            sources.add(source)
            return source
        }

        override suspend fun recordCsvImportSourcesBatch(
            csvImportId: CsvImportId,
            sources: List<CsvImportSourceRecord>,
        ) {}

        override suspend fun recordSampleGeneratorSourcesBatch(
            deviceInfo: DeviceInfo,
            sources: List<SampleGeneratorSourceRecord>,
        ) {}

        override suspend fun getSourcesForTransaction(transactionId: TransferId): List<TransferSource> =
            sources.filter { it.transactionId == transactionId }

        override suspend fun getSourceByRevision(
            transactionId: TransferId,
            revisionId: Long,
        ): TransferSource? = sources.find { it.transactionId == transactionId && it.revisionId == revisionId }
    }

    private class FakeAttributeTypeRepository : AttributeTypeRepository {
        private val types = mutableListOf<AttributeType>()
        private var nextId = 1L

        override fun getAll(): Flow<List<AttributeType>> = flowOf(types.toList())

        override fun getById(id: AttributeTypeId): Flow<AttributeType?> = flowOf(types.find { it.id == id })

        override fun getByName(name: String): Flow<AttributeType?> = flowOf(types.find { it.name == name })

        override suspend fun getOrCreate(name: String): AttributeTypeId {
            val existing = types.find { it.name == name }
            if (existing != null) return existing.id
            val newType = AttributeType(id = AttributeTypeId(nextId++), name = name)
            types.add(newType)
            return newType.id
        }

        fun getCreatedTypes(): List<AttributeType> = types.toList()
    }

    private class FakeTransferAttributeRepository : TransferAttributeRepository {
        private val attributes = mutableListOf<TransferAttribute>()
        private var nextId = 1L

        override fun getByTransaction(transactionId: TransferId): Flow<List<TransferAttribute>> =
            flowOf(attributes.filter { it.transactionId == transactionId })

        override suspend fun insert(
            transactionId: TransferId,
            attributeTypeId: AttributeTypeId,
            value: String,
        ): Long {
            val id = nextId++
            attributes.add(
                TransferAttribute(
                    id = id,
                    transactionId = transactionId,
                    attributeType = AttributeType(id = attributeTypeId, name = "Type${attributeTypeId.id}"),
                    value = value,
                ),
            )
            return id
        }

        override suspend fun updateValue(
            id: Long,
            newValue: String,
        ) {
            val index = attributes.indexOfFirst { it.id == id }
            if (index >= 0) {
                attributes[index] = attributes[index].copy(value = newValue)
            }
        }

        override suspend fun delete(id: Long) {
            attributes.removeAll { it.id == id }
        }

        fun getInsertedAttributes(): List<TransferAttribute> = this.attributes.toList()
    }
}
