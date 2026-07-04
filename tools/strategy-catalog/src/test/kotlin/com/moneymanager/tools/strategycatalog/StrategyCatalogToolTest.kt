package com.moneymanager.tools.strategycatalog

import com.moneymanager.database.json.StrategyArtifactCodec
import com.moneymanager.domain.StrategyFileNaming
import com.moneymanager.domain.StrategyKind
import com.moneymanager.strategycatalog.CatalogManifestCodec
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
        assertEquals(setOf(StrategyKind.CSV, StrategyKind.QIF, StrategyKind.API, StrategyKind.PASS_THROUGH), kinds)
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

    private fun createTempDir(): File =
        File.createTempFile("catalog-site", "").let {
            it.delete()
            it.mkdirs()
            it
        }
}
