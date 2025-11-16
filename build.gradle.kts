plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.metro) apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
