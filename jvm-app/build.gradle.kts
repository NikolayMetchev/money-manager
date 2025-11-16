plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
}

application {
    mainClass.set("com.moneymanager.MainKt")
}

kotlin {
    jvmToolchain(21)
}
