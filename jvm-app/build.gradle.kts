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

    // Compose Desktop with Skiko natives for all platforms
    implementation(libs.compose.desktop.windows.x64)
    implementation(libs.compose.desktop.macos.x64)
    implementation(libs.compose.desktop.macos.arm64)
    implementation(libs.compose.desktop.linux.x64)
    implementation(libs.compose.desktop.linux.arm64)

    // Declare transitive dependencies directly (required by buildHealth)
    implementation(libs.androidx.compose.runtime.desktop)
    implementation(libs.compose.foundation.desktop)
    implementation(libs.compose.foundation.layout.desktop)
    implementation(libs.compose.material.desktop)
    implementation(libs.compose.ui.desktop)
    implementation(libs.compose.ui.graphics.desktop)
    implementation(libs.compose.ui.text.desktop)
    implementation(libs.compose.ui.unit.desktop)
    implementation(libs.sqldelight.runtime)
    implementation(libs.diamondedge.logging)

    // Logging
    implementation(libs.kmlogging)
    runtimeOnly(libs.log4j.core)
    runtimeOnly(libs.log4j.slf4j2.impl)
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
