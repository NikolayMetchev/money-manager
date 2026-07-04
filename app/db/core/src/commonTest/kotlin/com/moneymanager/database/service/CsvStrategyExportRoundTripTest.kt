@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.service

import com.moneymanager.builtin.BuiltInCsvStrategies
import com.moneymanager.database.json.CsvStrategyExportCodec
import com.moneymanager.domain.model.AppVersion
import com.moneymanager.test.database.DbTest
import com.moneymanager.test.database.installBuiltInCsvStrategies
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Verifies that exporting a strategy to JSON and importing it back is lossless.
 *
 * Parameterised over every built-in CSV strategy, so any strategy added to
 * [com.moneymanager.builtin.BuiltInCsvStrategies.builtInCsvStrategies]
 * is covered automatically. Equality is checked on the export representation,
 * which is id-free, so freshly generated mapping/strategy ids don't interfere.
 */
class CsvStrategyExportRoundTripTest : DbTest() {
    @Test
    fun `every seeded csv strategy survives an export-import round trip unchanged`() =
        runTest {
            val service =
                CsvStrategyExportService(
                    accountRepository = repositories.accountRepository,
                    currencyRepository = repositories.currencyRepository,
                    categoryRepository = repositories.categoryRepository,
                    accountMappingRepository = repositories.accountMappingRepository,
                    importEngine = repositories.importEngine,
                )
            val appVersion = AppVersion("1.0.0-test")

            repositories.installBuiltInCsvStrategies()
            val strategies = repositories.csvImportStrategyRepository.getAllStrategies().first()
            assertTrue(strategies.isNotEmpty(), "Expected built-in CSV strategies to be installed")
            // Compare against the authoritative built-in list so new strategies are picked up automatically
            assertEquals(
                BuiltInCsvStrategies.builtInCsvStrategies(Clock.System.now()).map { it.name }.toSet(),
                strategies.map { it.name }.toSet(),
            )

            for (strategy in strategies) {
                val export = service.toExport(strategy, appVersion)
                val json = CsvStrategyExportCodec.encode(export)
                val decoded = CsvStrategyExportCodec.decode(json)

                val parsed = service.parseExport(decoded)
                assertTrue(
                    parsed.unresolvedReferences.isEmpty(),
                    "Strategy '${strategy.name}' has unresolved references after import: ${parsed.unresolvedReferences}",
                )

                val imported = service.createStrategyFromExport(decoded, resolutions = emptyMap()).strategy
                val reExport = service.toExport(imported, appVersion)
                assertEquals(
                    export,
                    reExport,
                    "Strategy '${strategy.name}' changed across an export-import round trip",
                )
            }
        }
}
