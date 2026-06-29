@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.database.seed.generator

import com.moneymanager.currency.Currency
import com.moneymanager.database.json.ApiStrategyJsonCodec
import com.moneymanager.database.json.FieldMappingJsonCodec
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.CurrencyScaleFactors
import com.moneymanager.domain.model.EntityType
import com.moneymanager.domain.model.WellKnownIds
import com.moneymanager.domain.model.csvstrategy.BuiltInCsvStrategies
import java.io.File
import java.util.Locale
import kotlin.time.Instant
import java.util.Currency as JavaCurrency

/** SQL string-literal escape (double single quotes). */
private fun String.sqlQuote(): String = "'" + replace("'", "''") + "'"

private const val SYSTEM_SOURCE_ID = 4 // SourceType.SYSTEM.id
private const val REVISION = 1L

/**
 * Emits the dynamic seed `.sq` (currencies + built-in strategies) into [outputDir]/<sqldelight package
 * path>. Run at build time by `:app:db:seed`'s `generateSeedSql` task; the output is NOT checked in.
 *
 * Determinism: currencies are sorted by code with explicit sequential ids; names use Locale.ENGLISH;
 * strategy ids/JSON are fixed; createdAt/updatedAt are table DEFAULTs (not emitted). SQLDelight runs a
 * module's `.sq` in file-name order, so the generated files are prefixed "Z" to sort AFTER the checked-in
 * StaticSeed.sq (which seeds the device/source_type the provenance rows below reference).
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: SeedGenerator <outputDir>" }
    val pkgDir = File(args[0], "com/moneymanager/database/sql/seed").apply { mkdirs() }

    val currencies = Currency.getAllCurrencies() // already sorted by code
    val gbpId =
        currencies.indexOfFirst { it.code == "GBP" }.let {
            require(it >= 0) { "GBP missing" }
            it + 1
        }

    File(pkgDir, "ZCurrencies.sq").writeText(generateCurrencies(currencies))
    File(pkgDir, "ZApiStrategies.sq").writeText(generateApiStrategies())
    File(pkgDir, "ZCsvStrategies.sq").writeText(generateCsvStrategies(gbpId.toLong()))
}

private fun header(what: String) = "-- GENERATED — do not edit. $what Built at build time by :app:db:seed:generator.\n"

internal fun generateCurrencies(currencies: List<Currency> = Currency.getAllCurrencies()): String {
    val sb = StringBuilder(header("ISO 4217 currencies (explicit ids, Locale.ENGLISH names) + SYSTEM provenance."))
    currencies.forEachIndexed { index, currency ->
        val id = index + 1
        val name = JavaCurrency.getInstance(currency.code).getDisplayName(Locale.ENGLISH)
        val scale = CurrencyScaleFactors.getScaleFactor(currency.code)
        sb.appendLine(
            "INSERT INTO currency(id, revision_id, code, name, scale_factor) " +
                "VALUES ($id, 1, ${currency.code.sqlQuote()}, ${name.sqlQuote()}, $scale);",
        )
    }
    currencies.forEachIndexed { index, _ ->
        sb.appendLine(systemSource(EntityType.CURRENCY.id, (index + 1).toLong()))
    }
    return sb.toString()
}

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

private fun systemSource(
    entityTypeId: Long,
    entityId: Long,
): String =
    "INSERT OR IGNORE INTO entity_source(entity_type_id, entity_id, revision_id, source_type_id, device_id) " +
        "VALUES ($entityTypeId, $entityId, $REVISION, $SYSTEM_SOURCE_ID, ${WellKnownIds.SYSTEM_DEVICE_ID});"
