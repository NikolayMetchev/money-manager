plugins {
    alias(libs.plugins.gradle.doctor)
    alias(libs.plugins.kover)
}

// Read version from system property, project property, or VERSION file
// Priority: -Dversion=X > -Pversion=X > VERSION file > "unspecified"
val versionFile = rootProject.file("VERSION")
val projectVersion = System.getProperty("version")
    ?: (project.findProperty("version") as? String)?.takeIf { it != "unspecified" }
    ?: if (versionFile.exists()) {
        versionFile.readText().trim()
    } else {
        "unspecified"
    }

allprojects {
    version = projectVersion

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

// Create root build task that builds all subprojects and runs buildHealth
tasks.register("build") {
    description = "Builds all subprojects and runs buildHealth"
    group = "build"
    dependsOn(subprojects.mapNotNull { it.tasks.findByName("build") })
    dependsOn("buildHealth")
    dependsOn("koverXmlReport")
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
                }
            }
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
    if (path.startsWith(":test") || project.file("src/test").exists().not()) {
        disable()
    }
    reports {
        filters {
            excludes {
                // Exclude generated code
                classes("*_Factory", "*_Factory\$*")
                classes("*_Impl", "*_Impl\$*")
                classes("*MapperImpl")
                // Exclude Metro DI generated code
                classes("*Component\$*")
                classes("*Module\$*")
                // Exclude SQLDelight generated code
                classes("com.moneymanager.database.*")
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

