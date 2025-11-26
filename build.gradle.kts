allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

subprojects {
    // Make check task depend on detekt to run it as part of the build
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(tasks.matching { it.name == "detekt" })
    }
}

tasks.register("lintFormat") {
    description = "Runs all formatting tasks"
    group = "formatting"
    dependsOn(subprojects.mapNotNull { it.tasks.findByName("sortDependencies") })
    dependsOn(subprojects.mapNotNull { it.tasks.findByName("ktlintFormat") })
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

