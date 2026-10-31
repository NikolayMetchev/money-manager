package com.moneymanager.tools.strategycatalog

import com.moneymanager.builtin.BuiltInApiStrategies
import com.moneymanager.builtin.BuiltInCsvStrategies
import com.moneymanager.builtin.BuiltInPassThroughs
import com.moneymanager.database.json.ApiStrategyExportCodec
import com.moneymanager.database.json.CsvStrategyExportCodec
import com.moneymanager.database.json.CurrencyListExportCodec
import com.moneymanager.database.json.PassThroughExportCodec
import com.moneymanager.database.json.StrategyArtifactCodec
import com.moneymanager.domain.model.CurrencyId
import com.moneymanager.domain.model.apistrategy.export.ApiStrategyExportMapper
import com.moneymanager.domain.model.csvstrategy.export.CsvStrategyExportMapper
import com.moneymanager.domain.model.passthrough.PassThroughAccount
import com.moneymanager.domain.model.passthrough.export.PassThroughExport
import com.moneymanager.domain.strategy.StrategyFileNaming
import com.moneymanager.domain.strategy.StrategyKey
import com.moneymanager.domain.strategy.StrategyKind
import com.moneymanager.domain.strategy.libraryKind
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

/**
 * Generates the strategy catalog for the GitHub Pages site: renders every Kotlin built-in definition
 * (app/strategies) plus the ISO 4217 currency list to its portable JSON artifact and writes
 * `<webpageDir>/strategy-library/` — index.json plus one file per artifact. Nothing is checked in; CI
 * runs this before uploading the `webpage/` directory to Pages. `currencies <file>` instead regenerates
 * the currency table bundled into the app from the same JDK list, and `calendars <file>` the holiday calendars from
 * OpenGamma Strata.
 */
fun main(args: Array<String>) {
    val usage = "usage: generate <webpageDir> | currencies <IsoCurrencies.kt> | calendars <StrataHolidayCalendars.kt>"
    require(args.size == 2) { usage }
    when (args[0]) {
        "generate" -> generateCatalogSite(workspaceFile(args[1]))
        "currencies" -> workspaceFile(args[1]).writeText(renderIsoCurrenciesSource(jdkIsoCurrencies()))
        "calendars" -> workspaceFile(args[1]).writeText(renderStrataHolidayCalendarsSource(strataHolidayCalendars()))
        else -> error(usage)
    }
}

// `bazel run` starts a tool in its runfiles tree, so a relative path names a file in the workspace.
private fun workspaceFile(path: String): File =
    File(path).takeIf { it.isAbsolute } ?: File(System.getenv("BUILD_WORKSPACE_DIRECTORY") ?: ".", path)

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
        artifacts[StrategyKey(strategy.libraryKind(), strategy.name)] = CsvStrategyExportCodec.encode(export)
    }

    for (strategy in BuiltInApiStrategies.builtInApiStrategies(Instant.fromEpochMilliseconds(0))) {
        val export = ApiStrategyExportMapper.toExport(strategy, LIBRARY_VERSION)
        artifacts[StrategyKey(StrategyKind.API, strategy.name)] = ApiStrategyExportCodec.encode(export)
    }

    for (definition in BuiltInPassThroughs.builtInPassThroughs()) {
        artifacts[StrategyKey(StrategyKind.PASS_THROUGH, definition.name)] = PassThroughExportCodec.encode(definition.toExport())
    }

    // Rendered from this JDK rather than the bundled table, so the site can carry codes newer than an
    // installed app's bundle.
    artifacts[StrategyKey(StrategyKind.CURRENCIES, StrategyFileNaming.CURRENCIES_NAME)] =
        CurrencyListExportCodec.encode(currencyListExport(jdkIsoCurrencies()))

    return artifacts
}

// PassThroughExport.version already defaults to the blank library version.
private fun PassThroughAccount.toExport(): PassThroughExport =
    PassThroughExport(
        name = name,
        conduitAccountName = conduitAccountName,
        relationshipTypeId = relationshipTypeId,
        rules = rules,
    )

/**
 * Renders every built-in to `<webpageDir>/strategy-library/` and writes index.json next to the
 * artifacts. Validates each artifact on the way out (decodable by its kind's codec, embedded name
 * matching the file name stem) so a bad definition fails the build rather than deploying.
 */
internal fun generateCatalogSite(webpageDir: File) {
    val siteDir = File(webpageDir, "strategy-library")
    siteDir.deleteRecursively()
    siteDir.mkdirs()

    val entries =
        builtInArtifacts().map { (key, json) ->
            require(key.kind != StrategyKind.GLOBAL_MAPPINGS) {
                "${key.name}: global account mappings are personal data and don't belong in the catalog"
            }
            val fileName = StrategyFileNaming.fileName(key)
            val embeddedName = StrategyArtifactCodec.embeddedName(key.kind, json)
            require(embeddedName == key.name) {
                "$fileName: embedded name \"$embeddedName\" doesn't match the file name stem \"${key.name}\""
            }
            File(siteDir, fileName).writeText(json)
            CatalogEntry(
                name = key.name,
                kind = key.kind,
                fileName = fileName,
                contentHash = StrategyArtifactCodec.canonicalHash(key.kind, json),
            )
        }
    require(entries.isNotEmpty()) { "no built-in artifacts were generated" }

    File(siteDir, "index.json").writeText(CatalogManifestCodec.encode(CatalogManifest(entries.sortedBy { it.fileName })))
}
