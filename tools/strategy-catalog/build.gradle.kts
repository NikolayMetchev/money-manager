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
    implementation(projects.app.model.apistrategy)
    implementation(projects.app.model.core)
    implementation(projects.app.model.csvstrategy)
    implementation(projects.app.model.passthrough)
    implementation(projects.app.strategies)
    implementation(projects.app.strategycatalog)
    implementation(projects.utils.currency)

    testImplementation(projects.app.model.rules)
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

tasks.register<JavaExec>("generateBundledCurrencies") {
    group = "build"
    description = "Regenerates utils/currency's bundled ISO 4217 table (IsoCurrencies.kt) from java.util.Currency. Run by hand."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.moneymanager.tools.strategycatalog.StrategyCatalogToolKt")
    val target = rootDir.resolve("utils/currency/src/commonMain/kotlin/com/moneymanager/currency/IsoCurrencies.kt")
    outputs.file(target)
    args("currencies", target.absolutePath)
}
