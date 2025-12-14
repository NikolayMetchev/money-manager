@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.moneymanager.database.audit

import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.RepositorySet
import com.moneymanager.database.createTestDatabaseLocation
import com.moneymanager.database.deleteTestDatabase
import com.moneymanager.di.AppComponent
import com.moneymanager.di.createTestAppComponentParams
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.domain.repository.AccountRepository
import com.moneymanager.domain.repository.CategoryRepository
import com.moneymanager.domain.repository.CurrencyRepository
import com.moneymanager.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

class AuditFunctionalTest {
    private lateinit var database: MoneyManagerDatabaseWrapper
    private lateinit var accountRepository: AccountRepository
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var currencyRepository: CurrencyRepository
    private lateinit var transactionRepository: TransactionRepository
    private lateinit var testDbLocation: com.moneymanager.database.DbLocation

    @BeforeTest
    fun setup() =
        runTest {
            testDbLocation = createTestDatabaseLocation()
            val component = AppComponent.create(createTestAppComponentParams())
            val databaseManager = component.databaseManager
            database = databaseManager.openDatabase(testDbLocation)
            val repositories = RepositorySet(database)

            accountRepository = repositories.accountRepository
            categoryRepository = repositories.categoryRepository
            currencyRepository = repositories.currencyRepository
            transactionRepository = repositories.transactionRepository
        }

    @AfterTest
    fun cleanup() {
        deleteTestDatabase(testDbLocation)
    }

    // ACCOUNT AUDIT TESTS

    @Test
    fun `account INSERT creates audit record with auditTypeId 1`() =
        runTest {
            val now = Clock.System.now()
            val account =
                Account(
                    id = AccountId(0),
                    name = "Test Checking",
                    openingDate = now,
                )

            val accountId = accountRepository.createAccount(account)

            val auditHistory = database.auditQueries.selectAuditHistoryForAccount(accountId.id).executeAsList()

            assertEquals(1, auditHistory.size, "Should have 1 audit record for INSERT")
            val auditRecord = auditHistory[0]
            assertEquals("INSERT", auditRecord.auditType)
            assertEquals(accountId.id, auditRecord.id)
            assertEquals("Test Checking", auditRecord.name)
            assertTrue(auditRecord.auditTimestamp > 0, "Audit timestamp should be set")
        }

    @Test
    fun `account UPDATE creates audit record with OLD values and auditTypeId 2`() =
        runTest {
            val now = Clock.System.now()
            val account =
                Account(
                    id = AccountId(0),
                    name = "Original Name",
                    openingDate = now,
                )

            val accountId = accountRepository.createAccount(account)

            accountRepository.updateAccount(
                Account(
                    id = accountId,
                    name = "Updated Name",
                    openingDate = now,
                ),
            )

            val auditHistory = database.auditQueries.selectAuditHistoryForAccount(accountId.id).executeAsList()

            assertEquals(2, auditHistory.size, "Should have 2 audit records (INSERT + UPDATE)")

            val updateAudit = auditHistory[0]
            assertEquals("UPDATE", updateAudit.auditType)
            assertEquals("Original Name", updateAudit.name, "Should store OLD value before update")

            val insertAudit = auditHistory[1]
            assertEquals("INSERT", insertAudit.auditType)
            assertEquals("Original Name", insertAudit.name)
        }

    @Test
    fun `account DELETE creates audit record with OLD values and auditTypeId 3`() =
        runTest {
            val now = Clock.System.now()
            val account =
                Account(
                    id = AccountId(0),
                    name = "To Be Deleted",
                    openingDate = now,
                )

            val accountId = accountRepository.createAccount(account)
            accountRepository.deleteAccount(accountId)

            val auditHistory = database.auditQueries.selectAuditHistoryForAccount(accountId.id).executeAsList()

            assertEquals(2, auditHistory.size, "Should have 2 audit records (INSERT + DELETE)")

            val deleteAudit = auditHistory[0]
            assertEquals("DELETE", deleteAudit.auditType)
            assertEquals("To Be Deleted", deleteAudit.name, "Should store OLD value before deletion")

            val insertAudit = auditHistory[1]
            assertEquals("INSERT", insertAudit.auditType)
        }

    @Test
    fun `account multiple operations create chronological audit trail`() =
        runTest {
            val now = Clock.System.now()
            val account =
                Account(
                    id = AccountId(0),
                    name = "Version 1",
                    openingDate = now,
                )

            val accountId = accountRepository.createAccount(account)

            accountRepository.updateAccount(account.copy(id = accountId, name = "Version 2"))
            accountRepository.updateAccount(account.copy(id = accountId, name = "Version 3"))
            accountRepository.updateAccount(account.copy(id = accountId, name = "Version 4"))

            val auditHistory = database.auditQueries.selectAuditHistoryForAccount(accountId.id).executeAsList()

            assertEquals(4, auditHistory.size, "Should have 4 audit records (1 INSERT + 3 UPDATEs)")
            assertEquals("UPDATE", auditHistory[0].auditType)
            assertEquals("Version 3", auditHistory[0].name)
            assertEquals("UPDATE", auditHistory[1].auditType)
            assertEquals("Version 2", auditHistory[1].name)
            assertEquals("UPDATE", auditHistory[2].auditType)
            assertEquals("Version 1", auditHistory[2].name)
            assertEquals("INSERT", auditHistory[3].auditType)
            assertEquals("Version 1", auditHistory[3].name)
        }

    // CURRENCY AUDIT TESTS

    @Test
    fun `currency INSERT creates audit record`() =
        runTest {
            val currencyId = currencyRepository.upsertCurrencyByCode("TST", "Test Currency")
            assertNotNull(currencyId)

            val auditHistory = database.auditQueries.selectAuditHistoryForCurrency(currencyId.toString()).executeAsList()

            assertEquals(1, auditHistory.size)
            assertEquals("INSERT", auditHistory[0].auditType)
            assertEquals("TST", auditHistory[0].code)
            assertEquals("Test Currency", auditHistory[0].name)
        }

    @Test
    fun `currency UPDATE creates audit record with OLD values`() =
        runTest {
            val currencyId = currencyRepository.upsertCurrencyByCode("TST", "Original Name")
            assertNotNull(currencyId)

            database.currencyQueries.update(
                code = "TST2",
                name = "Updated Name",
                scaleFactor = 100,
                id = currencyId.toString(),
            )

            val auditHistory = database.auditQueries.selectAuditHistoryForCurrency(currencyId.toString()).executeAsList()

            assertEquals(2, auditHistory.size)
            val updateAudit = auditHistory[0]
            assertEquals("UPDATE", updateAudit.auditType)
            assertEquals("TST", updateAudit.code, "Should store OLD code")
            assertEquals("Original Name", updateAudit.name, "Should store OLD name")
        }

    @Test
    fun `currency DELETE creates audit record`() =
        runTest {
            val currencyId = currencyRepository.upsertCurrencyByCode("TST", "Test Currency")
            assertNotNull(currencyId)

            database.currencyQueries.delete(currencyId.toString())

            val auditHistory = database.auditQueries.selectAuditHistoryForCurrency(currencyId.toString()).executeAsList()

            assertEquals(2, auditHistory.size)
            assertEquals("DELETE", auditHistory[0].auditType)
            assertEquals("TST", auditHistory[0].code)
        }

    // CATEGORY AUDIT TESTS

    @Test
    fun `category INSERT creates audit record`() =
        runTest {
            val category =
                Category(
                    id = 0,
                    name = "Test Category",
                    parentId = null,
                )

            val categoryId = categoryRepository.createCategory(category)

            val auditHistory = database.auditQueries.selectAuditHistoryForCategory(categoryId).executeAsList()

            assertEquals(1, auditHistory.size)
            assertEquals("INSERT", auditHistory[0].auditType)
            assertEquals("Test Category", auditHistory[0].name)
        }

    @Test
    fun `category UPDATE creates audit record with OLD values`() =
        runTest {
            val category =
                Category(
                    id = 0,
                    name = "Original Category",
                    parentId = null,
                )

            val categoryId = categoryRepository.createCategory(category)

            categoryRepository.updateCategory(
                Category(
                    id = categoryId,
                    name = "Updated Category",
                    parentId = null,
                ),
            )

            val auditHistory = database.auditQueries.selectAuditHistoryForCategory(categoryId).executeAsList()

            assertEquals(2, auditHistory.size)
            val updateAudit = auditHistory[0]
            assertEquals("UPDATE", updateAudit.auditType)
            assertEquals("Original Category", updateAudit.name, "Should store OLD name")
        }

    @Test
    fun `category DELETE creates audit record`() =
        runTest {
            val category =
                Category(
                    id = 0,
                    name = "To Be Deleted",
                    parentId = null,
                )

            val categoryId = categoryRepository.createCategory(category)
            categoryRepository.deleteCategory(categoryId)

            val auditHistory = database.auditQueries.selectAuditHistoryForCategory(categoryId).executeAsList()

            assertEquals(2, auditHistory.size)
            assertEquals("DELETE", auditHistory[0].auditType)
            assertEquals("To Be Deleted", auditHistory[0].name)
        }

    // TRANSFER AUDIT TESTS

    @Test
    fun `transfer INSERT creates audit record`() =
        runTest {
            val (sourceAccountId, targetAccountId, currency) = setupTransferPrerequisites()

            val transferId = TransferId(Uuid.random())
            val now = Clock.System.now()
            val description = "Test Transfer"
            val amount = Money.fromDisplayValue(100.0, currency)

            transactionRepository.createTransfer(
                Transfer(
                    id = transferId,
                    timestamp = now,
                    description = description,
                    sourceAccountId = sourceAccountId,
                    targetAccountId = targetAccountId,
                    amount = amount,
                ),
            )

            val auditHistory = database.auditQueries.selectAuditHistoryForTransfer(transferId.toString()).executeAsList()

            assertEquals(1, auditHistory.size)
            assertEquals("INSERT", auditHistory[0].auditType)
            assertEquals(description, auditHistory[0].description)
            assertEquals(amount.amount, auditHistory[0].amount)
        }

    @Test
    fun `transfer UPDATE creates audit record with OLD values`() =
        runTest {
            val (sourceAccountId, targetAccountId, currency) = setupTransferPrerequisites()

            val transferId = TransferId(Uuid.random())
            val now = Clock.System.now()
            val originalAmount = Money.fromDisplayValue(100.0, currency)
            val updatedAmount = Money.fromDisplayValue(200.0, currency)

            transactionRepository.createTransfer(
                Transfer(
                    id = transferId,
                    timestamp = now,
                    description = "Original Description",
                    sourceAccountId = sourceAccountId,
                    targetAccountId = targetAccountId,
                    amount = originalAmount,
                ),
            )

            transactionRepository.updateTransfer(
                Transfer(
                    id = transferId,
                    timestamp = now,
                    description = "Updated Description",
                    sourceAccountId = sourceAccountId,
                    targetAccountId = targetAccountId,
                    amount = updatedAmount,
                ),
            )

            val auditHistory = database.auditQueries.selectAuditHistoryForTransfer(transferId.toString()).executeAsList()

            assertEquals(2, auditHistory.size)
            val updateAudit = auditHistory[0]
            assertEquals("UPDATE", updateAudit.auditType)
            assertEquals("Original Description", updateAudit.description, "Should store OLD description")
            assertEquals(originalAmount.amount, updateAudit.amount, "Should store OLD amount")
        }

    @Test
    fun `transfer DELETE creates audit record`() =
        runTest {
            val (sourceAccountId, targetAccountId, currency) = setupTransferPrerequisites()

            val transferId = TransferId(Uuid.random())
            val now = Clock.System.now()

            transactionRepository.createTransfer(
                Transfer(
                    id = transferId,
                    timestamp = now,
                    description = "To Be Deleted",
                    sourceAccountId = sourceAccountId,
                    targetAccountId = targetAccountId,
                    amount = Money.fromDisplayValue(100.0, currency),
                ),
            )

            transactionRepository.deleteTransaction(transferId.id)

            val auditHistory = database.auditQueries.selectAuditHistoryForTransfer(transferId.toString()).executeAsList()

            assertEquals(2, auditHistory.size)
            assertEquals("DELETE", auditHistory[0].auditType)
            assertEquals("To Be Deleted", auditHistory[0].description)
        }

    private suspend fun setupTransferPrerequisites(): Triple<AccountId, AccountId, Currency> {
        val now = Clock.System.now()

        val sourceAccountId =
            accountRepository.createAccount(
                Account(
                    id = AccountId(0),
                    name = "Source Account",
                    openingDate = now,
                ),
            )

        val targetAccountId =
            accountRepository.createAccount(
                Account(
                    id = AccountId(0),
                    name = "Target Account",
                    openingDate = now,
                ),
            )

        val usdCurrency = currencyRepository.getCurrencyByCode("USD").first()
        assertNotNull(usdCurrency, "USD currency should exist from seed data")

        return Triple(sourceAccountId, targetAccountId, usdCurrency)
    }
}
