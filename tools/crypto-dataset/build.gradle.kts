plugins {
    alias(libs.plugins.kotlin.jvm)
    id("moneymanager.kotlin-convention")
    alias(libs.plugins.kotlin.serialization)
}

// JVM maintainer tool that regenerates the bundled crypto catalog checked in at
// app/cryptodata/src/commonResources/crypto/coins.tsv. It fetches CoinGecko's coin list (long tail) and
// top coins by market cap (canonical names for colliding tickers), de-duplicates by symbol with the
// ranked names winning, and writes the TSV via the shared renderCryptoDataset. Run manually / on a
// schedule — NOT part of the app build, so offline builds stay reproducible from the checked-in file.
dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.http)
    implementation(projects.app.cryptodata)

    testImplementation(kotlin("test"))
    testImplementation(projects.app.model.core)
}

tasks.register<JavaExec>("generateCryptoDataset") {
    group = "build"
    description = "Regenerates app/cryptodata/src/commonResources/crypto/coins.tsv from CoinGecko."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.moneymanager.tools.cryptodataset.CryptoDatasetToolKt")
    val output = rootDir.resolve("app/cryptodata/src/commonResources/crypto/coins.tsv").absolutePath
    args(output)
}
