package com.moneymanager.bazel.kmp.r8

import com.android.tools.r8.CompilationMode
import com.android.tools.r8.JdkClassFileProvider
import com.android.tools.r8.OutputMode
import com.android.tools.r8.R8
import com.android.tools.r8.R8Command
import com.android.tools.r8.origin.PathOrigin
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private const val COMPOSE_RULES = "default-compose-desktop-rules.pro"

/**
 * Runs R8 for `r8_jars`, with class files, not DEX, as its output.
 *
 * The arguments are the configuration file the rule writes and the output jar. `{compose_rules}` in the configuration
 * becomes the Compose Gradle plugin's own `default-compose-desktop-rules.pro`, read from the plugin's jar on this
 * classpath, so the rules move with the catalog's Compose version.
 *
 * The configuration goes to R8 as text rather than as a file: R8 resolves a file's `-injars` against the file's own
 * directory, and the rule writes them relative to the execution root, the working directory.
 *
 * The library classes are this runner's own JDK's: R8 reads a JDK home, not jmods. The runner's JDK is MODULE.bazel's
 * JDK, the one the app is compiled against and bundles.
 */
fun main(args: Array<String>) {
    val config = Path.of(args[0])
    val output = Path.of(args[1])
    val composeRules = Files.createTempFile("compose-rules", ".pro")
    try {
        val rules =
            ClassLoader.getSystemResourceAsStream(COMPOSE_RULES)
                ?: error("$COMPOSE_RULES isn't on the classpath (compose-gradle-plugin)")
        rules.use { Files.copy(it, composeRules, StandardCopyOption.REPLACE_EXISTING) }
        val text = Files.readString(config).replace("{compose_rules}", composeRules.toString())
        val command =
            R8Command
                .builder()
                .addProguardConfiguration(listOf(text), PathOrigin(config))
                .addLibraryResourceProvider(JdkClassFileProvider.fromJdkHome(Path.of(System.getProperty("java.home"))))
                // Compatibility mode: R8's full mode also drops default constructors and attributes that no keep rule
                // names.
                .setProguardCompatibility(true)
                .setMode(CompilationMode.RELEASE)
                .setOutput(output, OutputMode.ClassFile)
                .build()
        R8.run(command)
    } finally {
        Files.deleteIfExists(composeRules)
    }
}
