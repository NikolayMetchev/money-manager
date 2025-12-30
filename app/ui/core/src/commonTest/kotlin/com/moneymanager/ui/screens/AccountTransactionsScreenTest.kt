@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.moneymanager.database.DatabaseMaintenanceService
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
import com.moneymanager.domain.model.DeviceInfo
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.SourceType
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
import com.moneymanager.domain.repository.TransactionRepository
import com.moneymanager.domain.repository.TransferAttributeRepository
import com.moneymanager.domain.repository.TransferSourceRepository
import com.moneymanager.ui.error.ProvideSchemaAwareScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
    fun editTransaction_addNewAttribute_savesAttributeSuccessfully() =
        runComposeUiTest {
            // Given: A transaction with no attributes
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

            val transferId = TransferId(Uuid.random())
            val transfer =
                Transfer(
                    id = transferId,
                    timestamp = now,
                    description = "Test transaction",
                    sourceAccountId = checking.id,
                    targetAccountId = savings.id,
                    amount = Money.fromDisplayValue(50.0, usdCurrency),
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

            // When: Viewing the account transactions screen
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

            // Click the edit button (pencil emoji) on the transaction
            onNodeWithText("\u270F\uFE0F").performClick()
            waitForIdle()

            // Verify edit dialog is displayed
            onNodeWithText("Edit Transaction").assertIsDisplayed()

            // Wait for attributes to load
            mainClock.advanceTimeBy(100)
            waitForIdle()

            // Click "Add Attribute" button
            onNodeWithText("+ Add Attribute").performClick()
            waitForIdle()

            // Find and fill in the attribute type field (first empty text field after "Attributes")
            // The type field has label "Type"
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

            // Then: Verify the attribute was saved
            // Check that the batch insert was called with the new attribute
            assertTrue(
                transferAttributeRepository.batchInserts.isNotEmpty(),
                "Expected at least one batch insert to be called",
            )

            // Verify the attribute type was created
            val createdTypes = attributeTypeRepository.getCreatedTypes()
            assertTrue(
                createdTypes.any { it.name == "Reference Number" },
                "Expected 'Reference Number' attribute type to be created, but found: ${createdTypes.map { it.name }}",
            )

            // Verify the attribute value was saved correctly
            val lastBatchInsert = transferAttributeRepository.batchInserts.last()
            assertEquals(transferId, lastBatchInsert.first, "Batch insert should be for the correct transaction")
            assertTrue(
                lastBatchInsert.third.any { (_, value) -> value == "REF-12345" },
                "Expected attribute value 'REF-12345' to be saved, but found: ${lastBatchInsert.third}",
            )
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
            pagingInfo: com.moneymanager.domain.model.PagingInfo?,
        ): com.moneymanager.domain.model.PagingResult<AccountRow> {
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

            return com.moneymanager.domain.model.PagingResult(
                items = items,
                pagingInfo =
                    com.moneymanager.domain.model.PagingInfo(
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
            firstId: com.moneymanager.domain.model.TransactionId,
        ): com.moneymanager.domain.model.PagingResult<AccountRow> =
            com.moneymanager.domain.model.PagingResult(
                items = emptyList(),
                pagingInfo =
                    com.moneymanager.domain.model.PagingInfo(
                        lastTimestamp = null,
                        lastId = null,
                        hasMore = false,
                    ),
            )

        override suspend fun getPageContainingTransaction(
            accountId: AccountId,
            transactionId: TransferId,
            pageSize: Int,
        ): com.moneymanager.domain.model.PageWithTargetIndex<AccountRow> {
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

            return com.moneymanager.domain.model.PageWithTargetIndex(
                items = items,
                targetIndex = targetIndex,
                pagingInfo =
                    com.moneymanager.domain.model.PagingInfo(
                        lastTimestamp = items.lastOrNull()?.timestamp,
                        lastId = items.lastOrNull()?.transactionId,
                        hasMore = hasMore,
                    ),
                hasPrevious = false,
            )
        }

        override suspend fun createTransfer(transfer: Transfer) {}

        override suspend fun createTransfersBatch(transfers: List<Transfer>) {}

        override suspend fun updateTransfer(transfer: Transfer) {}

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

        // Track batch inserts for test verification
        val batchInserts = mutableListOf<Triple<TransferId, Long, List<Pair<AttributeTypeId, String>>>>()

        override fun getByTransactionAndRevision(
            transactionId: TransferId,
            revisionId: Long,
        ): Flow<List<TransferAttribute>> =
            flowOf(attributes.filter { it.transactionId == transactionId && it.revisionId == revisionId })

        override fun getAllByTransaction(transactionId: TransferId): Flow<List<TransferAttribute>> =
            flowOf(attributes.filter { it.transactionId == transactionId })

        override suspend fun insert(
            transactionId: TransferId,
            revisionId: Long,
            attributeTypeId: AttributeTypeId,
            value: String,
        ): Long {
            val id = nextId++
            attributes.add(
                TransferAttribute(
                    id = id,
                    transactionId = transactionId,
                    revisionId = revisionId,
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

        override suspend fun insertBatch(
            transactionId: TransferId,
            revisionId: Long,
            attributes: List<Pair<AttributeTypeId, String>>,
        ) {
            batchInserts.add(Triple(transactionId, revisionId, attributes))
            attributes.forEach { (typeId, value) ->
                insert(transactionId, revisionId, typeId, value)
            }
        }

        fun getInsertedAttributes(): List<TransferAttribute> = this.attributes.toList()
    }
}
