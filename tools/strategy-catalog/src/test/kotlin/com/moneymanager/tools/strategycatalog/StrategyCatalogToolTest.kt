package com.moneymanager.tools.strategycatalog

import com.moneymanager.database.json.StrategyArtifactCodec
import com.moneymanager.domain.strategy.StrategyFileNaming
import com.moneymanager.domain.strategy.StrategyKind
import com.moneymanager.strategycatalog.CatalogManifestCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class StrategyCatalogToolTest {
    @Test
    fun `every built-in artifact round-trips its codec and matches its file name`() {
        val artifacts = builtInArtifacts()
        assertTrue(artifacts.isNotEmpty())
        for ((key, json) in artifacts) {
            assertEquals(key.name, StrategyArtifactCodec.embeddedName(key.kind, json))
            assertEquals(key, StrategyFileNaming.parse(StrategyFileNaming.fileName(key)) ?: fail("unparseable file name for $key"))
        }
    }

    @Test
    fun `generated site lists every artifact with its canonical hash`() {
        val webpageDir = createTempDir()
        try {
            generateCatalogSite(webpageDir)
            val siteDir = File(webpageDir, "strategy-library")
            val manifest = CatalogManifestCodec.decode(File(siteDir, "index.json").readText())

            assertEquals(builtInArtifacts().size, manifest.entries.size, "one manifest entry per built-in")
            for (entry in manifest.entries) {
                val artifact = File(siteDir, entry.fileName)
                assertTrue(artifact.exists(), "${entry.fileName} generated next to index.json")
                assertEquals(
                    StrategyArtifactCodec.canonicalHash(entry.kind, artifact.readText()),
                    entry.contentHash,
                    "${entry.fileName}: manifest hash is the canonical content hash",
                )
                assertEquals(entry.name, StrategyArtifactCodec.embeddedName(entry.kind, artifact.readText()))
            }
        } finally {
            webpageDir.deleteRecursively()
        }
    }

    @Test
    fun `the catalog covers all built-in kinds`() {
        val kinds = builtInArtifacts().keys.map { it.kind }.toSet()
        assertEquals(
            setOf(
                StrategyKind.CSV,
                StrategyKind.QIF,
                StrategyKind.XLSX,
                StrategyKind.PDF,
                StrategyKind.API,
                StrategyKind.PASS_THROUGH,
                StrategyKind.CURRENCIES,
            ),
            kinds,
        )
    }

    @Test
    fun `every csv and api artifact publishes its rules`() {
        // A built-in decides everything through its rule list, so an artifact without rules would install
        // a strategy that imports differently from the built-in.
        val strategies = builtInArtifacts().filterKeys { it.kind in RULE_KINDS }
        assertTrue(strategies.isNotEmpty())
        for ((key, json) in strategies) {
            assertTrue(Json.parseToJsonElement(json).ruleCount() > 0, "${key.name} publishes no rules")
        }
    }

    // A rule is the one object with an "actions" list, wherever the config nests it.
    private fun JsonElement.ruleCount(): Int =
        when (this) {
            is JsonObject -> (if (get("actions") is JsonArray) 1 else 0) + values.sumOf { it.ruleCount() }
            is JsonArray -> sumOf { it.ruleCount() }
            else -> 0
        }

    @Test
    fun `regenerating replaces stale artifacts`() {
        val webpageDir = createTempDir()
        try {
            val siteDir = File(webpageDir, "strategy-library").apply { mkdirs() }
            val stale = File(siteDir, "Removed.csv.json").apply { writeText("{}") }
            generateCatalogSite(webpageDir)
            assertTrue(!stale.exists(), "stale artifacts from a previous run are cleared")
        } finally {
            webpageDir.deleteRecursively()
        }
    }

    private companion object {
        val RULE_KINDS = setOf(StrategyKind.CSV, StrategyKind.QIF, StrategyKind.XLSX, StrategyKind.PDF, StrategyKind.API)
    }

    private fun createTempDir(): File =
        File.createTempFile("catalog-site", "").let {
            it.delete()
            it.mkdirs()
            it
        }
}
