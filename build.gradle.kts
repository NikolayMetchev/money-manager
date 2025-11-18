plugins {
    alias(libs.plugins.dependency.analysis)
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "com.autonomousapps.dependency-analysis")
}
