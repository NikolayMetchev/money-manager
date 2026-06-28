package com.moneymanager.database.schema

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Enforces the read/write separation of `.sq` files (see issue #571). Each table lives in a
 * per-table subdirectory under `sqldelight/.../sql/<table>/` with up to three files:
 *
 *  - `<Table>.sq`       — schema only (CREATE TABLE/VIEW/INDEX/TRIGGER and unlabelled seed rows),
 *                         no labelled queries.
 *  - `<Table>Select.sq` — only read queries (SELECT).
 *  - `<Table>Write.sq`  — only mutations (INSERT/UPDATE/DELETE and maintenance), plus the
 *                         `last_insert_rowid()` read helpers that pair with the inserts.
 *
 * Keeping writes confined to `*Write.sq` files is the convention that lets us reason about where
 * the database is mutated (a step toward making the import engine the sole writer).
 *
 * This runs on the JVM only because it scans source files (the working directory under Gradle is the
 * module directory, `app/db/core`).
 */
class SqlFileNamingConventionTest {
    private enum class Kind { READ, WRITE, LAST_INSERT }

    // A label line is an identifier alone on its own line, e.g. `selectAll:`.
    private val labelRegex = Regex("^([A-Za-z][A-Za-z0-9_]*):\\s*$")
    private val writeKeywords = setOf("INSERT", "UPDATE", "DELETE", "REPLACE", "REINDEX", "VACUUM", "ANALYZE")

    // The .sq files now live in two sibling modules: schema + *Select.sq in :app:db:read and *Write.sq
    // in :app:db:write. This test scans both. Working dir under Gradle is the module dir (app/db/core),
    // with a repo-root fallback for other runners.
    private fun sqlDirs(): List<File> {
        val candidates =
            listOf(
                "../schema/src/commonMain/sqldelight/com/moneymanager/database/sql",
                "../read/src/commonMain/sqldelight/com/moneymanager/database/sql",
                "../write/src/commonMain/sqldelight/com/moneymanager/database/sql",
                "app/db/schema/src/commonMain/sqldelight/com/moneymanager/database/sql",
                "app/db/read/src/commonMain/sqldelight/com/moneymanager/database/sql",
                "app/db/write/src/commonMain/sqldelight/com/moneymanager/database/sql",
            )
        return candidates.map(::File).filter { it.isDirectory }.ifEmpty {
            fail(
                "Could not locate the sqldelight sql directories. Tried (relative to ${File(".").absolutePath}): " +
                    candidates.joinToString(),
            )
        }
    }

    private data class LabelledQuery(
        val label: String,
        val kind: Kind,
    )

    /** Parses the labelled queries from a `.sq` file. Unlabelled statements (DDL, seed rows) are ignored. */
    private fun parse(file: File): List<LabelledQuery> {
        val lines = file.readLines()
        val result = mutableListOf<LabelledQuery>()
        var i = 0
        while (i < lines.size) {
            val match = labelRegex.matchEntire(lines[i].trim())
            if (match == null) {
                i++
                continue
            }
            val label = match.groupValues[1]
            // Collect the statement body: subsequent lines up to and including the one ending with ';'.
            val body = StringBuilder()
            var j = i + 1
            while (j < lines.size) {
                val line = lines[j]
                body.append(line).append('\n')
                if (line.trimEnd().endsWith(";")) {
                    j++
                    break
                }
                j++
            }
            result.add(LabelledQuery(label, classify(body.toString())))
            i = j
        }
        return result
    }

    private fun classify(body: String): Kind {
        if (body.contains("last_insert_rowid", ignoreCase = true)) return Kind.LAST_INSERT
        val firstKeyword =
            body
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("--") }
                .firstOrNull()
                ?.takeWhile { it.isLetter() }
                ?.uppercase()
                .orEmpty()
        return if (firstKeyword in writeKeywords) Kind.WRITE else Kind.READ
    }

    @Test
    fun `sq files separate reads from writes by file name`() {
        val files = sqlDirs().flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "sq" } }
        assertTrue(files.isNotEmpty(), "Expected to find .sq files under ${sqlDirs().joinToString { it.absolutePath }}")

        val violations = mutableListOf<String>()

        for (file in files) {
            val base = file.nameWithoutExtension
            val queries = parse(file)
            when {
                base.endsWith("Select") -> {
                    queries.filter { it.kind != Kind.READ }.forEach {
                        violations.add("${file.name}: '${it.label}' is a ${it.kind} but Select files must contain only SELECT reads")
                    }
                }
                base.endsWith("Write") -> {
                    queries.filter { it.kind == Kind.READ }.forEach {
                        violations.add(
                            "${file.name}: '${it.label}' is a read but Write files must contain only mutations " +
                                "(SELECT last_insert_rowid() helpers are allowed)",
                        )
                    }
                    if (queries.none { it.kind == Kind.WRITE }) {
                        violations.add("${file.name}: a Write file must contain at least one mutation")
                    }
                }
                else -> {
                    // Schema file: DDL and unlabelled seed rows only.
                    queries.forEach {
                        violations.add(
                            "${file.name}: schema file must contain no labelled queries, but found '${it.label}' " +
                                "(move it to ${base}Select.sq or ${base}Write.sq)",
                        )
                    }
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail(
                "Found ${violations.size} .sq read/write separation violation(s):\n" +
                    violations.sorted().joinToString("\n") { "  - $it" },
            )
        }
    }
}
