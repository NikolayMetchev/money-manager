plugins {
    alias(libs.plugins.gradle.doctor) apply false
    alias(libs.plugins.kover)
}

// Read version from system property, project property, or VERSION file
// Priority: -Dversion=X > -Pversion=X > VERSION file > "unspecified"
val versionFile = rootDir.resolve("VERSION")
val projectVersion = System.getProperty("version")
    ?: (project.findProperty("version") as? String)?.takeIf { it != "unspecified" }
    ?: if (versionFile.exists()) {
        versionFile.readText().trim()
    } else {
        "unspecified"
    }

version = projectVersion

// gradle-doctor configures tasks/plugins across subprojects from the root project,
// which violates project isolation — keep it off while isolation is enabled.
if (providers.gradleProperty("org.gradle.unsafe.isolated-projects").orNull != "true") {
    pluginManager.apply("com.osacky.doctor")
}

tasks.register("build") {
    description = "Builds all subprojects and runs buildHealth"
    group = "build"
    dependsOn("buildHealth")
    dependsOn("koverXmlReport")
}

tasks.register("lintFormat") {
    description = "Runs all formatting tasks"
    group = "formatting"
}

dependencyAnalysis {
    issues {
        all {
            onAny {
                severity("fail")
            }
            // Kotlin 2.4 surfaces the JUnit framework artifact that kotlin("test")
            // auto-selects as a used transitive in every platform test source set.
            // It is an implementation detail of kotlin("test"), not a direct dependency.
            onUsedTransitiveDependencies {
                exclude("org.jetbrains.kotlin:kotlin-test-junit")
            }
        }

        project(":app:di:core") {
            ignoreSourceSet("commonTest")
        }

        project(":test:app:db") {
            ignoreSourceSet("commonTest")
        }

        project(":app:db:core") {
            sourceSet("commonTest") {
                onIncorrectConfiguration {
                    exclude(":app:model:core")
                    exclude(":app:importmodel")
                }
            }
        }

        // importmodel is a pure model module with no test sources; the multiplatform convention still
        // injects kotlin("test") into commonTest, which DAGP then flags as unused.
        project(":app:importmodel") {
            ignoreSourceSet("commonTest")
        }

        project(":utils:compose:scrollbar") {
            ignoreSourceSet("commonTest", "jvmDev")
        }

        project(":utils:compose:filePicker") {
            ignoreSourceSet("jvmDev")
        }

        project(":app:ui:core") {
            ignoreSourceSet("jvmDev")
            sourceSet("commonTest") {
                onUnusedDependencies {
                    exclude("org.jetbrains.kotlin:kotlin-test")
                }
            }
            sourceSet("jvmTest") {
                onUnusedDependencies {
                    // Compose desktop UI tests need the current OS Skiko native runtime in CI.
                    exclude("org.jetbrains.compose.desktop:desktop-jvm-linux-arm64")
                    exclude("org.jetbrains.compose.desktop:desktop-jvm-linux-x64")
                    exclude("org.jetbrains.compose.desktop:desktop-jvm-macos-arm64")
                    exclude("org.jetbrains.compose.desktop:desktop-jvm-macos-x64")
                    exclude("org.jetbrains.compose.desktop:desktop-jvm-windows-x64")
                }
            }
        }
    }
}

kover {
    currentProject { sources { excludeJava = true } }
    if (path.startsWith(":test") || (path != ":" && project.file("src/test").exists().not())) {
        disable()
    }
    reports {
        filters {
            excludes {
                // Exclude generated code
                classes("*_Factory", "*_Factory$*")
                classes("*_Impl", "*_Impl$*")
                classes("*MapperImpl")
                // Exclude Metro DI generated code
                classes("*Component$*")
                classes("*Module$*")
                // Exclude SQLDelight generated code
                classes("com.moneymanager.database.*")
                // Exclude test helper modules — they live in commonMain but are not production code
                packages("com.moneymanager.test")
            }
        }
        total {
            html {
                title = "Money Manager Code Coverage"
            }
            xml {
                title = "Money Manager Code Coverage"
            }
        }
    }
}

