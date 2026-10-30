package com.moneymanager.schemaspy

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertTrue

class SchemaSpyDatabaseCreatorTest {
    @Test
    fun `the schema is in the database file without its sidecars`() =
        runTest {
            val dir = Files.createTempDirectory("schemaspy")
            val created = dir.resolve("created.db")

            main(arrayOf(created.toString()))

            // The schemaspy_docs rule hands SchemaSpy the main file alone, so read a copy without the WAL.
            val copy = Files.createDirectory(dir.resolve("copy")).resolve("schemaspy.db")
            Files.copy(created, copy)
            val tables =
                DriverManager.getConnection("jdbc:sqlite:$copy").use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT count(*) FROM sqlite_master WHERE type = 'table'").use {
                            it.next()
                            it.getInt(1)
                        }
                    }
                }
            assertTrue(tables > 0, "Expected the schema's tables in the database file, found $tables")
        }
}
