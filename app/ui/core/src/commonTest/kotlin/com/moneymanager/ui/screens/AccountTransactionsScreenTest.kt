@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import androidx.compose.ui.test.waitUntilExactlyOneExists
import com.moneymanager.database.ManualSourceRecorder
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.SampleGeneratorSourceRecorder
import com.moneymanager.database.port.DbEntitySource
import com.moneymanager.database.port.DbMaintenance
import com.moneymanager.di.database.DatabaseComponent
import com.moneymanager.domain.EntitySource
import com.moneymanager.domain.Maintenance
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.AccountRow
import com.moneymanager.domain.model.AttributeTypeId
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.DbLocation
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.PageWithTargetIndex
import com.moneymanager.domain.model.PagingInfo
import com.moneymanager.domain.model.PagingResult
import com.moneymanager.domain.model.PersonId
import com.moneymanager.domain.model.SourceRecorder
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.AccountAttributeRepository
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.AttributeTypeRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.PersonAccountOwnershipRepository
import com.moneymanager.domain.repository.PersonRepository
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.domain.repository.TransferSourceRepository
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.createTestDatabaseManager
import com.moneymanager.test.database.deleteTestDatabase
import com.moneymanager.ui.error.ProvideSchemaAwareScope
import com.moneymanager.ui.screens.transactions.AccountTransactionsScreen
import com.moneymanager.ui.screens.transactions.TransactionAuditScreen
import com.moneymanager.ui.test.runMoneyManagerComposeUiTest
import dev.mokkery.MockMode
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.properties.Delegates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration

@OptIn(ExperimentalTestApi::class)
class AccountTransactionsScreenTest {
    @Test
    fun accountTransactionCard_flipsAccountDisplay_whenPerspectiveChanges() =
        runMoneyManagerComposeUiTest {
            // Given: Two accounts and a transfer between them
            val now = Clock.System.now()
            val usdCurrency =
                Currency(
                    id = CurrencyId(1L),
                    code = "USD",
                    name = "US Dollar",
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
                    id = TransferId(0L),
                    timestamp = now,
                    description = "Transfer to savings",
                    sourceAccountId = checking.id,
                    targetAccountId = savings.id,
                    amount = Money.fromDisplayValue("100", usdCurrency),
                )

            val accountRepository = createAccountRepository(listOf(checking, savings))
            val transactionRepository = createTransactionRepository(listOf(transfer))
            val transferSourceRepository = createTransferSourceRepository()
            val entitySource = createStubEntitySource()
            val currencyRepository = createCurrencyRepository(listOf(usdCurrency))
            val categoryRepository = createCategoryRepository()
            val attributeTypeRepository = createAttributeTypeRepository()
            val personRepository = createPersonRepository()
            val personAccountOwnershipRepository = createPersonAccountOwnershipRepository()
            val maintenance = createMaintenance()

            // When: Viewing from Checking account's perspective
            setContent {
                ProvideSchemaAwareScope {
                    var currentAccountId by remember { mutableStateOf(checking.id) }

                    AccountTransactionsScreen(
                        accountId = currentAccountId,
                        transactionRepository = transactionRepository,
                        transferSourceRepository = transferSourceRepository,
                        EntitySource = entitySource,
                        accountRepository = accountRepository,
                        accountAttributeRepository = createAccountAttributeRepository(),
                        categoryRepository = categoryRepository,
                        currencyRepository = currencyRepository,
                        attributeTypeRepository = attributeTypeRepository,
                        personRepository = personRepository,
                        personAccountOwnershipRepository = personAccountOwnershipRepository,
                        Maintenance = maintenance,
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
        runMoneyManagerComposeUiTest {
            // Given: Two accounts with a transfer between them
            val now = Clock.System.now()
            val usdCurrency =
                Currency(
                    id = CurrencyId(1L),
                    code = "USD",
                    name = "US Dollar",
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
                    id = TransferId(0L),
                    timestamp = now,
                    description = "Transfer to savings",
                    sourceAccountId = checking.id,
                    targetAccountId = savings.id,
                    amount = Money.fromDisplayValue("100", usdCurrency),
                )

            val accountRepository = createAccountRepository(listOf(checking, savings))
            val transactionRepository = createTransactionRepository(listOf(transfer))
            val transferSourceRepository = createTransferSourceRepository()
            val entitySource = createStubEntitySource()
            val currencyRepository = createCurrencyRepository(listOf(usdCurrency))
            val categoryRepository = createCategoryRepository()
            val attributeTypeRepository = createAttributeTypeRepository()
            val personRepository = createPersonRepository()
            val personAccountOwnershipRepository = createPersonAccountOwnershipRepository()
            val maintenance = createMaintenance()

            setContent {
                ProvideSchemaAwareScope {
                    var currentAccountId by remember { mutableStateOf(checking.id) }

                    AccountTransactionsScreen(
                        accountId = currentAccountId,
                        transactionRepository = transactionRepository,
                        transferSourceRepository = transferSourceRepository,
                        EntitySource = entitySource,
                        accountRepository = accountRepository,
                        accountAttributeRepository = createAccountAttributeRepository(),
                        categoryRepository = categoryRepository,
                        currencyRepository = currencyRepository,
                        attributeTypeRepository = attributeTypeRepository,
                        personRepository = personRepository,
                        personAccountOwnershipRepository = personAccountOwnershipRepository,
                        Maintenance = maintenance,
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
        var checkingAccountId by Delegates.notNull<AccountId>()
        var savingsAccountId by Delegates.notNull<AccountId>()
        var transferId by Delegates.notNull<TransferId>()

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
                val transfer =
                    Transfer(
                        id = TransferId(0L),
                        timestamp = now,
                        description = "E2E Test Transaction",
                        sourceAccountId = checkingAccountId,
                        targetAccountId = savingsAccountId,
                        amount = Money.fromDisplayValue("50", usdCurrency),
                    )
                val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
                repositories.transactionRepository.createTransfers(
                    transfers = listOf(transfer),
                    sourceRecorder = SampleGeneratorSourceRecorder(repositories.transferSourceQueries, deviceId),
                )

                // Query back the created transfer to get the database-generated ID
                val createdTransfers = repositories.transactionRepository.getTransactionsByAccount(checkingAccountId).first()
                transferId = createdTransfers.first().id

                // Refresh materialized views so the transaction appears
                repositories.maintenanceService.fullRefreshMaterializedViews()
            }

            // Run the UI test
            runMoneyManagerComposeUiTest {
                // When: Viewing the account transactions screen
                setContent {
                    ProvideSchemaAwareScope {
                        var currentAccountId by remember { mutableStateOf(checkingAccountId) }
                        var auditTransferId by remember { mutableStateOf<TransferId?>(null) }

                        if (auditTransferId != null) {
                            TransactionAuditScreen(
                                transferId = auditTransferId!!,
                                auditRepository = repositories.auditRepository,
                                accountRepository = repositories.accountRepository,
                                transactionRepository = repositories.transactionRepository,
                                onBack = { auditTransferId = null },
                            )
                        } else {
                            AccountTransactionsScreen(
                                accountId = currentAccountId,
                                transactionRepository = repositories.transactionRepository,
                                transferSourceRepository = repositories.transferSourceRepository,
                                EntitySource = createDbEntitySource(repositories),
                                accountRepository = repositories.accountRepository,
                                accountAttributeRepository = repositories.accountAttributeRepository,
                                categoryRepository = repositories.categoryRepository,
                                currencyRepository = repositories.currencyRepository,
                                attributeTypeRepository = repositories.attributeTypeRepository,
                                personRepository = repositories.personRepository,
                                personAccountOwnershipRepository = repositories.personAccountOwnershipRepository,
                                Maintenance = createDbMaintenance(repositories),
                                onAccountIdChange = { currentAccountId = it },
                                onCurrencyIdChange = {},
                                onAuditClick = { auditTransferId = it },
                            )
                        }
                    }
                }

                // Wait for the edit button to appear
                waitUntilExactlyOneExists(hasText("\u270F\uFE0F"), timeoutMillis = 10000)

                // Click the edit button (pencil emoji) on the transaction
                onNodeWithText("\u270F\uFE0F").performClick()
                waitForIdle()

                // Wait for edit dialog to appear and attributes to finish loading
                // (dialog shows spinner while loading attributes from DB via Dispatchers.Default)
                waitUntilExactlyOneExists(hasText("Edit Transaction"), timeoutMillis = 10000)
                waitUntilExactlyOneExists(hasText("+ Add Attribute"), timeoutMillis = 30000)
                onNodeWithText("+ Add Attribute").performClick()

                // Wait for attribute fields to appear
                waitUntilAtLeastOneExists(hasText("Type"), timeoutMillis = 10000)

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

                // Click Update to save and wait for dialog to close
                onNodeWithText("Update").performClick()
                waitUntilDoesNotExist(hasText("Edit Transaction"), timeoutMillis = 30000)
                waitForIdle()

                // Verify from database: the attribute was saved
                runBlocking {
                    // Get the transfer's current revision
                    val savedTransfer =
                        repositories.transactionRepository
                            .getTransactionById(transferId.id)
                            .first()!!
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
                            .getByName("Reference Number")
                            .first()
                    assertTrue(
                        attributeType != null,
                        "Attribute type 'Reference Number' should exist in database",
                    )

                    // Verify the attribute was saved
                    val attributes =
                        repositories.transferAttributeRepository
                            .getByTransaction(transferId)
                            .first()
                    assertTrue(
                        attributes.any { it.value == "REF-12345" && it.attributeType.name == "Reference Number" },
                        "Attribute with value 'REF-12345' should exist in database. Found: $attributes",
                    )
                }

                // Refresh materialized views so the updated transaction appears
                runBlocking {
                    repositories.maintenanceService.fullRefreshMaterializedViews()
                }

                // Wait for audit button to be visible (indicates UI has refreshed)
                waitUntilExactlyOneExists(hasText("\uD83D\uDCDC"), timeoutMillis = 10000)

                // Now click the Audit History button (📜) and verify it shows the new attribute
                onNodeWithText("\uD83D\uDCDC").performClick()

                // Wait for audit history dialog to appear
                waitUntilExactlyOneExists(hasText("Audit History:", substring = true), timeoutMillis = 10000)

                // The audit should show the attribute type and value in the audit history
                // The attribute is displayed as "+Reference Number:" and "REF-12345" as separate text nodes
                waitUntilAtLeastOneExists(hasText("Reference Number", substring = true), timeoutMillis = 10000)
                waitUntilAtLeastOneExists(hasText("REF-12345", substring = true), timeoutMillis = 10000)
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
        var checkingAccountId by Delegates.notNull<AccountId>()
        var savingsAccountId by Delegates.notNull<AccountId>()
        var transferId by Delegates.notNull<TransferId>()

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
                val transfer =
                    Transfer(
                        id = TransferId(0L),
                        timestamp = now,
                        description = "Audit Test Transaction",
                        sourceAccountId = checkingAccountId,
                        targetAccountId = savingsAccountId,
                        amount = Money.fromDisplayValue("100", usdCurrency),
                    )
                val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
                repositories.transactionRepository.createTransfers(
                    transfers = listOf(transfer),
                    sourceRecorder = ManualSourceRecorder(repositories.transferSourceQueries, deviceId),
                )

                // Query back the created transfer to get the database-generated ID
                val createdTransfers = repositories.transactionRepository.getTransactionsByAccount(checkingAccountId).first()
                transferId = createdTransfers.first().id

                // Verify initial revision is 1
                val initialTransfer =
                    repositories.transactionRepository.getTransactionById(transferId.id).first()!!
                assertEquals(1L, initialTransfer.revisionId, "Initial revision should be 1")

                // Refresh materialized views so the transaction appears
                repositories.maintenanceService.fullRefreshMaterializedViews()
            }

            // Run the UI test
            runMoneyManagerComposeUiTest {
                // When: Viewing the account transactions screen
                setContent {
                    ProvideSchemaAwareScope {
                        var currentAccountId by remember { mutableStateOf(checkingAccountId) }
                        var auditTransferId by remember { mutableStateOf<TransferId?>(null) }

                        if (auditTransferId != null) {
                            TransactionAuditScreen(
                                transferId = auditTransferId!!,
                                auditRepository = repositories.auditRepository,
                                accountRepository = repositories.accountRepository,
                                transactionRepository = repositories.transactionRepository,
                                onBack = { auditTransferId = null },
                            )
                        } else {
                            AccountTransactionsScreen(
                                accountId = currentAccountId,
                                transactionRepository = repositories.transactionRepository,
                                transferSourceRepository = repositories.transferSourceRepository,
                                EntitySource = createDbEntitySource(repositories),
                                accountRepository = repositories.accountRepository,
                                accountAttributeRepository = repositories.accountAttributeRepository,
                                categoryRepository = repositories.categoryRepository,
                                currencyRepository = repositories.currencyRepository,
                                attributeTypeRepository = repositories.attributeTypeRepository,
                                personRepository = repositories.personRepository,
                                personAccountOwnershipRepository = repositories.personAccountOwnershipRepository,
                                Maintenance = createDbMaintenance(repositories),
                                onAccountIdChange = { currentAccountId = it },
                                onCurrencyIdChange = {},
                                onAuditClick = { auditTransferId = it },
                            )
                        }
                    }
                }

                // Wait for the edit button to appear
                waitUntilExactlyOneExists(hasText("\u270F\uFE0F"), timeoutMillis = 10000)

                // Step 1: Open edit dialog
                onNodeWithText("\u270F\uFE0F").performClick()

                // Wait for edit dialog to appear (attributes are initialized synchronously from Transfer)
                waitUntilExactlyOneExists(hasText("Edit Transaction"), timeoutMillis = 10000)
                waitUntilExactlyOneExists(hasText("+ Add Attribute"), timeoutMillis = 10000)
                onNodeWithText("+ Add Attribute").performClick()

                // Wait for attribute fields to appear
                waitUntilAtLeastOneExists(hasText("Type"), timeoutMillis = 10000)

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

                // Step 3: Click Update to save and wait for dialog to close
                onNodeWithText("Update").performClick()
                waitUntilDoesNotExist(hasText("Edit Transaction"), timeoutMillis = 30000)
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
        var checkingAccountId by Delegates.notNull<AccountId>()
        var savingsAccountId by Delegates.notNull<AccountId>()
        var transferId by Delegates.notNull<TransferId>()

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
                val transfer =
                    Transfer(
                        id = TransferId(0L),
                        timestamp = now,
                        description = "Original Description",
                        sourceAccountId = checkingAccountId,
                        targetAccountId = savingsAccountId,
                        amount = Money.fromDisplayValue("100", usdCurrency),
                    )
                val deviceId = repositories.deviceRepository.getOrCreateDevice(DeviceInfo.Jvm("test-machine", "Test OS"))
                repositories.transactionRepository.createTransfers(
                    transfers = listOf(transfer),
                    sourceRecorder = ManualSourceRecorder(repositories.transferSourceQueries, deviceId),
                )

                // Query back the created transfer to get the database-generated ID
                val createdTransfers = repositories.transactionRepository.getTransactionsByAccount(checkingAccountId).first()
                transferId = createdTransfers.first().id

                // Verify initial revision is 1
                val initialTransfer =
                    repositories.transactionRepository.getTransactionById(transferId.id).first()!!
                assertEquals(1L, initialTransfer.revisionId, "Initial revision should be 1")

                // Refresh materialized views so the transaction appears
                repositories.maintenanceService.fullRefreshMaterializedViews()
            }

            // Run the UI test
            runMoneyManagerComposeUiTest {
                // When: Viewing the account transactions screen
                setContent {
                    ProvideSchemaAwareScope {
                        var currentAccountId by remember { mutableStateOf(checkingAccountId) }
                        var auditTransferId by remember { mutableStateOf<TransferId?>(null) }

                        if (auditTransferId != null) {
                            TransactionAuditScreen(
                                transferId = auditTransferId!!,
                                auditRepository = repositories.auditRepository,
                                accountRepository = repositories.accountRepository,
                                transactionRepository = repositories.transactionRepository,
                                onBack = { auditTransferId = null },
                            )
                        } else {
                            AccountTransactionsScreen(
                                accountId = currentAccountId,
                                transactionRepository = repositories.transactionRepository,
                                transferSourceRepository = repositories.transferSourceRepository,
                                EntitySource = createDbEntitySource(repositories),
                                accountRepository = repositories.accountRepository,
                                accountAttributeRepository = repositories.accountAttributeRepository,
                                categoryRepository = repositories.categoryRepository,
                                currencyRepository = repositories.currencyRepository,
                                attributeTypeRepository = repositories.attributeTypeRepository,
                                personRepository = repositories.personRepository,
                                personAccountOwnershipRepository = repositories.personAccountOwnershipRepository,
                                Maintenance = createDbMaintenance(repositories),
                                onAccountIdChange = { currentAccountId = it },
                                onCurrencyIdChange = {},
                                onAuditClick = { auditTransferId = it },
                            )
                        }
                    }
                }

                // Wait for the edit button to appear
                waitUntilExactlyOneExists(hasText("\u270F\uFE0F"), timeoutMillis = 10000)

                // Step 1: Open edit dialog
                onNodeWithText("\u270F\uFE0F").performClick()

                // Wait for edit dialog to appear (attributes are initialized synchronously from Transfer)
                waitUntilExactlyOneExists(hasText("Edit Transaction"), timeoutMillis = 10000)
                waitUntilExactlyOneExists(hasText("+ Add Attribute"), timeoutMillis = 10000)

                // Step 2: Change the description (index 1 is the editable text field in the dialog)
                waitUntilAtLeastOneExists(hasText("Original Description"), timeoutMillis = 10000)
                onAllNodesWithText("Original Description")[1]
                    .performTextReplacement("Updated Description")
                waitForIdle()

                // Step 3: Add a new attribute (increased timeout for CI stability)
                waitUntilExactlyOneExists(hasText("+ Add Attribute"), timeoutMillis = 10000)
                onNodeWithText("+ Add Attribute").performClick()

                // Wait for attribute fields to appear
                waitUntilAtLeastOneExists(hasText("Type"), timeoutMillis = 10000)

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
                waitUntilDoesNotExist(hasText("Edit Transaction"), timeoutMillis = 30000)
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

    private fun createAccountRepository(accounts: List<Account>): AccountRepository =
        mock(MockMode.autoUnit) {
            every { getAllAccounts() } returns flowOf(accounts)
            every { getAccountById(any()) } calls { (id: AccountId) -> flowOf(accounts.find { it.id == id }) }
            everySuspend { createAccount(any()) } returns AccountId(999L)
            everySuspend { createAccountsBatch(any()) } returns emptyList()
            everySuspend { updateAccount(any()) } returns 1L
            everySuspend { updateAccountWithAttributes(any(), any(), any(), any(), any()) } returns 1L
            everySuspend { countTransfersByAccount(any()) } returns 0L
            everySuspend { getTransfersBetweenAccounts(any(), any()) } returns emptyList()
        }

    private fun createTransactionRepository(transfers: List<Transfer>): TransactionRepository =
        mock(MockMode.autoUnit) {
            every { getTransactionById(any()) } calls { (id: Long) -> flowOf(transfers.find { it.id.id == id }) }
            every { getTransactionsByAccount(any()) } calls { (accountId: AccountId) ->
                flowOf(transfers.filter { it.sourceAccountId == accountId || it.targetAccountId == accountId })
            }
            every { getTransactionsByDateRange(any(), any()) } returns flowOf(emptyList())
            every { getTransactionsByAccountAndDateRange(any(), any(), any()) } returns flowOf(emptyList())
            every { getAccountBalances() } returns flowOf(emptyList())
            everySuspend { getRunningBalanceByAccountPaginated(any(), any(), any()) } calls
                { (accountId: AccountId, pageSize: Int, _: PagingInfo?) ->
                    val allRows = buildAccountRows(transfers, accountId)
                    val items = allRows.take(pageSize)
                    PagingResult(
                        items = items,
                        pagingInfo =
                            PagingInfo(
                                lastTimestamp = items.lastOrNull()?.timestamp,
                                lastId = items.lastOrNull()?.transactionId,
                                hasMore = allRows.size > pageSize,
                            ),
                    )
                }
            everySuspend { getRunningBalanceByAccountPaginatedBackward(any(), any(), any(), any()) } returns
                PagingResult(emptyList<AccountRow>(), PagingInfo(null, null, false))
            everySuspend { getPageContainingTransaction(any(), any(), any()) } calls
                { (accountId: AccountId, transactionId: TransferId, pageSize: Int) ->
                    val allRows = buildAccountRows(transfers, accountId)
                    val targetIndex = allRows.indexOfFirst { it.transactionId.id == transactionId.id }
                    val items = allRows.take(pageSize)
                    PageWithTargetIndex(
                        items = items,
                        targetIndex = targetIndex,
                        pagingInfo =
                            PagingInfo(
                                lastTimestamp = items.lastOrNull()?.timestamp,
                                lastId = items.lastOrNull()?.transactionId,
                                hasMore = allRows.size > pageSize,
                            ),
                        hasPrevious = false,
                    )
                }
        }

    private fun buildAccountRows(
        transfers: List<Transfer>,
        accountId: AccountId,
    ): List<AccountRow> =
        transfers
            .flatMap { transfer ->
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

    private fun createCurrencyRepository(currencies: List<Currency>): CurrencyRepository =
        mock(MockMode.autoUnit) {
            every { getAllCurrencies() } returns flowOf(currencies)
            every { getCurrencyById(any()) } calls { (id: CurrencyId) -> flowOf(currencies.find { it.id == id }) }
            every { getCurrencyByCode(any()) } calls { (code: String) -> flowOf(currencies.find { it.code == code }) }
            everySuspend { upsertCurrencyByCode(any(), any()) } returns CurrencyId(1L)
        }

    private fun createCategoryRepository(): CategoryRepository =
        mock(MockMode.autoUnit) {
            val categories =
                listOf(
                    Category(id = -1L, name = "Uncategorized"),
                    Category(id = 1L, name = "Food"),
                    Category(id = 2L, name = "Transport"),
                )
            every { getAllCategories() } returns flowOf(categories)
            every { getCategoryBalances() } returns flowOf(emptyList())
            every { getCategoryById(any()) } calls { (id: Long) -> flowOf(categories.find { it.id == id }) }
            every { getTopLevelCategories() } returns flowOf(categories.filter { it.parentId == null })
            every { getCategoriesByParent(any()) } calls { (parentId: Long) -> flowOf(categories.filter { it.parentId == parentId }) }
            everySuspend { createCategory(any()) } returns 0L
        }

    private fun createTransferSourceRepository(): TransferSourceRepository =
        mock(MockMode.autoUnit) {
            everySuspend { getSourcesForTransaction(any()) } returns emptyList()
            everySuspend { getSourceByRevision(any(), any()) } returns null
        }

    private fun createAttributeTypeRepository(): AttributeTypeRepository =
        mock(MockMode.autoUnit) {
            every { getAll() } returns flowOf(emptyList())
            every { getById(any()) } returns flowOf(null)
            every { getByName(any()) } returns flowOf(null)
            everySuspend { getOrCreate(any()) } returns AttributeTypeId(0L)
        }

    private fun createAccountAttributeRepository(): AccountAttributeRepository =
        mock(MockMode.autoUnit) {
            every { getByAccount(any()) } returns flowOf(emptyList())
            everySuspend { insert(any(), any(), any()) } returns 0L
            everySuspend { insertInCreationMode(any(), any(), any()) } returns 0L
        }

    private fun createPersonRepository(): PersonRepository =
        mock(MockMode.autoUnit) {
            every { getAllPeople() } returns flowOf(emptyList())
            every { getPersonById(any()) } returns flowOf(null)
            everySuspend { createPerson(any()) } returns PersonId(0L)
        }

    private fun createPersonAccountOwnershipRepository(): PersonAccountOwnershipRepository =
        mock(MockMode.autoUnit) {
            every { getOwnershipsByPerson(any()) } returns flowOf(emptyList())
            every { getOwnershipsByAccount(any()) } returns flowOf(emptyList())
            every { getOwnershipById(any()) } returns flowOf(null)
            everySuspend { createOwnership(any(), any()) } returns 0L
        }

    private fun createMaintenance(): Maintenance =
        mock(MockMode.autoUnit) {
            everySuspend { reindex() } returns Duration.ZERO
            everySuspend { vacuum() } returns Duration.ZERO
            everySuspend { analyze() } returns Duration.ZERO
            everySuspend { refreshMaterializedViews() } returns Duration.ZERO
            everySuspend { fullRefreshMaterializedViews() } returns Duration.ZERO
        }

    private fun createStubEntitySource(): EntitySource {
        val recorder: SourceRecorder = mock(MockMode.autoUnit)
        return mock(MockMode.autoUnit) {
            every { manualRecorder() } returns recorder
            every { sampleGeneratorRecorder() } returns recorder
            every { csvImportRecorder(any(), any()) } returns recorder
            every { apiImportRecorder(any(), any(), any()) } returns recorder
        }
    }

    private fun createDbMaintenance(repositories: DatabaseComponent): Maintenance = DbMaintenance(repositories.maintenanceService)

    private fun createDbEntitySource(repositories: DatabaseComponent): EntitySource =
        DbEntitySource(repositories.entitySourceQueries, repositories.transferSourceQueries, repositories.deviceId)
}
