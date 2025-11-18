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

dependencyAnalysis {
    issues {
        all {
            onAny {
                // Ignore warnings related to compose.desktop.currentOs
                // This dependency provides platform-specific Skiko natives required for Compose Desktop
                exclude("org.jetbrains.compose.desktop:desktop-jvm-windows-x64")

                // Ignore suggestions to add transitive Compose dependencies directly
                // We use compose.desktop.currentOs which properly handles these
                exclude("org.jetbrains.compose.runtime:runtime-desktop")
                exclude("org.jetbrains.compose.ui:ui-desktop")
                exclude("org.jetbrains.compose.ui:ui-graphics-desktop")
                exclude("org.jetbrains.compose.ui:ui-unit-desktop")
            }
        }
    }
}
