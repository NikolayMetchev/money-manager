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

// TEMPORARY workaround for a DAGP 3.16.0 regression, filed upstream as
// autonomousapps/dependency-analysis-gradle-plugin#1749. On Kotlin Multiplatform, 3.16.0 no
// longer recognizes that commonMain project dependencies are used, so it floods every KMP module
// with false "unused dependency" advice for commonMain project deps and matching "declare
// directly" advice across the platform source sets (~678 lines). The pre-existing
// kotlin-metadata-jvm override does not address it (the failure persists with the metadata read
// successfully), and no fixed DAGP release exists yet. Until one ships, exclude project
// dependencies from these two categories; external-library analysis is unaffected. Revert by
// deleting this list and the two exclude loops below once #1749 is fixed.
val dagp1749ProjectPaths = allprojects.map { it.path }.filter { it != ":" }

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
                // DAGP #1749: false "declare directly" advice for commonMain project deps.
                dagp1749ProjectPaths.forEach { exclude(it) }
            }
            // DAGP #1749: false "unused" advice for commonMain project deps.
            onUnusedDependencies {
                dagp1749ProjectPaths.forEach { exclude(it) }
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
                    exclude(":app:importengineapi")
                }
            }
        }

        // importengineapi is a pure model/interface module with no test sources; the multiplatform
        // convention still injects kotlin("test") into commonTest, which DAGP then flags as unused.
        project(":app:importengineapi") {
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
            sourceSet("commonMain") {
                onUnusedDependencies {
                    // CloudStorageCard references GOOGLE_DRIVE_PROVIDER_ID / GOOGLE_DRIVE_FOLDER_NAME,
                    // which are `const val` and get inlined, so DAGP sees no remaining binary reference
                    // to the module even though the dependency is genuinely needed to compile.
                    exclude(":app:remotestorage:googledrive")
                }
            }
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

        // The Compose hot-reload plugin's `jvmDev` source set produces unused-dependency noise
        // (compose-desktop, hot-reload-runtime-api) in every split-out Compose UI module.
        listOf(
            ":app:ui:components",
            ":app:ui:audit",
            ":app:ui:people",
            ":app:ui:imports:api",
            ":app:ui:imports:qif",
            ":test:app:ui",
        ).forEach { p -> project(p) { ignoreSourceSet("jvmDev") } }

        // Modules whose jvmTest uses `compose.desktop.currentOs` pull in the per-OS Skiko native
        // artifacts, which DAGP flags as unused (only the current OS one is used in CI). They also
        // get the `jvmDev` hot-reload noise.
        listOf(
            ":app:ui:foundation",
            ":app:ui:accounts",
            ":app:ui:categories",
            ":app:ui:currencies",
            ":app:ui:transactions",
            ":app:ui:imports:csv",
        ).forEach { p ->
            project(p) {
                ignoreSourceSet("jvmDev")
                sourceSet("jvmTest") {
                    onUnusedDependencies {
                        exclude("org.jetbrains.compose.desktop:desktop-jvm-linux-arm64")
                        exclude("org.jetbrains.compose.desktop:desktop-jvm-linux-x64")
                        exclude("org.jetbrains.compose.desktop:desktop-jvm-macos-arm64")
                        exclude("org.jetbrains.compose.desktop:desktop-jvm-macos-x64")
                        exclude("org.jetbrains.compose.desktop:desktop-jvm-windows-x64")
                    }
                }
            }
        }

        project(":app:ui:settings") {
            ignoreSourceSet("jvmDev")
            sourceSet("commonMain") {
                onUnusedDependencies {
                    // CloudStorageCard references GOOGLE_DRIVE_PROVIDER_ID / GOOGLE_DRIVE_FOLDER_NAME,
                    // which are `const val` and get inlined, so DAGP sees no remaining binary reference
                    // to the module even though the dependency is genuinely needed to compile.
                    exclude(":app:remotestorage:googledrive")
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

