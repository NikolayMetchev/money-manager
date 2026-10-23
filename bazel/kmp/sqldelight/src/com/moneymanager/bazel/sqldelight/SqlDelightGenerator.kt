package com.moneymanager.bazel.sqldelight

import app.cash.sqldelight.core.SqlDelightCompilationUnit
import app.cash.sqldelight.core.SqlDelightDatabaseName
import app.cash.sqldelight.core.SqlDelightDatabaseOptions
import app.cash.sqldelight.core.SqlDelightEnvironment
import app.cash.sqldelight.core.SqlDelightSourceFolder
import app.cash.sqldelight.dialect.api.SqlDelightDialect
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess

/**
 * Generates a SQLDelight database's Kotlin for `sqldelight_library` (#1003).
 *
 * SQLDelight ships only a Gradle plugin, so this drives the compiler the way its `GenerateInterfaces` work
 * action does: one `SqlDelightEnvironment` over the module's `.sq` folder plus each upstream database's
 * folder as a dependency, with the Gradle extension's defaults. The API is internal to SQLDelight, which is
 * why its version is pinned and `golden_test` compares the output with Gradle's.
 *
 * Writes the sources into `--out-dir` (for konanc) and `--srcjar` (for the JVM compile).
 */
fun main(args: Array<String>) {
    val flags = Flags(args)
    val outDir = File(flags.one("--out-dir")).absoluteFile
    val options =
        DatabaseOptions(
            packageName = flags.one("--package"),
            className = flags.one("--class-name"),
            dependencies = flags.all("--dependency").map { DatabaseName.parse(it) },
        )
    val unit =
        CompilationUnit(
            name = "commonMain",
            sourceFolders =
                flags.all("--source-folder").map { SourceFolder(File(it).absoluteFile, dependency = false) }.toSet() +
                    flags.all("--dependency-folder").map { SourceFolder(File(it).absoluteFile, dependency = true) },
            outputDirectoryFile = outDir,
        )
    // Gradle takes the first dialect its ServiceLoader finds, which is the declared one; a dialect's jars also
    // carry the older dialects it extends, so name it.
    val dialect = Class.forName(flags.one("--dialect")).getDeclaredConstructor().newInstance() as SqlDelightDialect
    val environment = SqlDelightEnvironment(options, unit, false, dialect, flags.one("--module-name"))
    val status = environment.generateSqlDelightFiles { System.err.println(it) }
    if (status is SqlDelightEnvironment.CompilationStatus.Failure) {
        status.errors.forEach(System.err::println)
        exitProcess(1)
    }
    writeSrcjar(outDir, File(flags.one("--srcjar")))
}

private fun writeSrcjar(dir: File, srcjar: File) {
    val epoch = FileTime.fromMillis(0)
    ZipOutputStream(srcjar.outputStream().buffered()).use { zip ->
        dir.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(dir).invariantSeparatorsPath }.forEach { file ->
            val entry = ZipEntry(file.relativeTo(dir).invariantSeparatorsPath)
            entry.creationTime = epoch
            entry.lastModifiedTime = epoch
            entry.time = 0
            zip.putNextEntry(entry)
            Files.copy(file.toPath(), zip)
            zip.closeEntry()
        }
    }
}

private class Flags(args: Array<String>) {
    private val values: Map<String, List<String>> =
        args.toList().chunked(2).groupBy({ it.first() }, { it.getOrNull(1) ?: error("${it.first()} needs a value") })

    fun one(name: String): String = values[name]?.singleOrNull() ?: error("expected one $name")

    fun all(name: String): List<String> = values[name].orEmpty()
}

private data class DatabaseName(
    override val packageName: String,
    override val className: String,
) : SqlDelightDatabaseName {
    companion object {
        /** `com.example.sql:MyDatabase`. */
        fun parse(value: String): DatabaseName {
            val (pkg, cls) = value.split(':', limit = 2)
            return DatabaseName(pkg, cls)
        }
    }
}

/** The Gradle extension's defaults (SqlDelightDatabase), but async, as the browser's worker driver needs: `SELECT *` expanded, no excluded columns. */
private data class DatabaseOptions(
    override val packageName: String,
    override val className: String,
    override val dependencies: List<SqlDelightDatabaseName>,
    override val deriveSchemaFromMigrations: Boolean = false,
    override val treatNullAsUnknownForEquality: Boolean = false,
    override val generateAsync: Boolean = true,
    override val expandSelectStar: Boolean = true,
    override val codegenExcludedColumns: Set<String> = emptySet(),
) : SqlDelightDatabaseOptions

private data class SourceFolder(
    override val folder: File,
    override val dependency: Boolean,
) : SqlDelightSourceFolder

private data class CompilationUnit(
    override val name: String,
    override val sourceFolders: Set<SqlDelightSourceFolder>,
    override val outputDirectoryFile: File,
) : SqlDelightCompilationUnit
