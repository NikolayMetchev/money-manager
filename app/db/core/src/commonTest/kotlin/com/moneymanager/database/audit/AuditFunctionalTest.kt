@file:OptIn(kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

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
import kotlin.uuid.Uuid

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
    val id: String,
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
                Account_Audit.audit_id,
                Account_Audit.audit_timestamp,
                AuditType.name AS auditType,
                Account_Audit.id,
                Account_Audit.name
            FROM Account_Audit
            JOIN AuditType ON Account_Audit.audit_type_id = AuditType.id
            WHERE Account_Audit.id = $accountId
            ORDER BY Account_Audit.audit_timestamp DESC, Account_Audit.audit_id DESC
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
    private fun selectAuditHistoryForCurrency(currencyId: String): List<CurrencyAuditRecord> {
        val sql =
            """
            SELECT
                Currency_Audit.audit_id,
                Currency_Audit.audit_timestamp,
                AuditType.name AS auditType,
                Currency_Audit.id,
                Currency_Audit.code,
                Currency_Audit.name
            FROM Currency_Audit
            JOIN AuditType ON Currency_Audit.audit_type_id = AuditType.id
            WHERE Currency_Audit.id = '$currencyId'
            ORDER BY Currency_Audit.audit_timestamp DESC, Currency_Audit.audit_id DESC
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
                            id = cursor.getString(3)!!,
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
                Category_Audit.audit_id,
                Category_Audit.audit_timestamp,
                AuditType.name AS auditType,
                Category_Audit.id,
                Category_Audit.name
            FROM Category_Audit
            JOIN AuditType ON Category_Audit.audit_type_id = AuditType.id
            WHERE Category_Audit.id = $categoryId
            ORDER BY Category_Audit.audit_timestamp DESC, Category_Audit.audit_id DESC
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

            val auditHistory = selectAuditHistoryForCurrency(currencyId.toString())

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
                id = currencyId.toString(),
            )

            val auditHistory = selectAuditHistoryForCurrency(currencyId.toString())

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

            database.currencyQueries.delete(currencyId.toString())

            val auditHistory = selectAuditHistoryForCurrency(currencyId.toString())

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

            val transferId = TransferId(Uuid.random())
            val now = Clock.System.now()
            val description = "Test Transfer"
            val amount = Money.fromDisplayValue(100.0, currency)

            createTransfer(
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
            assertEquals("INSERT", auditHistory[0].audit_type)
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

            createTransfer(
                Transfer(
                    id = transferId,
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
                        id = transferId,
                        timestamp = now,
                        description = "Updated Description",
                        sourceAccountId = sourceAccountId,
                        targetAccountId = targetAccountId,
                        amount = updatedAmount,
                    ),
                deletedAttributeIds = emptySet(),
                updatedAttributes = emptyMap(),
                newAttributes = emptyList(),
                transactionId = transferId,
            )

            val auditHistory = database.auditQueries.selectAuditHistoryForTransfer(transferId.toString()).executeAsList()

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

            val transferId = TransferId(Uuid.random())
            val now = Clock.System.now()

            createTransfer(
                Transfer(
                    id = transferId,
                    timestamp = now,
                    description = "To Be Deleted",
                    sourceAccountId = sourceAccountId,
                    targetAccountId = targetAccountId,
                    amount = Money.fromDisplayValue(100.0, currency),
                ),
            )

            repositories.transactionRepository.deleteTransaction(transferId.id)

            val auditHistory = database.auditQueries.selectAuditHistoryForTransfer(transferId.toString()).executeAsList()

            assertEquals(2, auditHistory.size)
            assertEquals("DELETE", auditHistory[0].audit_type)
            assertEquals("To Be Deleted", auditHistory[0].description)
        }

    @Test
    fun `transfer UPDATE should increment revisionId in audit record`() =
        runTest {
            val (sourceAccountId, targetAccountId, currency) = setupTransferPrerequisites()

            val transferId = TransferId(Uuid.random())
            val now = Clock.System.now()

            // Create transfer (revisionId should be 1)
            createTransfer(
                Transfer(
                    id = transferId,
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
                        id = transferId,
                        timestamp = now,
                        description = "Updated Description",
                        sourceAccountId = sourceAccountId,
                        targetAccountId = targetAccountId,
                        amount = Money.fromDisplayValue(200.0, currency),
                    ),
                deletedAttributeIds = emptySet(),
                updatedAttributes = emptyMap(),
                newAttributes = emptyList(),
                transactionId = transferId,
            )

            val auditHistory = database.auditQueries.selectAuditHistoryForTransfer(transferId.toString()).executeAsList()

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

            val transferId = TransferId(Uuid.random())
            val now = Clock.System.now()

            // Create transfer (revisionId = 1)
            createTransfer(
                Transfer(
                    id = transferId,
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
                        id = transferId,
                        timestamp = now,
                        description = "Version 2",
                        sourceAccountId = sourceAccountId,
                        targetAccountId = targetAccountId,
                        amount = Money.fromDisplayValue(200.0, currency),
                    ),
                deletedAttributeIds = emptySet(),
                updatedAttributes = emptyMap(),
                newAttributes = emptyList(),
                transactionId = transferId,
            )

            // Second update (revisionId = 3)
            repositories.transactionRepository.updateTransfer(
                transfer =
                    Transfer(
                        id = transferId,
                        timestamp = now,
                        description = "Version 3",
                        sourceAccountId = sourceAccountId,
                        targetAccountId = targetAccountId,
                        amount = Money.fromDisplayValue(300.0, currency),
                    ),
                deletedAttributeIds = emptySet(),
                updatedAttributes = emptyMap(),
                newAttributes = emptyList(),
                transactionId = transferId,
            )

            val auditHistory = database.auditQueries.selectAuditHistoryForTransfer(transferId.toString()).executeAsList()

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
