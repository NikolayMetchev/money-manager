buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath(libs.android.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.dependency.analysis)
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.android.application) apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "com.autonomousapps.dependency-analysis")

    // Make check task depend on detekt to run it as part of the build
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(tasks.matching { it.name == "detekt" })
    }
}

dependencyAnalysis {
    issues {
        all {
            onAny {
                severity("fail")

                // Ignore warnings for platform-specific Skiko natives
                // We bundle all platforms to create cross-platform distributions
                exclude("org.jetbrains.compose.desktop:desktop-jvm-windows-x64")
                exclude("org.jetbrains.compose.desktop:desktop-jvm-macos-x64")
                exclude("org.jetbrains.compose.desktop:desktop-jvm-macos-arm64")
                exclude("org.jetbrains.compose.desktop:desktop-jvm-linux-x64")
                exclude("org.jetbrains.compose.desktop:desktop-jvm-linux-arm64")
            }
        }
    }
}

// Run buildHealth as part of the build task
subprojects {
    tasks.matching { it.name == "build" }.configureEach {
        finalizedBy(rootProject.tasks.named("buildHealth"))
    }
}
