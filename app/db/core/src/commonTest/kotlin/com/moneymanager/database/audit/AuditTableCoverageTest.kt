package com.moneymanager.database.audit

import app.cash.sqldelight.db.QueryResult
import com.moneymanager.database.DbLocation
import com.moneymanager.database.MoneyManagerDatabaseWrapper
import com.moneymanager.database.RepositorySet
import com.moneymanager.di.AppComponent
import com.moneymanager.test.database.createTestAppComponentParams
import com.moneymanager.test.database.createTestDatabaseLocation
import com.moneymanager.test.database.deleteTestDatabase
import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class AuditTableCoverageTest: DbTest() {
    @Test
    fun `all regular tables have audit tables with matching schema`() {
        // Get all auditable tables using centralized utility function
        val regularTables = database.getAuditableTables()

        assertTrue(regularTables.isNotEmpty(), "Should have at least one regular table to audit")

        // For each regular table, verify audit table exists and has correct schema
        regularTables.forEach { tableName ->
            val auditTableName = "${tableName}_Audit"

            // Verify audit table exists
            var auditTableExists = false
            database.executeQuery(
                null,
                """
                SELECT name FROM sqlite_master
                WHERE type = 'table' AND name = '$auditTableName'
                """.trimIndent(),
                { cursor ->
                    auditTableExists = cursor.next().value
                    QueryResult.Unit
                },
                0,
            )

            if (!auditTableExists) {
                fail("Audit table $auditTableName is missing for table $tableName")
            }

            // Get columns using centralized utility function
            val mainTableColumns = database.getTableColumns(tableName)
            val auditTableColumns = database.getTableColumns(auditTableName)

            // Verify audit table has audit metadata columns
            assertTrue(
                auditTableColumns.contains("auditId"),
                "Audit table $auditTableName is missing auditId column",
            )
            assertTrue(
                auditTableColumns.contains("auditTimestamp"),
                "Audit table $auditTableName is missing auditTimestamp column",
            )
            assertTrue(
                auditTableColumns.contains("auditTypeId"),
                "Audit table $auditTableName is missing auditTypeId column",
            )

            // Verify audit table has all columns from main table
            mainTableColumns.forEach { columnName ->
                assertTrue(
                    auditTableColumns.contains(columnName),
                    "Audit table $auditTableName is missing column $columnName from main table $tableName",
                )
            }

            // Verify audit table has exactly: 3 audit columns + all main table columns
            val expectedColumnCount = 3 + mainTableColumns.size
            assertEquals(
                expectedColumnCount,
                auditTableColumns.size,
                "Audit table $auditTableName has unexpected column count. " +
                    "Expected $expectedColumnCount (3 audit + ${mainTableColumns.size} main), " +
                    "but got ${auditTableColumns.size}",
            )
        }
    }
}
