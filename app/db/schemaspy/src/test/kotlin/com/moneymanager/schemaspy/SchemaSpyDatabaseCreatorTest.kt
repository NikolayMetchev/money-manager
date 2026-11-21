package com.moneymanager.schemaspy

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertTrue

class SchemaSpyDatabaseCreatorTest {
    @Test
    fun `the schema is in the database file without its sidecars`() =
        runTest {
            val dir = newTempDirectory()
            val created = dir.resolve("created.db")

            main(arrayOf(created.toString()))

            val tables = tableCountOfSidecarFreeCopy(dir, created)
            assertTrue(tables > 0, "Expected the schema's tables in the database file, found $tables")
        }

    private fun newTempDirectory(): Path = Files.createTempDirectory("schemaspy")

    // The schemaspy_docs rule hands SchemaSpy the main file alone, so read a copy without the WAL.
    private fun tableCountOfSidecarFreeCopy(
        dir: Path,
        created: Path,
    ): Int {
        val copy = Files.createDirectory(dir.resolve("copy")).resolve("schemaspy.db")
        Files.copy(created, copy)
        return DriverManager.getConnection("jdbc:sqlite:$copy").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM sqlite_master WHERE type = 'table'").use {
                    it.next()
                    it.getInt(1)
                }
            }
        }
    }
}
