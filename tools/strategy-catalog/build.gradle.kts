plugins {
    alias(libs.plugins.kotlin.jvm)
    id("moneymanager.kotlin-convention")
    alias(libs.plugins.kotlin.serialization)
}

// JVM tool for the strategy catalog published on GitHub Pages. generateCatalogSite renders the Kotlin
// built-in definitions (app/strategies) into webpage/strategy-library/{index.json, *.json} — nothing
// is checked in; CI runs it before uploading webpage/ to Pages (webpage/strategy-library is
// gitignored, like the SchemaSpy docs). Its tests validate every generated artifact.
dependencies {
    implementation(projects.app.db.read)
    implementation(projects.app.model.core)
    implementation(projects.app.strategies)
    implementation(projects.app.strategycatalog)

    testImplementation(kotlin("test"))
}

tasks.register<JavaExec>("generateCatalogSite") {
    group = "build"
    description = "Generates the strategy catalog (index.json + artifacts) into webpage/strategy-library from the Kotlin built-ins."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.moneymanager.tools.strategycatalog.StrategyCatalogToolKt")
    val webpagePath = rootDir.resolve("webpage").absolutePath
    outputs.dir(rootDir.resolve("webpage/strategy-library"))
    args("generate", webpagePath)
}
