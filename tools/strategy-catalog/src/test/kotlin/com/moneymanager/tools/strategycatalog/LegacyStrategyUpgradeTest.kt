package com.moneymanager.tools.strategycatalog

import com.moneymanager.builtin.BuiltInApiStrategies
import com.moneymanager.builtin.BuiltInCsvStrategies
import com.moneymanager.database.json.ApiStrategyJsonCodec
import com.moneymanager.database.json.CsvStrategyJsonCodec
import com.moneymanager.database.json.StrategyArtifactCodec
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.csvstrategy.AmountParsingMapping
import com.moneymanager.domain.model.csvstrategy.CsvImportStrategy
import com.moneymanager.domain.model.csvstrategy.CurrencyLookupMapping
import com.moneymanager.domain.model.csvstrategy.TransferField
import com.moneymanager.domain.model.rules.ValueExpr
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
    private val addedAfterVersioning = setOf("PayPal API", "PayPal CSV", "PayPal CSV (legacy)", "Curve CSV (Transactions)")

    /**
     * A built-in as it stood at the legacy-v0 snapshot, for the few deliberately changed since in a way no
     * old config described (so no migration step can add it): each change is undone here, and everything
     * else about the built-in must still be exactly what its legacy form upgrades to.
     *
     * Foreign amounts: a card payment abroad is booked in the currency the merchant was paid, with the
     * card's conversion as a trade. The Excel export also stopped reading its settled amount as being in
     * the requested currency.
     */
    private fun CsvImportStrategy.asOfLegacySnapshot(): CsvImportStrategy {
        if (name !in setOf("Crypto.com Card", "Crypto.com Card (Excel)", "Monzo CSV")) return this
        val amount = config.fieldMappings.getValue(TransferField.AMOUNT) as AmountParsingMapping
        val reverted = config.fieldMappings + (TransferField.AMOUNT to amount.copy(foreignAmount = null))
        val currency =
            if (name == "Crypto.com Card (Excel)") {
                mapOf(TransferField.CURRENCY to CurrencyLookupMapping(TransferField.CURRENCY, ValueExpr(listOf("Currency "))))
            } else {
                emptyMap()
            }
        return copy(config = config.copy(fieldMappings = reverted + currency))
    }

    private fun resource(path: String): String =
        requireNotNull(javaClass.classLoader.getResource("legacy-v0/$path")) { "missing fixture legacy-v0/$path" }.readText()

    @Test
    fun `published legacy artifacts upgrade to the current built-ins`() {
        for ((key, current) in builtInArtifacts { it.asOfLegacySnapshot() }) {
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
        for (strategy in BuiltInCsvStrategies.builtInCsvStrategies(epoch, CurrencyId(1)).filter { it.name !in addedAfterVersioning }) {
            val legacy = CsvStrategyJsonCodec.decode(resource("db/${strategy.name}.csv-config.json"))
            // Compared re-encoded: decoding canonicalizes order-free collections, the in-memory built-in doesn't.
            assertEquals(
                CsvStrategyJsonCodec.encode(strategy.asOfLegacySnapshot().config),
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
