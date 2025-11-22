plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
    application
}

dependencies {
    implementation(projects.shared)
    implementation(projects.sharedDatabase)
    implementation(projects.sharedDi)
    implementation(projects.composeUi)

    // Compose Desktop with platform-specific Skiko natives
    implementation(compose.desktop.currentOs)

    // Logging
    implementation(libs.kmlogging)
    implementation(libs.log4j.core)
    implementation(libs.log4j.slf4j2.impl)
}

application {
    mainClass.set("com.moneymanager.MainKt")
}

kotlin {
    jvmToolchain(21)
}

// Handle duplicate JARs in distribution tasks
tasks.withType<Tar>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<Zip>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
