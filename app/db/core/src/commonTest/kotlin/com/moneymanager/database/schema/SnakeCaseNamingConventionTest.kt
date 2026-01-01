package com.moneymanager.database.schema

import app.cash.sqldelight.db.QueryResult
import com.moneymanager.test.database.DbTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Validates that all database tables and views use snake_case naming for columns.
 * This ensures consistency across the schema and compatibility with Mappie's
 * automatic case conversion (snake_case in DB -> camelCase in domain models).
 */
class SnakeCaseNamingConventionTest : DbTest() {
    // Pattern: lowercase letters/digits with underscores, must start with letter
    private val snakeCasePattern = Regex("^[a-z][a-z0-9]*(_[a-z0-9]+)*$")

    // Tables/views to exclude from validation (system or dynamically created)
    private val excludedNames =
        setOf(
            "sqlite_sequence",
            "_import_batch",
            "_creation_mode",
        )

    // Prefix for dynamically created CSV import tables
    private val dynamicTablePrefix = "csv_import_"

    @Test
    fun `all table and view names use snake_case naming`() {
        val violations = mutableListOf<String>()

        // Get all tables and views
        val tablesAndViews = getTablesAndViews()

        assertTrue(tablesAndViews.isNotEmpty(), "Should have at least one table or view")

        tablesAndViews.forEach { name ->
            if (!snakeCasePattern.matches(name)) {
                violations.add(name)
            }
        }

        if (violations.isNotEmpty()) {
            fail(
                "Found ${violations.size} table/view name(s) not using snake_case naming:\n" +
                    violations.sorted().joinToString("\n") { "  - $it" },
            )
        }
    }

    @Test
    fun `all table and view columns use snake_case naming`() {
        val violations = mutableListOf<String>()

        // Get all tables and views
        val tablesAndViews = getTablesAndViews()

        assertTrue(tablesAndViews.isNotEmpty(), "Should have at least one table or view")

        tablesAndViews.forEach { name ->
            val columns = database.getTableColumns(name)
            columns.forEach { column ->
                if (!snakeCasePattern.matches(column)) {
                    violations.add("$name.$column")
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail(
                "Found ${violations.size} column(s) not using snake_case naming:\n" +
                    violations.sorted().joinToString("\n") { "  - $it" },
            )
        }
    }

    private fun getTablesAndViews(): List<String> {
        val names = mutableListOf<String>()
        database.executeQuery(
            null,
            """
            SELECT name FROM sqlite_master
            WHERE type IN ('table', 'view')
            AND name NOT LIKE 'sqlite_%'
            ORDER BY type, name
            """.trimIndent(),
            { cursor ->
                while (cursor.next().value) {
                    val name = cursor.getString(0) ?: continue
                    if (name !in excludedNames && !name.startsWith(dynamicTablePrefix)) {
                        names.add(name)
                    }
                }
                QueryResult.Unit
            },
            0,
        )
        return names
    }
}
