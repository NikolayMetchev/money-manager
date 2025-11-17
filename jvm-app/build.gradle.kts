plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(projects.shared)
    implementation(projects.sharedDi)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
}

application {
    mainClass.set("com.moneymanager.MainKt")
}

kotlin {
    jvmToolchain(21)
}
