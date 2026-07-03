package com.moneymanager.database

import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A fresh database contains NO strategies or pass-through definitions: built-ins live in the
 * strategy-library catalog and are installed on demand from the UI (or, in tests, via the
 * installBuiltIn* helpers in test/app/db).
 */
class FreshDatabaseHasNoStrategiesTest : DbTest() {
    @Test
    fun `a fresh database has no strategies or pass-through definitions`() =
        runTest {
            assertTrue(
                repositories.csvImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .isEmpty(),
            )
            assertTrue(
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .isEmpty(),
            )
            assertTrue(
                repositories.passThroughAccountRepository
                    .getAll()
                    .first()
                    .isEmpty(),
            )
        }
}
