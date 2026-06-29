package com.moneymanager.database.seed.generator

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeedGeneratorTest {
    @Test
    fun `generates the three built-in API strategies each with SYSTEM provenance`() {
        val sql = generateApiStrategies()

        assertEquals(3, Regex("""INSERT INTO api_import_strategy\(""").findAll(sql).count())
        assertEquals(3, Regex("""INSERT OR IGNORE INTO api_import_strategy_source""").findAll(sql).count())
        assertTrue("'Monzo'" in sql && "'Wise'" in sql && "'Starling'" in sql, "all three strategy names present")
        // The fixed UUIDs must be emitted as-is so the seed is stable across builds.
        assertTrue(BuiltInStrategies.monzoStrategyId.toString() in sql)
    }

    @Test
    fun `generates the four built-in CSV strategies referencing the GBP currency id`() {
        val gbpId = 1L
        val sql = generateCsvStrategies(gbpId)

        assertEquals(4, Regex("""INSERT INTO csv_import_strategy\(""").findAll(sql).count())
        assertEquals(4, Regex("""INSERT OR IGNORE INTO csv_import_strategy_source""").findAll(sql).count())
        // The QIF strategies hard-code the GBP currency id passed in.
        assertTrue("\"currencyId\":$gbpId" in sql, "QIF strategy references the GBP currency id $gbpId")
    }

    @Test
    fun `main writes the generated sq files into the sqldelight package directory`() {
        val outDir =
            File.createTempFile("seed-gen", "").let {
                it.delete()
                it
            }
        try {
            main(arrayOf(outDir.absolutePath))
            val pkg = File(outDir, "com/moneymanager/database/sql/seed")
            assertTrue(File(pkg, "ZApiStrategies.sq").readText().contains("api_import_strategy"))
            assertTrue(File(pkg, "ZCsvStrategies.sq").readText().contains("csv_import_strategy"))
        } finally {
            outDir.deleteRecursively()
        }
    }
}
