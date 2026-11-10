package com.moneymanager.tools.repo

import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StartupBenchTest {
    @Test
    fun `a run's home holds a copy of the database as its last database`() {
        val root = Files.createTempDirectory("bench-root").toFile()
        val db = File(root, "bench.db").also { it.writeText("db") }
        val home = makeHome(root, db.path)
        val settings = Json.decodeFromString<BenchSettings>(File(home, ".moneymanager/settings.json").readText())
        assertEquals(File(home, ".moneymanager/bench.db").path, settings.lastDatabase)
        assertEquals("db", File(settings.lastDatabase).readText())
    }

    private fun launcher(
        dir: File,
        name: String,
        body: String,
    ): String =
        File(dir, name)
            .also {
                it.writeText("#!/bin/sh\n$body\n")
                it.setExecutable(true)
            }.path

    @Test
    fun `each launcher is timed in an isolated home and recorded as JSON lines`() {
        if (System.getProperty("os.name").startsWith("Windows")) return
        val dir = Files.createTempDirectory("bench").toFile()
        val db = File(dir, "bench.db").also { it.writeText("db") }
        val out = File(dir, "out.jsonl")
        // The desktop stand-in reports a milestone through the probe, as the app does; the CLI ones check their home.
        val desktop = launcher(dir, "desktop", $$"echo '{\"window\": 12.5}' > \"$MM_STARTUP_PROBE\"")
        val cli = launcher(dir, "cli", $$"test -f \"$HOME/.moneymanager/settings.json\"")
        startupBench(
            listOf(
                "--bench-db",
                db.path,
                "--empty-db",
                db.path,
                "--desktop",
                desktop,
                "--mm-jvm",
                cli,
                "--mm",
                cli,
                "--runs",
                "2",
                "--variants",
                "cache,nocache",
                "--sha",
                "abc",
                "--label",
                "test",
                "--out",
                out.path,
            ),
        )
        val records = out.readLines().map { Json.decodeFromString<BenchRecord>(it) }
        val runtimes = records.map { it.runtime }.toSet()
        assertEquals(setOf("desktop", "mm-jvm", "mm"), runtimes)
        assertTrue(
            records.any {
                it.metric == "window" &&
                    it.medianMs == 12.5
            },
        )
        assertTrue(records.all { it.sha == "abc" && it.runs == 2 })
        assertEquals(
            listOf("native"),
            records
                .filter {
                    it.runtime == "mm"
                }.map { it.variant }
                .distinct(),
        )
    }
}
