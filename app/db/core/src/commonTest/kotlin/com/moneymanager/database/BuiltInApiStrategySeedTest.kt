package com.moneymanager.database

import com.moneymanager.test.database.DbTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BuiltInApiStrategySeedTest : DbTest() {
    @Test
    fun `a fresh database seeds both built-in strategies`() =
        runTest {
            val names =
                repositories.apiImportStrategyRepository
                    .getAllStrategies()
                    .first()
                    .map { it.name }
                    .toSet()
            assertEquals(setOf("Monzo", "Wise"), names)
        }
}
