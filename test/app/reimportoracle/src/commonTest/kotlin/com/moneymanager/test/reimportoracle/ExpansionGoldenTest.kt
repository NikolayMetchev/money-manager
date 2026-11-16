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
            assertEquals(OracleCorpus.ALL.map { it.name }.toSet(), FRESH_IMPORT_GOLDENS.keys, "a scenario without a golden")
            for (scenario in OracleCorpus.ALL) {
                val actual =
                    OracleDatabase.with(scenario) {
                        import()
                        db.canonicalSnapshot().render()
                    }
                assertEquals(FRESH_IMPORT_GOLDENS.getValue(scenario.name), actual.trimEnd('\n'), scenario.name)
            }
        }
}
