package com.moneymanager.bazel.kmp.r8

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.system.exitProcess

private const val SERVICES = "META-INF/services/"

// Annotation processors are compile-time only; the app never loads them.
private const val PROCESSOR = "javax.annotation.processing.Processor"

private data class Provider(
    val service: String,
    val implementation: String,
)

/**
 * A test over `r8_jars`' output: a service file whose provider class the shrinker removed (one reached only through a
 * ServiceLoader lookup it can't follow: the SQLite JDBC driver, the crypto provider, …) makes the release app fail at
 * runtime while the build passes.
 *
 * The arguments are the shrunk jars. The report (`ok`/`MISSING` per provider) goes to the test's undeclared outputs.
 */
fun main(args: Array<String>) {
    val classes = mutableSetOf<String>()
    val providers = mutableListOf<Provider>()
    for (jar in args) {
        ZipFile(jar).use { zip ->
            for (entry in zip.entries().asSequence()) {
                val name = entry.name
                if (name.endsWith(".class")) {
                    classes += name.removeSuffix(".class").replace('/', '.')
                } else if (name.startsWith(SERVICES) && !entry.isDirectory) {
                    val service = name.removePrefix(SERVICES)
                    zip.getInputStream(entry).bufferedReader().useLines { lines ->
                        lines
                            .map { it.substringBefore('#').trim() }
                            .filter { it.isNotEmpty() }
                            .forEach { providers += Provider(service, it) }
                    }
                }
            }
        }
    }
    val report = StringBuilder()
    val missing = mutableListOf<String>()
    for (provider in providers) {
        val kept = provider.implementation in classes
        report.append(if (kept) "ok" else "MISSING").append(" ${provider.service} -> ${provider.implementation}\n")
        if (!kept && provider.service != PROCESSOR) {
            missing += "  ${provider.service} -> ${provider.implementation}"
        }
    }
    System.getenv("TEST_UNDECLARED_OUTPUTS_DIR")?.let {
        Files.writeString(Path.of(it, "service-providers.txt"), report)
    }
    if (missing.isNotEmpty()) {
        System.err.println(
            "R8 removed ServiceLoader providers; add -keep rules to r8-rules.pro:\n" + missing.joinToString("\n"),
        )
        exitProcess(1)
    }
    println("${providers.size} service providers, all kept.")
}
