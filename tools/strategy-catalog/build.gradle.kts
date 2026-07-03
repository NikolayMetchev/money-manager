plugins {
    alias(libs.plugins.kotlin.jvm)
    id("moneymanager.kotlin-convention")
    alias(libs.plugins.kotlin.serialization)
}

// JVM tool for the central strategy catalog (strategy-library/ + GitHub Pages):
//   export <strategyLibraryDir>            — renders the Kotlin built-in definitions to the checked-in
//                                            strategy-library/*.json artifacts (run when built-ins change)
//   index <strategyLibraryDir> <outputDir> — validates every artifact, computes canonical hashes and
//                                            writes <outputDir>/strategy-library/{index.json, *.json}
// Its tests keep the Kotlin definitions and the checked-in JSON in lockstep.
dependencies {
    implementation(projects.app.db.read)
    implementation(projects.app.model.core)
    implementation(projects.app.strategycatalog)
    implementation(projects.test.app.strategies)

    testImplementation(kotlin("test"))
}

val exportStrategyLibrary =
    tasks.register<JavaExec>("exportStrategyLibrary") {
        group = "build"
        description = "Regenerates strategy-library/ from the Kotlin built-in strategy definitions."
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("com.moneymanager.tools.strategycatalog.StrategyCatalogToolKt")
        args("export", rootDir.resolve("strategy-library").absolutePath)
    }

tasks.register<JavaExec>("generateCatalogSite") {
    group = "build"
    description = "Validates strategy-library/ and writes the catalog site (index.json + artifacts) into build/catalog-site."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.moneymanager.tools.strategycatalog.StrategyCatalogToolKt")
    val libraryPath = rootDir.resolve("strategy-library").absolutePath
    val outDir = layout.buildDirectory.dir("catalog-site")
    inputs.dir(libraryPath)
    outputs.dir(outDir)
    // Capture only the path string + output provider (config-cache-safe; no script object references).
    argumentProviders.add(CommandLineArgumentProvider { listOf("index", libraryPath, outDir.get().asFile.absolutePath) })
}
