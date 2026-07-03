import dev.detekt.gradle.Detekt
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.artifacts.DependencySubstitutions
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.sort.dependencies)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
    alias(libs.plugins.ktlint)
}

// "group:name:version" coordinate for a catalog library (catalog deps only carry module + version).
val MinimalExternalModuleDependency.versionedModule: String
    get() = "$module:${versionConstraint.requiredVersion}"

// Pin a catalog library to its catalog version.
fun DependencySubstitutions.substitute(dependency: MinimalExternalModuleDependency) {
    substitute(module(dependency.module.toString()))
        .using(module(dependency.versionedModule))
}

fun DependencySubstitutions.substitute(dependency: Provider<MinimalExternalModuleDependency>) {
    substitute(dependency.get())
}

// Pin an arbitrary (transitive-only) module coordinate to a catalog [versions] version.
fun DependencySubstitutions.substitute(coordinate: String, version: Provider<String>) {
    substitute(module(coordinate))
        .using(module("$coordinate:${version.get()}"))
}

// Align divergent transitive deps to a single version across every module's compile/runtime/lint
// classpaths and KMP source-set metadata. Modules that pull these only transitively (e.g.
// utils/compose/scrollbar via compose-ui) otherwise resolve older versions than the assembled app;
// this pins them to the catalog version. Scoped to app classpaths/metadata so Gradle and test tooling
// classpaths (kotlin compiler, detekt, kover, ktlint, unified-test-platform, compose hot reload,
// schemaspy) are untouched. Add one substitute(...) line per dependency to align.
configurations.matching {
    it.name.endsWith("CompileClasspath") ||
        it.name.endsWith("RuntimeClasspath") ||
        it.name.endsWith("LintChecksClasspath") ||
        it.name.endsWith("DependenciesMetadata")
}.all {
    resolutionStrategy {
        dependencySubstitution {
            // Catalog libraries pinned to their catalog version.
            substitute(libs.androidx.activity.asProvider())
            substitute(libs.androidx.test.runner)
            substitute(libs.diamondedge.logging)
            substitute(libs.slf4j.api)
            substitute(libs.kotlinx.coroutines.core)
            substitute(libs.kotlinx.serialization.core)

            // Transitive-only coordinates pinned to a catalog [versions] entry.
            substitute("androidx.annotation:annotation", libs.versions.androidx.annotation)
            substitute("androidx.annotation:annotation-jvm", libs.versions.androidx.annotation)
            substitute("androidx.collection:collection", libs.versions.androidx.collection)
            substitute("androidx.concurrent:concurrent-futures", libs.versions.androidx.concurrent.futures)
            substitute("androidx.lifecycle:lifecycle-common", libs.versions.androidx.lifecycle)
            substitute("androidx.lifecycle:lifecycle-common-jvm", libs.versions.androidx.lifecycle)
            substitute("androidx.lifecycle:lifecycle-runtime", libs.versions.androidx.lifecycle)
            substitute("androidx.lifecycle:lifecycle-runtime-android", libs.versions.androidx.lifecycle)
            substitute("androidx.lifecycle:lifecycle-runtime-compose", libs.versions.androidx.lifecycle)
            substitute("androidx.lifecycle:lifecycle-runtime-compose-android", libs.versions.androidx.lifecycle)
            substitute("androidx.lifecycle:lifecycle-runtime-ktx", libs.versions.androidx.lifecycle)
            substitute("androidx.lifecycle:lifecycle-runtime-ktx-android", libs.versions.androidx.lifecycle)
            substitute("androidx.navigationevent:navigationevent", libs.versions.androidx.navigationevent)
            substitute("androidx.navigationevent:navigationevent-compose", libs.versions.androidx.navigationevent)
            substitute("androidx.savedstate:savedstate", libs.versions.androidx.savedstate)
            substitute("androidx.savedstate:savedstate-compose", libs.versions.androidx.savedstate)
            substitute("androidx.test.services:storage", libs.versions.androidx.test.services.storage)
            substitute("androidx.tracing:tracing", libs.versions.androidx.tracing)
            substitute("org.jetbrains:annotations", libs.versions.jetbrains.annotations)
            substitute("org.jetbrains.kotlinx:atomicfu", libs.versions.atomicfu)
            substitute("org.jetbrains.kotlinx:atomicfu-jvm", libs.versions.atomicfu)
            substitute("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm", libs.versions.kotlinx.coroutines)
            substitute("org.jetbrains.kotlinx:kotlinx-coroutines-android", libs.versions.kotlinx.coroutines)
            substitute("org.jetbrains.kotlinx:kotlinx-coroutines-bom", libs.versions.kotlinx.coroutines)
            substitute("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm", libs.versions.kotlinx.serialization)
            substitute("org.jetbrains.kotlinx:kotlinx-serialization-json", libs.versions.kotlinx.serialization)
            substitute("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm", libs.versions.kotlinx.serialization)
            substitute("org.jetbrains.kotlinx:kotlinx-serialization-bom", libs.versions.kotlinx.serialization)
        }
    }
}

// Align build-time/tooling transitive deps (AGP test platform, annotation processors, etc.) across
// every configuration in this module, sourced from the catalog build-tool-pins bundle. The bundle is
// the safelist, so kotlin compiler/stdlib/dagger (not listed) are left untouched even though this
// applies to all configs.
configurations.all {
    resolutionStrategy {
        dependencySubstitution {
            libs.bundles.build.tool.pins.get().forEach { substitute(it) }
        }
    }
}

// Set group based on project path
// Example: :app:model:core -> app.model.core
group = project.path.removePrefix(":").replace(":", ".")

tasks {
    val jvmTargetVersion = libs.versions.jvm.target.get()

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            allWarningsAsErrors.set(true)
            jvmTarget.set(JvmTarget.fromTarget(jvmTargetVersion))
        }
    }

    withType<Detekt>().configureEach {
        // Exclude generated code from analysis
        exclude { it.file.absolutePath.contains("/build/generated/") }
    }

    withType<Test>().configureEach {
        // Test classes are distributed across forked JVMs; suites here are isolated (own temp-dir
        // DBs, no shared state). Compose Desktop UI modules override this back to 1 in
        // moneymanager.compose-multiplatform-convention (Skiko/AWT tests flake under contention).
        // -PtestMaxParallelForks=N overrides for debugging (e.g. =1 to serialize).
        maxParallelForks = providers.gradleProperty("testMaxParallelForks").orNull
            ?.let { it.toIntOrNull() ?: error("Invalid -PtestMaxParallelForks value '$it': expected an integer") }
            ?: Runtime.getRuntime().availableProcessors().coerceAtMost(8)
    }

    withType<JavaCompile>().configureEach {
        targetCompatibility = jvmTargetVersion
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }

    // Make detekt aggregate task include KMP source-set tasks without invoking
    // compilation-based type resolution, which currently reports false
    // expect/actual compiler errors for shared JVM/Android source sets.
    named("detekt") {
        dependsOn(
            matching {
                it.name.startsWith("detekt") &&
                    it.name.endsWith("SourceSet") &&
                    !it.name.startsWith("detektBaseline")
            },
        )
    }
}

detekt {
    config.setFrom(rootDir.resolve("detekt.yml"))
    buildUponDefaultConfig = true
    allRules = false
}

ktlint {
    android.set(false)
    // Allow CI to ignore failures when auto-formatting via system property
    // Usage: ./gradlew lintFormat -Dktlint.ignoreFailures=true
    ignoreFailures.set(System.getProperty("ktlint.ignoreFailures", "false").toBoolean())

    // Exclude generated files from linting
    filter {
        exclude { entry ->
            entry.file.toString().contains("generated")
        }
    }
}

configure<KoverProjectExtension> {
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
                classes("com.moneymanager.database.sql.*")
            }
        }
    }
}
