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
    private val libraryDir = repoRoot().resolve("strategy-library")

    // Walks up from the working directory to the repo root (the dir holding settings.gradle.kts), so
    // the test is independent of Gradle's test working dir.
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile ?: fail("repo root not found above ${System.getProperty("user.dir")}")
        }
        return dir
    }

    @Test
    fun `every checked-in artifact validates and its name matches the file name stem`() {
        val files = libraryDir.listFiles { file -> file.isFile && file.extension == "json" }.orEmpty()
        assertTrue(files.isNotEmpty(), "strategy-library/ contains artifacts")
        for (file in files) {
            val key = StrategyFileNaming.parse(file.name) ?: fail("${file.name}: not a valid library file name")
            val embeddedName = StrategyArtifactCodec.embeddedName(key.kind, file.readText())
            assertEquals(key.name, embeddedName, "${file.name}: embedded name matches file name stem")
        }
    }

    @Test
    fun `checked-in artifacts are in lockstep with the Kotlin built-in definitions`() {
        // Fails in either direction: an edited Kotlin definition without a re-export, or a hand-edited
        // built-in JSON. Run :tools:strategy-catalog:exportStrategyLibrary to regenerate.
        for ((key, expectedJson) in builtInArtifacts()) {
            val file = File(libraryDir, StrategyFileNaming.fileName(key))
            assertTrue(file.exists(), "${file.name} exists in strategy-library/")
            assertEquals(expectedJson, file.readText(), "${file.name} matches the export of its Kotlin definition")
        }
    }

    @Test
    fun `generated index lists every artifact with its canonical hash`() {
        val outputDir = createTempDir()
        try {
            generateCatalogSite(libraryDir, outputDir)
            val siteDir = File(outputDir, "strategy-library")
            val manifest = CatalogManifestCodec.decode(File(siteDir, "index.json").readText())

            val jsonFiles = libraryDir.listFiles { file -> file.isFile && file.extension == "json" }.orEmpty()
            assertEquals(jsonFiles.size, manifest.entries.size, "one manifest entry per artifact")
            for (entry in manifest.entries) {
                val copied = File(siteDir, entry.fileName)
                assertTrue(copied.exists(), "${entry.fileName} copied next to index.json")
                assertEquals(
                    StrategyArtifactCodec.canonicalHash(entry.kind, copied.readText()),
                    entry.contentHash,
                    "${entry.fileName}: manifest hash is the canonical content hash",
                )
            }
        } finally {
            outputDir.deleteRecursively()
        }
    }

    @Test
    fun `the catalog covers all built-in kinds`() {
        val kinds =
            libraryDir
                .listFiles { file -> file.isFile && file.extension == "json" }
                .orEmpty()
                .mapNotNull { StrategyFileNaming.parse(it.name)?.kind }
                .toSet()
        assertEquals(setOf(StrategyKind.CSV, StrategyKind.QIF, StrategyKind.API, StrategyKind.PASS_THROUGH), kinds)
    }

    private fun createTempDir(): File =
        File.createTempFile("catalog-site", "").let {
            it.delete()
            it.mkdirs()
            it
        }
}
