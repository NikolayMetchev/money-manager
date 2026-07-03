@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.moneymanager.tools.strategycatalog

import com.moneymanager.builtin.BuiltInApiStrategies
import com.moneymanager.builtin.BuiltInCsvStrategies
import com.moneymanager.builtin.BuiltInPassThroughs
import com.moneymanager.database.json.ApiStrategyExportCodec
import com.moneymanager.database.json.CsvStrategyExportCodec
import com.moneymanager.database.json.PassThroughExportCodec
import com.moneymanager.database.json.StrategyArtifactCodec
import com.moneymanager.domain.StrategyFileNaming
import com.moneymanager.domain.StrategyKey
import com.moneymanager.domain.StrategyKind
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.apistrategy.export.ApiStrategyExportMapper
import com.moneymanager.domain.model.csvstrategy.export.CsvStrategyExportMapper
import com.moneymanager.domain.model.csvstrategy.isQifStrategy
import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.model.passthrough.export.PassThroughExport
import com.moneymanager.strategycatalog.CatalogEntry
import com.moneymanager.strategycatalog.CatalogManifest
import com.moneymanager.strategycatalog.CatalogManifestCodec
import java.io.File
import kotlin.time.Instant

// Library artifacts carry a blank version stamp: they aren't tied to the app version that produced
// them, and the canonical hash blanks the version anyway.
private const val LIBRARY_VERSION = ""

// The QIF built-ins hard-code a currency the apply flow pre-selects; in exports it travels as a code.
private val GBP_CURRENCY_ID = CurrencyId(1)
private const val GBP_CURRENCY_CODE = "GBP"

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "export" -> {
            require(args.size == 2) { "usage: export <strategyLibraryDir>" }
            exportBuiltIns(File(args[1]))
        }
        "index" -> {
            require(args.size == 3) { "usage: index <strategyLibraryDir> <outputDir>" }
            generateCatalogSite(File(args[1]), File(args[2]))
        }
        else -> error("usage: (export <strategyLibraryDir> | index <strategyLibraryDir> <outputDir>)")
    }
}

/** Every built-in definition rendered to its portable artifact, keyed for StrategyFileNaming. */
internal fun builtInArtifacts(): Map<StrategyKey, String> {
    val artifacts = linkedMapOf<StrategyKey, String>()

    for (strategy in BuiltInCsvStrategies.builtInCsvStrategies(Instant.fromEpochMilliseconds(0), GBP_CURRENCY_ID)) {
        val export =
            CsvStrategyExportMapper.toExport(
                strategy = strategy,
                version = LIBRARY_VERSION,
                accountNameById = { null },
                currencyCodeById = { id -> GBP_CURRENCY_CODE.takeIf { id == GBP_CURRENCY_ID } },
                categoryNameById = { null },
            )
        val kind = if (strategy.isQifStrategy()) StrategyKind.QIF else StrategyKind.CSV
        artifacts[StrategyKey(kind, strategy.name)] = CsvStrategyExportCodec.encode(export)
    }

    for (strategy in BuiltInApiStrategies.builtInApiStrategies(Instant.fromEpochMilliseconds(0))) {
        val export = ApiStrategyExportMapper.toExport(strategy, LIBRARY_VERSION)
        artifacts[StrategyKey(StrategyKind.API, strategy.name)] = ApiStrategyExportCodec.encode(export)
    }

    for (definition in BuiltInPassThroughs.builtInPassThroughs()) {
        artifacts[StrategyKey(StrategyKind.PASS_THROUGH, definition.name)] = PassThroughExportCodec.encode(definition.toExport())
    }

    return artifacts
}

private fun PassThroughAccount.toExport(): PassThroughExport =
    PassThroughExport(
        version = LIBRARY_VERSION,
        name = name,
        conduitAccountName = conduitAccountName,
        relationshipTypeId = relationshipTypeId,
        rules = rules,
    )

internal fun exportBuiltIns(libraryDir: File) {
    libraryDir.mkdirs()
    for ((key, json) in builtInArtifacts()) {
        File(libraryDir, StrategyFileNaming.fileName(key)).writeText(json)
    }
}

/**
 * Validates every artifact in [libraryDir] (decodable by its kind's codec, embedded name matching the
 * filename stem) and writes the deployable site — index.json + a copy of each artifact — into
 * [outputDir]/strategy-library.
 */
internal fun generateCatalogSite(
    libraryDir: File,
    outputDir: File,
) {
    val entries =
        libraryDir
            .listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }
            .map { file ->
                val key =
                    StrategyFileNaming.parse(file.name)
                        ?: error("${file.name}: not a valid strategy-library file name")
                require(key.kind != StrategyKind.GLOBAL_MAPPINGS) {
                    "${file.name}: global account mappings are personal data and don't belong in the catalog"
                }
                val json = file.readText()
                val embeddedName = StrategyArtifactCodec.embeddedName(key.kind, json)
                require(embeddedName == key.name) {
                    "${file.name}: embedded name \"$embeddedName\" doesn't match the file name stem \"${key.name}\""
                }
                CatalogEntry(
                    name = key.name,
                    kind = key.kind,
                    fileName = file.name,
                    contentHash = StrategyArtifactCodec.canonicalHash(key.kind, json),
                )
            }
    require(entries.isNotEmpty()) { "no artifacts found in $libraryDir" }

    val siteDir = File(outputDir, "strategy-library").apply { mkdirs() }
    File(siteDir, "index.json").writeText(CatalogManifestCodec.encode(CatalogManifest(entries)))
    for (entry in entries) {
        File(libraryDir, entry.fileName).copyTo(File(siteDir, entry.fileName), overwrite = true)
    }
}
