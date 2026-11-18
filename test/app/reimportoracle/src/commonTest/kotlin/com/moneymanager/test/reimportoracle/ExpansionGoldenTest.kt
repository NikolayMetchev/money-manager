package com.moneymanager.test.reimportoracle

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/**
 * Every oracle scenario imports exactly as [FRESH_IMPORT_GOLDENS] records: the expander's fees, pass-through chains and
 * conversions (#1422), and the twins the matcher joins (#1428).
 */
class ExpansionGoldenTest {
    @Test
    fun every_scenario_imports_as_before_the_expander() =
        runTest(timeout = 30.minutes) {
            // Every scenario runs before any fails, so one run shows every divergence.
            val mismatches =
                OracleCorpus.ALL.mapNotNull { scenario ->
                    val actual =
                        OracleDatabase
                            .with(scenario) {
                                import()
                                db.canonicalSnapshot().render()
                            }.trimEnd('\n')
                    (scenario.name to actual).takeIf { actual != FRESH_IMPORT_GOLDENS[scenario.name] }
                }
            mismatches.forEach { (name, actual) -> println("=== GOLDEN $name\n$actual\n=== END") }
            assertEquals(OracleCorpus.ALL.map { it.name }.toSet(), FRESH_IMPORT_GOLDENS.keys, "a scenario without a golden")
            mismatches.firstOrNull()?.let { (name, actual) -> assertEquals(FRESH_IMPORT_GOLDENS.getValue(name), actual, name) }
        }
}
