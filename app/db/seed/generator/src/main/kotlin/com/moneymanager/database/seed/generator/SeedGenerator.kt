@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.seed.generator

import com.moneymanager.database.json.ApiStrategyJsonCodec
import com.moneymanager.database.json.FieldMappingJsonCodec
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.csvstrategy.BuiltInCsvStrategies
import java.io.File
import kotlin.time.Instant

/** SQL string-literal escape (double single quotes). */
private fun String.sqlQuote(): String = "'" + replace("'", "''") + "'"

private const val SYSTEM_SOURCE_ID = 4 // SourceType.SYSTEM.id
private const val REVISION = 1L

// GBP is seeded with this fixed id in StaticSeed.sq; the QIF strategies reference it as a constant.
// Currencies themselves are NOT generated here — they are platform-dependent (Android exposes more
// ISO 4217 codes than the JVM), so they are seeded at runtime by DatabaseConfig.seedCurrencies.
private const val GBP_CURRENCY_ID = 1L

/**
 * Emits the dynamic seed `.sq` (built-in API + CSV/QIF strategies) into [outputDir]/<sqldelight package
 * path>. Run at build time by `:app:db:seed`'s `generateSeedSql` task; the output is NOT checked in.
 *
 * Determinism: strategy ids/JSON are fixed; createdAt/updatedAt are table DEFAULTs (not emitted).
 * SQLDelight runs a module's `.sq` in file-name order, so the generated files are prefixed "Z" to sort
 * AFTER the checked-in StaticSeed.sq (which seeds the device/source_type/GBP the rows below reference).
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: SeedGenerator <outputDir>" }
    val pkgDir = File(args[0], "com/moneymanager/database/sql/seed").apply { mkdirs() }

    File(pkgDir, "ZApiStrategies.sq").writeText(generateApiStrategies())
    File(pkgDir, "ZCsvStrategies.sq").writeText(generateCsvStrategies(GBP_CURRENCY_ID))
}

private fun header(what: String) = "-- GENERATED — do not edit. $what Built at build time by :app:db:seed:generator.\n"

internal fun generateApiStrategies(): String {
    data class Api(
        val id: String,
        val name: String,
        val configJson: String,
    )
    val strategies =
        listOf(
            Api(BuiltInStrategies.monzoStrategyId.toString(), "Monzo", ApiStrategyJsonCodec.encode(BuiltInStrategies.monzoApiConfig())),
            Api(BuiltInStrategies.wiseStrategyId.toString(), "Wise", ApiStrategyJsonCodec.encode(BuiltInStrategies.wiseApiConfig())),
            Api(
                BuiltInStrategies.starlingStrategyId.toString(),
                "Starling",
                ApiStrategyJsonCodec.encode(BuiltInStrategies.starlingApiConfig()),
            ),
        )
    val sb = StringBuilder(header("Built-in API import strategies + SYSTEM provenance."))
    strategies.forEach {
        sb.appendLine(
            "INSERT INTO api_import_strategy(id, name, config_json) " +
                "VALUES (${it.id.sqlQuote()}, ${it.name.sqlQuote()}, ${it.configJson.sqlQuote()});",
        )
    }
    strategies.forEach {
        sb.appendLine(
            "INSERT OR IGNORE INTO api_import_strategy_source(strategy_id, revision_id, source_type_id, device_id) " +
                "VALUES (${it.id.sqlQuote()}, $REVISION, $SYSTEM_SOURCE_ID, ${WellKnownIds.SYSTEM_DEVICE_ID});",
        )
    }
    return sb.toString()
}

internal fun generateCsvStrategies(gbpCurrencyId: Long): String {
    val strategies = BuiltInCsvStrategies.builtInCsvStrategies(Instant.fromEpochMilliseconds(0), CurrencyId(gbpCurrencyId))
    val sb = StringBuilder(header("Built-in CSV/QIF import strategies + SYSTEM provenance."))
    strategies.forEach { s ->
        sb.appendLine(
            "INSERT INTO csv_import_strategy(id, name, identification_columns_json, field_mappings_json, " +
                "attribute_mappings_json, row_rules_json, companion_rules_json, content_match_rules_json) VALUES (" +
                "${s.id.id.toString().sqlQuote()}, ${s.name.sqlQuote()}, " +
                "${FieldMappingJsonCodec.encodeColumns(s.identificationColumns).sqlQuote()}, " +
                "${FieldMappingJsonCodec.encode(s.fieldMappings).sqlQuote()}, " +
                "${FieldMappingJsonCodec.encodeAttributeMappings(s.attributeMappings).sqlQuote()}, " +
                "${FieldMappingJsonCodec.encodeRowRules(s.rowPreprocessingRules).sqlQuote()}, " +
                "${FieldMappingJsonCodec.encodeCompanionRules(s.companionTransactionRules).sqlQuote()}, " +
                "${FieldMappingJsonCodec.encodeContentRules(s.contentMatchRules).sqlQuote()});",
        )
    }
    strategies.forEach { s ->
        sb.appendLine(
            "INSERT OR IGNORE INTO csv_import_strategy_source(strategy_id, revision_id, source_type_id, device_id) " +
                "VALUES (${s.id.id.toString().sqlQuote()}, $REVISION, $SYSTEM_SOURCE_ID, ${WellKnownIds.SYSTEM_DEVICE_ID});",
        )
    }
    return sb.toString()
}
