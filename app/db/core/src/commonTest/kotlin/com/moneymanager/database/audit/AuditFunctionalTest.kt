@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.audit

import app.cash.sqldelight.db.QueryResult
import com.moneymanager.domain.model.Account
import com.moneymanager.domain.model.AccountId
import com.moneymanager.domain.model.Category
import com.moneymanager.domain.model.Currency
import com.moneymanager.domain.model.Money
import com.moneymanager.domain.model.Transfer
import com.moneymanager.domain.model.TransferId
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Data class for account audit history records (test-only).
 */
private data class AccountAuditRecord(
    val auditId: Long,
    val auditTimestamp: Long,
    val auditType: String,
    val id: Long,
    val name: String,
)

/**
 * Data class for currency audit history records (test-only).
 */
private data class CurrencyAuditRecord(
    val auditId: Long,
    val auditTimestamp: Long,
    val auditType: String,
    val id: Long,
    val code: String,
    val name: String,
)

/**
 * Data class for category audit history records (test-only).
 */
private data class CategoryAuditRecord(
    val auditId: Long,
    val auditTimestamp: Long,
    val auditType: String,
    val id: Long,
    val name: String,
)

class AuditFunctionalTest : DbTest() {
    /**
     * Test-only query: selectAuditHistoryForAccount
     * Retrieves audit history for a specific account using raw SQL.
     */
    private fun selectAuditHistoryForAccount(accountId: Long): List<AccountAuditRecord> {
        val sql =
            """
            SELECT
                account_audit.audit_id,
                account_audit.audit_timestamp,
                audit_type.name AS auditType,
                account_audit.id,
                account_audit.name
            FROM account_audit
            JOIN audit_type ON account_audit.audit_type_id = audit_type.id
            WHERE account_audit.id = $accountId
            ORDER BY account_audit.audit_timestamp DESC, account_audit.audit_id DESC
            """.trimIndent()

        return database.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                val results = mutableListOf<AccountAuditRecord>()
                while (cursor.next().value) {
                    results.add(
                        AccountAuditRecord(
                            auditId = cursor.getLong(0)!!,
                            auditTimestamp = cursor.getLong(1)!!,
                            auditType = cursor.getString(2)!!,
                            id = cursor.getLong(3)!!,
                            name = cursor.getString(4)!!,
                        ),
                    )
                }
                QueryResult.Value(results)
            },
            parameters = 0,
        ).value
    }

    /**
     * Test-only query: selectAuditHistoryForCurrency
     * Retrieves audit history for a specific currency using raw SQL.
     */
    private fun selectAuditHistoryForCurrency(currencyId: Long): List<CurrencyAuditRecord> {
        val sql =
            """
            SELECT
                currency_audit.audit_id,
                currency_audit.audit_timestamp,
                audit_type.name AS auditType,
                currency_audit.id,
                currency_audit.code,
                currency_audit.name
            FROM currency_audit
            JOIN audit_type ON currency_audit.audit_type_id = audit_type.id
            WHERE currency_audit.id = $currencyId
            ORDER BY currency_audit.audit_timestamp DESC, currency_audit.audit_id DESC
            """.trimIndent()

        return database.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                val results = mutableListOf<CurrencyAuditRecord>()
                while (cursor.next().value) {
                    results.add(
                        CurrencyAuditRecord(
                            auditId = cursor.getLong(0)!!,
                            auditTimestamp = cursor.getLong(1)!!,
                            auditType = cursor.getString(2)!!,
                            id = cursor.getLong(3)!!,
                            code = cursor.getString(4)!!,
                            name = cursor.getString(5)!!,
                        ),
                    )
                }
                QueryResult.Value(results)
            },
            parameters = 0,
        ).value
    }

    /**
     * Test-only query: selectAuditHistoryForCategory
     * Retrieves audit history for a specific category using raw SQL.
     */
    private fun selectAuditHistoryForCategory(categoryId: Long): List<CategoryAuditRecord> {
        val sql =
            """
            SELECT
                category_audit.audit_id,
                category_audit.audit_timestamp,
                audit_type.name AS auditType,
                category_audit.id,
                category_audit.name
            FROM category_audit
            JOIN audit_type ON category_audit.audit_type_id = audit_type.id
            WHERE category_audit.id = $categoryId
            ORDER BY category_audit.audit_timestamp DESC, category_audit.audit_id DESC
            """.trimIndent()

        return database.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                val results = mutableListOf<CategoryAuditRecord>()
                while (cursor.next().value) {
                    results.add(
                        CategoryAuditRecord(
                            auditId = cursor.getLong(0)!!,
                            auditTimestamp = cursor.getLong(1)!!,
                            auditType = cursor.getString(2)!!,
                            id = cursor.getLong(3)!!,
                            name = cursor.getString(4)!!,
                        ),
                    )
                }
                QueryResult.Value(results)
            },
            parameters = 0,
        ).value
    }

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

            val accountId = repositories.accountRepository.createAccount(account)

            val auditHistory = selectAuditHistoryForAccount(accountId.id)

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

            val accountId = repositories.accountRepository.createAccount(account)

            repositories.accountRepository.updateAccount(
                Account(
                    id = accountId,
                    name = "Updated Name",
                    openingDate = now,
                ),
            )

            val auditHistory = selectAuditHistoryForAccount(accountId.id)

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

            val accountId = repositories.accountRepository.createAccount(account)
            repositories.accountRepository.deleteAccount(accountId)

            val auditHistory = selectAuditHistoryForAccount(accountId.id)

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

            val accountId = repositories.accountRepository.createAccount(account)

            repositories.accountRepository.updateAccount(account.copy(id = accountId, name = "Version 2"))
            repositories.accountRepository.updateAccount(account.copy(id = accountId, name = "Version 3"))
            repositories.accountRepository.updateAccount(account.copy(id = accountId, name = "Version 4"))

            val auditHistory = selectAuditHistoryForAccount(accountId.id)

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
            val currencyId = repositories.currencyRepository.upsertCurrencyByCode("TST", "Test Currency")
            assertNotNull(currencyId)

            val auditHistory = selectAuditHistoryForCurrency(currencyId.id)

            assertEquals(1, auditHistory.size)
            assertEquals("INSERT", auditHistory[0].auditType)
            assertEquals("TST", auditHistory[0].code)
            assertEquals("Test Currency", auditHistory[0].name)
        }

    @Test
    fun `currency UPDATE creates audit record with OLD values`() =
        runTest {
            val currencyId = repositories.currencyRepository.upsertCurrencyByCode("TST", "Original Name")
            assertNotNull(currencyId)

            database.currencyQueries.update(
                code = "TST2",
                name = "Updated Name",
                scale_factor = 100,
                id = currencyId.id,
            )

            val auditHistory = selectAuditHistoryForCurrency(currencyId.id)

            assertEquals(2, auditHistory.size)
            val updateAudit = auditHistory[0]
            assertEquals("UPDATE", updateAudit.auditType)
            assertEquals("TST", updateAudit.code, "Should store OLD code")
            assertEquals("Original Name", updateAudit.name, "Should store OLD name")
        }

    @Test
    fun `currency DELETE creates audit record`() =
        runTest {
            val currencyId = repositories.currencyRepository.upsertCurrencyByCode("TST", "Test Currency")
            assertNotNull(currencyId)

            database.currencyQueries.delete(currencyId.id)

            val auditHistory = selectAuditHistoryForCurrency(currencyId.id)

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

            val categoryId = repositories.categoryRepository.createCategory(category)

            val auditHistory = selectAuditHistoryForCategory(categoryId)

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

            val categoryId = repositories.categoryRepository.createCategory(category)

            repositories.categoryRepository.updateCategory(
                Category(
                    id = categoryId,
                    name = "Updated Category",
                    parentId = null,
                ),
            )

            val auditHistory = selectAuditHistoryForCategory(categoryId)

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

            val categoryId = repositories.categoryRepository.createCategory(category)
            repositories.categoryRepository.deleteCategory(categoryId)

            val auditHistory = selectAuditHistoryForCategory(categoryId)

            assertEquals(2, auditHistory.size)
            assertEquals("DELETE", auditHistory[0].auditType)
            assertEquals("To Be Deleted", auditHistory[0].name)
        }

    // TRANSFER AUDIT TESTS

    @Test
    fun `transfer INSERT creates audit record`() =
        runTest {
            val (sourceAccountId, targetAccountId, currency) = setupTransferPrerequisites()

            val now = Clock.System.now()
            val description = "Test Transfer"
            val amount = Money.fromDisplayValue(100.0, currency)

            val transfer =
                createTransfer(
                    Transfer(
                        id = TransferId(0L),
                        timestamp = now,
                        description = description,
                        sourceAccountId = sourceAccountId,
                        targetAccountId = targetAccountId,
                        amount = amount,
                    ),
                )

            val auditHistory = database.auditQueries.selectAuditHistoryForTransfer(transfer.id.id).executeAsList()

            assertEquals(1, auditHistory.size)
            assertEquals("INSERT", auditHistory[0].audit_type)
            assertEquals(description, auditHistory[0].description)
            assertEquals(amount.amount, auditHistory[0].amount)
        }

    @Test
    fun `transfer UPDATE creates audit record with OLD values`() =
        runTest {
            val (sourceAccountId, targetAccountId, currency) = setupTransferPrerequisites()

            val now = Clock.System.now()
            val originalAmount = Money.fromDisplayValue(100.0, currency)
            val updatedAmount = Money.fromDisplayValue(200.0, currency)

            val transfer =
                createTransfer(
                    Transfer(
                        id = TransferId(0L),
                        timestamp = now,
                        description = "Original Description",
                        sourceAccountId = sourceAccountId,
                        targetAccountId = targetAccountId,
                        amount = originalAmount,
                    ),
                )

            repositories.transactionRepository.updateTransfer(
                transfer =
                    Transfer(
                        id = transfer.id,
                        timestamp = now,
                        description = "Updated Description",
                        sourceAccountId = sourceAccountId,
                        targetAccountId = targetAccountId,
                        amount = updatedAmount,
                    ),
                deletedAttributeIds = emptySet(),
                updatedAttributes = emptyMap(),
                newAttributes = emptyList(),
                transactionId = transfer.id,
            )

            val auditHistory = database.auditQueries.selectAuditHistoryForTransfer(transfer.id.id).executeAsList()

            assertEquals(2, auditHistory.size)
            val updateAudit = auditHistory[0]
            assertEquals("UPDATE", updateAudit.audit_type)
            assertEquals("Original Description", updateAudit.description, "Should store OLD description")
            assertEquals(originalAmount.amount, updateAudit.amount, "Should store OLD amount")
        }

    @Test
    fun `transfer DELETE creates audit record`() =
        runTest {
            val (sourceAccountId, targetAccountId, currency) = setupTransferPrerequisites()

            val now = Clock.System.now()

            val transfer =
                createTransfer(
                    Transfer(
                        id = TransferId(0L),
                        timestamp = now,
                        description = "To Be Deleted",
                        sourceAccountId = sourceAccountId,
                        targetAccountId = targetAccountId,
                        amount = Money.fromDisplayValue(100.0, currency),
                    ),
                )

            repositories.transactionRepository.deleteTransaction(transfer.id.id)

            val auditHistory = database.auditQueries.selectAuditHistoryForTransfer(transfer.id.id).executeAsList()

            assertEquals(2, auditHistory.size)
            assertEquals("DELETE", auditHistory[0].audit_type)
            assertEquals("To Be Deleted", auditHistory[0].description)
        }

    @Test
    fun `transfer UPDATE should increment revisionId in audit record`() =
        runTest {
            val (sourceAccountId, targetAccountId, currency) = setupTransferPrerequisites()

            val now = Clock.System.now()

            // Create transfer (revisionId should be 1)
            val transfer =
                createTransfer(
                    Transfer(
                        id = TransferId(0L),
                        timestamp = now,
                        description = "Original Description",
                        sourceAccountId = sourceAccountId,
                        targetAccountId = targetAccountId,
                        amount = Money.fromDisplayValue(100.0, currency),
                    ),
                )

            // Update transfer (revisionId should be 2)
            repositories.transactionRepository.updateTransfer(
                transfer =
                    Transfer(
                        id = transfer.id,
                        timestamp = now,
                        description = "Updated Description",
                        sourceAccountId = sourceAccountId,
                        targetAccountId = targetAccountId,
                        amount = Money.fromDisplayValue(200.0, currency),
                    ),
                deletedAttributeIds = emptySet(),
                updatedAttributes = emptyMap(),
                newAttributes = emptyList(),
                transactionId = transfer.id,
            )

            val auditHistory = database.auditQueries.selectAuditHistoryForTransfer(transfer.id.id).executeAsList()

            assertEquals(2, auditHistory.size, "Should have 2 audit records (INSERT + UPDATE)")

            // Most recent first (UPDATE)
            val updateAudit = auditHistory[0]
            assertEquals("UPDATE", updateAudit.audit_type)
            // Bug: UPDATE trigger captures OLD.revisionId (1) instead of NEW.revisionId (2)
            // This test will fail until the trigger is fixed
            assertEquals(2L, updateAudit.revision_id, "UPDATE audit should have revisionId 2 (after increment)")

            // INSERT entry
            val insertAudit = auditHistory[1]
            assertEquals("INSERT", insertAudit.audit_type)
            assertEquals(1L, insertAudit.revision_id, "INSERT audit should have revisionId 1")
        }

    @Test
    fun `transfer multiple UPDATEs should increment revisionId each time`() =
        runTest {
            val (sourceAccountId, targetAccountId, currency) = setupTransferPrerequisites()

            val now = Clock.System.now()

            // Create transfer (revisionId = 1)
            val transfer =
                createTransfer(
                    Transfer(
                        id = TransferId(0L),
                        timestamp = now,
                        description = "Version 1",
                        sourceAccountId = sourceAccountId,
                        targetAccountId = targetAccountId,
                        amount = Money.fromDisplayValue(100.0, currency),
                    ),
                )

            // First update (revisionId = 2)
            repositories.transactionRepository.updateTransfer(
                transfer =
                    Transfer(
                        id = transfer.id,
                        timestamp = now,
                        description = "Version 2",
                        sourceAccountId = sourceAccountId,
                        targetAccountId = targetAccountId,
                        amount = Money.fromDisplayValue(200.0, currency),
                    ),
                deletedAttributeIds = emptySet(),
                updatedAttributes = emptyMap(),
                newAttributes = emptyList(),
                transactionId = transfer.id,
            )

            // Second update (revisionId = 3)
            repositories.transactionRepository.updateTransfer(
                transfer =
                    Transfer(
                        id = transfer.id,
                        timestamp = now,
                        description = "Version 3",
                        sourceAccountId = sourceAccountId,
                        targetAccountId = targetAccountId,
                        amount = Money.fromDisplayValue(300.0, currency),
                    ),
                deletedAttributeIds = emptySet(),
                updatedAttributes = emptyMap(),
                newAttributes = emptyList(),
                transactionId = transfer.id,
            )

            val auditHistory = database.auditQueries.selectAuditHistoryForTransfer(transfer.id.id).executeAsList()

            assertEquals(3, auditHistory.size, "Should have 3 audit records (INSERT + 2 UPDATEs)")

            // Most recent first - second UPDATE (revisionId = 3)
            assertEquals("UPDATE", auditHistory[0].audit_type)
            assertEquals(3L, auditHistory[0].revision_id, "Second UPDATE should have revisionId 3")

            // First UPDATE (revisionId = 2)
            assertEquals("UPDATE", auditHistory[1].audit_type)
            assertEquals(2L, auditHistory[1].revision_id, "First UPDATE should have revisionId 2")

            // INSERT (revisionId = 1)
            assertEquals("INSERT", auditHistory[2].audit_type)
            assertEquals(1L, auditHistory[2].revision_id, "INSERT should have revisionId 1")
        }

    private suspend fun setupTransferPrerequisites(): Triple<AccountId, AccountId, Currency> {
        val now = Clock.System.now()

        val sourceAccountId =
            repositories.accountRepository.createAccount(
                Account(
                    id = AccountId(0),
                    name = "Source Account",
                    openingDate = now,
                ),
            )

        val targetAccountId =
            repositories.accountRepository.createAccount(
                Account(
                    id = AccountId(0),
                    name = "Target Account",
                    openingDate = now,
                ),
            )

        val usdCurrency = repositories.currencyRepository.getCurrencyByCode("USD").first()
        assertNotNull(usdCurrency, "USD currency should exist from seed data")

        return Triple(sourceAccountId, targetAccountId, usdCurrency)
    }
}
