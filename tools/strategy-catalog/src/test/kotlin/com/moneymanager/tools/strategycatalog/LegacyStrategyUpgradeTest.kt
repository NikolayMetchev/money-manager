package com.moneymanager.tools.strategycatalog

import com.moneymanager.builtin.BuiltInApiStrategies
import com.moneymanager.builtin.BuiltInCsvStrategies
import com.moneymanager.database.json.ApiStrategyJsonCodec
import com.moneymanager.database.json.CsvStrategyJsonCodec
import com.moneymanager.database.json.StrategyArtifactCodec
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.strategy.StrategyFileNaming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Every built-in strategy as it was persisted before strategy configs were versioned (the
 * `legacy-v0` resources: the published catalog artifacts and the database `config_json` form) must
 * upgrade to exactly today's built-in definition. This is what proves each config migration step
 * reproduces, in the new vocabulary, the behaviour the old fields described — the same artifacts sit
 * in users' databases, on Drive and in the catalog, and must keep importing identically.
 */
class LegacyStrategyUpgradeTest {
    private val epoch = Instant.fromEpochMilliseconds(0)

    // Built-ins first published after configs were versioned: they never had a legacy-v0 form to upgrade.
    private val addedAfterVersioning = setOf("PayPal API")

    private fun resource(path: String): String =
        requireNotNull(javaClass.classLoader.getResource("legacy-v0/$path")) { "missing fixture legacy-v0/$path" }.readText()

    @Test
    fun `published legacy artifacts upgrade to the current built-ins`() {
        for ((key, current) in builtInArtifacts()) {
            val fileName = StrategyFileNaming.fileName(key)
            if (fileName.endsWith(".passthrough.json") || key.name in addedAfterVersioning) continue
            val legacy = resource("export/$fileName")
            assertEquals(
                StrategyArtifactCodec.canonicalHash(key.kind, current),
                StrategyArtifactCodec.canonicalHash(key.kind, legacy),
                "$fileName: the legacy artifact no longer upgrades to the current built-in",
            )
        }
    }

    @Test
    fun `legacy database csv configs upgrade to the current built-ins`() {
        for (strategy in BuiltInCsvStrategies.builtInCsvStrategies(epoch, CurrencyId(1))) {
            val legacy = CsvStrategyJsonCodec.decode(resource("db/${strategy.name}.csv-config.json"))
            // Compared re-encoded: decoding canonicalizes order-free collections, the in-memory built-in doesn't.
            assertEquals(
                CsvStrategyJsonCodec.encode(strategy.config),
                CsvStrategyJsonCodec.encode(legacy),
                "${strategy.name}: legacy config_json no longer upgrades to the built-in",
            )
        }
    }

    @Test
    fun `legacy database api configs upgrade to the current built-ins`() {
        for (strategy in BuiltInApiStrategies.builtInApiStrategies(epoch).filter { it.name !in addedAfterVersioning }) {
            val legacy = ApiStrategyJsonCodec.decode(resource("db/${strategy.name}.api-config.json"))
            assertEquals(
                ApiStrategyJsonCodec.encode(strategy.config),
                ApiStrategyJsonCodec.encode(legacy),
                "${strategy.name}: legacy config_json no longer upgrades to the built-in",
            )
        }
    }
}
