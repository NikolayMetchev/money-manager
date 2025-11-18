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
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
}

application {
    mainClass.set("com.moneymanager.MainKt")
}

kotlin {
    jvmToolchain(21)
}
