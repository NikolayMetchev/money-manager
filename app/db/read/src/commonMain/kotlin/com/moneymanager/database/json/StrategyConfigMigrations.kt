package com.moneymanager.database.json

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * One step upgrading a strategy config's JSON from the version before it to its own. Steps work on raw
 * JSON so a retired field can still be read after its Kotlin property is gone, and they must accept
 * both persisted shapes of a config: the database form (field mappings reference ids) and the export
 * form (field mappings reference names). A step therefore only renames, moves or rewrites keys it
 * recognises and passes everything else through untouched.
 */
internal fun interface ConfigMigrationStep {
    fun migrate(config: JsonObject): JsonObject
}

/**
 * Upgrades persisted strategy configs to the shape the current code decodes. Every stored or synced
 * config is stamped with [VERSION_KEY] when written; on read, the steps between that stamp (0 when
 * absent, i.e. written before versioning) and the current version run in order. This is what lets a
 * strategy feature be replaced by a more general one instead of only ever adding knobs: configs in an
 * older database, on Drive or in the published catalog keep decoding.
 */
internal object StrategyConfigMigrations {
    const val VERSION_KEY = "configVersion"

    private val csvSteps: List<ConfigMigrationStep> = listOf(csvSharedRulesStep, csvAccountRulesStep, csvLegGroupsStep)

    private val apiSteps: List<ConfigMigrationStep> = listOf(apiSharedRulesStep, apiOnePipelineStep, apiDirectionStep, apiFeeAndLedgerStep)

    val currentCsvVersion: Int get() = csvSteps.size

    val currentApiVersion: Int get() = apiSteps.size

    fun upgradeCsv(config: JsonObject): JsonObject = upgrade(config, csvSteps)

    fun upgradeApi(config: JsonObject): JsonObject = upgrade(config, apiSteps)

    fun stampCsv(config: JsonObject): JsonObject = stamp(config, currentCsvVersion)

    fun stampApi(config: JsonObject): JsonObject = stamp(config, currentApiVersion)

    private fun upgrade(
        config: JsonObject,
        steps: List<ConfigMigrationStep>,
    ): JsonObject {
        val from = config[VERSION_KEY]?.jsonPrimitive?.intOrNull ?: 0
        require(from <= steps.size) {
            "Strategy config version $from is newer than this app understands (${steps.size}); update the app"
        }
        return steps.drop(from).fold(JsonObject(config.withoutVersion())) { acc, step -> step.migrate(acc) }
    }

    private fun stamp(
        config: JsonObject,
        version: Int,
    ): JsonObject = JsonObject(config.withoutVersion() + (VERSION_KEY to JsonPrimitive(version)))

    private fun JsonObject.withoutVersion(): Map<String, JsonElement> = this.filterKeys { it != VERSION_KEY }
}
