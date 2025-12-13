package com.moneymanager.database.audit

import com.moneymanager.database.RepositorySet
import com.moneymanager.database.createTestDatabaseLocation
import com.moneymanager.database.deleteTestDatabase
import com.moneymanager.database.sql.MoneyManagerDatabase
import com.moneymanager.di.AppComponent
import com.moneymanager.di.createTestAppComponentParams
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.fail

class AuditTableCoverageTest {
    private lateinit var database: MoneyManagerDatabase
    private lateinit var testDbLocation: com.moneymanager.database.DbLocation

    @BeforeTest
    fun setup() =
        runTest {
            testDbLocation = createTestDatabaseLocation()
            val component = AppComponent.create(createTestAppComponentParams())
            val databaseManager = component.databaseManager
            database = databaseManager.openDatabase(testDbLocation)
            RepositorySet(database)
        }

    @AfterTest
    fun cleanup() {
        deleteTestDatabase(testDbLocation)
    }

    @Test
    fun `all regular tables have audit tables`() {
        // Verify audit tables exist by using the generated queries
        // If a query executes without error, the audit table exists

        try {
            // Account_Audit
            database.auditQueries.selectAuditHistoryForAccount(1).executeAsList()

            // Currency_Audit
            database.auditQueries.selectAuditHistoryForCurrency("test").executeAsList()

            // Category_Audit
            database.auditQueries.selectAuditHistoryForCategory(1).executeAsList()

            // Transfer_Audit
            database.auditQueries.selectAuditHistoryForTransfer("test").executeAsList()

            // If we get here, all audit tables exist and queries work
        } catch (e: Exception) {
            if (e.message?.contains("no such table", ignoreCase = true) == true) {
                fail("One or more audit tables are missing: ${e.message}")
            } else {
                // Re-throw if it's a different error
                throw e
            }
        }
    }
}
