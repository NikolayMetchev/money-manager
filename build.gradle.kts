import dev.iurysouza.modulegraph.LinkText
import dev.iurysouza.modulegraph.Orientation
import dev.iurysouza.modulegraph.Theme

plugins {
    base
    alias(libs.plugins.gradle.doctor) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.module.graph)
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

tasks.register("lintFormat") {
    description = "Runs all formatting tasks"
    group = "formatting"
}

// One package, one module. Split packages defeat the point of the module split: `internal` silently
// leaks across a boundary the compiler can no longer police, IDE navigation blurs, and it stops being
// obvious which module owns a type. Test source sets are exempt — they compile separately, so sharing
// a package there costs nothing.
//
// This has to live on the root project: it is the only place that can compare each module's packages
// against every other's. It reads sources straight off disk rather than querying sibling projects, so
// it stays compatible with project isolation.
tasks.register("verifyUniquePackages") {
    group = "verification"
    description = "Fails if two modules declare the same Kotlin package in their main sources."

    // Captured as a plain File so the action holds no reference to the build script itself, which the
    // configuration cache cannot serialize.
    val repositoryRoot = layout.projectDirectory.asFile

    doLast {
        val packageRegex = Regex("""^package ([\w.]+)$""", RegexOption.MULTILINE)
        val owners = mutableMapOf<String, MutableSet<String>>()

        repositoryRoot
            .walkTopDown()
            .onEnter { it.name !in setOf("build", ".git", ".gradle", ".idea", ".claude") }
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val relativePath = file.relativeTo(repositoryRoot).invariantSeparatorsPath
                val sourceSet = relativePath.substringAfter("/src/", "").substringBefore('/')
                if (!sourceSet.endsWith("Main")) return@forEach

                val module = generateSequence(file.parentFile) { it.parentFile }
                    .takeWhile { it.startsWith(repositoryRoot) }
                    .firstOrNull { it.resolve("build.gradle.kts").isFile }
                    ?.relativeTo(repositoryRoot)
                    ?.invariantSeparatorsPath
                    ?.ifEmpty { null }
                    ?: return@forEach

                val declaredPackage = packageRegex.find(file.readText())?.groupValues?.get(1) ?: return@forEach
                owners.getOrPut(declaredPackage) { mutableSetOf() }.add(module)
            }

        val split = owners.filterValues { it.size > 1 }.toSortedMap()
        require(split.isEmpty()) {
            buildString {
                appendLine("A Kotlin package must belong to exactly one module, but these are split:")
                split.forEach { (declaredPackage, modules) ->
                    appendLine("  $declaredPackage")
                    modules.sorted().forEach { appendLine("      $it") }
                }
                appendLine("Give each module its own package (e.g. append the module name as a sub-package).")
            }
        }
    }
}

tasks.named("check") {
    dependsOn("verifyUniquePackages")
}

// `createModuleGraph` writes a Mermaid module-dependency graph into this Markdown file.
// The webpage/modules.html page fetches that Markdown and renders the graph client-side,
// and the static-Pages workflow regenerates it before publishing.
moduleGraphConfig {
    readmePath.set("${rootDir}/webpage/modules-graph.md")
    heading.set("## Module dependency graph")
    orientation.set(Orientation.LEFT_TO_RIGHT)
    theme.set(Theme.NEUTRAL)
    // Label each edge with its originating Gradle configuration (e.g. commonMainImplementation,
    // jvmTestImplementation) so the viewer can filter production vs. test dependencies.
    linkText.set(LinkText.CONFIGURATION)
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

        project(":app:model:passthrough") {
            sourceSet("commonMain") {
                onUnusedDependencies {
                    // PassThroughAccount's only use of :app:model:core is WellKnownIds, whose members are
                    // `const val` and therefore inlined — no binary reference survives for DAGP to see,
                    // even though the module does not compile without the dependency.
                    exclude(":app:model:core")
                }
            }
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
            ":app:ui:imports:importDirectory",
            ":app:ui:imports:qif",
            ":app:ui:imports:timeline",
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

