package com.moneymanager.database.audit

import app.cash.sqldelight.db.QueryResult
import com.moneymanager.test.database.DbTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class AuditTableCoverageTest : DbTest() {
    @Test
    fun `all regular tables have audit tables with matching schema`() {
        // Get all auditable tables using centralized utility function
        val regularTables = database.getAuditableTables()

        assertTrue(regularTables.isNotEmpty(), "Should have at least one regular table to audit")

        // For each regular table, verify audit table exists and has correct schema
        regularTables.forEach { tableName ->
            val auditTableName = "${tableName}_audit"

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
                auditTableColumns.contains("id"),
                "Audit table $auditTableName is missing id column",
            )
            assertTrue(
                auditTableColumns.contains("audit_timestamp"),
                "Audit table $auditTableName is missing audit_timestamp column",
            )
            assertTrue(
                auditTableColumns.contains("audit_type_id"),
                "Audit table $auditTableName is missing audit_type_id column",
            )

            // Verify audit table has all columns from main table
            mainTableColumns.forEach { columnName ->
                assertTrue(
                    auditTableColumns.contains(columnName),
                    "Audit table $auditTableName is missing column $columnName from main table $tableName",
                )
            }

            // Verify audit table has at least: 3 audit columns + all main table columns
            // Some audit tables have extra denormalized columns (e.g. category_audit.parent_name)
            val minExpectedColumnCount = 3 + mainTableColumns.size
            assertTrue(
                auditTableColumns.size >= minExpectedColumnCount,
                "Audit table $auditTableName has unexpected column count. " +
                    "Expected at least $minExpectedColumnCount (3 audit + ${mainTableColumns.size} main), " +
                    "but got ${auditTableColumns.size}",
            )
        }
    }
}
