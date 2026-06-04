package com.moneymanager.database

import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BuiltInApiStrategySeedTest : DbTest() {
    private suspend fun strategies() =
        repositories.apiImportStrategyRepository
            .getAllStrategies()
            .first()

    private suspend fun strategyNames(): Set<String> = strategies().map { it.name }.toSet()

    @Test
    fun `a fresh database has both built-in strategies`() =
        runTest {
            assertEquals(setOf("Monzo", "Wise"), strategyNames())
        }

    @Test
    fun `ensureBuiltInApiStrategies re-adds a missing strategy and is idempotent`() =
        runTest {
            // Simulate a database created before Wise existed.
            val wise = strategies().single { it.name == "Wise" }
            repositories.apiImportStrategyRepository.deleteStrategy(wise.id)
            assertEquals(setOf("Monzo"), strategyNames(), "Wise should be gone after delete")

            // The migration path: opening the database restores the missing built-in.
            DatabaseConfig.ensureBuiltInApiStrategies(database)
            assertEquals(setOf("Monzo", "Wise"), strategyNames(), "Wise should be restored")

            // Running again must not create duplicates.
            DatabaseConfig.ensureBuiltInApiStrategies(database)
            assertEquals(2, strategies().size, "Seeding must be idempotent")
        }
}
