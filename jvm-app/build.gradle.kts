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

    // Transitive dependencies that should be declared directly
    implementation(libs.compose.runtime.desktop)
    implementation(libs.compose.ui.desktop)
    implementation(libs.compose.ui.graphics.desktop)
    implementation(libs.compose.ui.unit.desktop)
}

application {
    mainClass.set("com.moneymanager.MainKt")
}

kotlin {
    jvmToolchain(21)
}
