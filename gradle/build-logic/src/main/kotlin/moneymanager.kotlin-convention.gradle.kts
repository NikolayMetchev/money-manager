import dev.detekt.gradle.Detekt
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.artifacts.DependencySubstitutions
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.squareup.sort-dependencies")
    id("dev.detekt")
    id("org.jetbrains.kotlinx.kover")
    id("org.jlleitschuh.gradle.ktlint")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

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
fun DependencySubstitutions.substitute(coordinate: String, versionRef: String) {
    substitute(module(coordinate))
        .using(module("$coordinate:${libs.findVersion(versionRef).get().requiredVersion}"))
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
            substitute(libs.findLibrary("androidx-activity").get())
            substitute(libs.findLibrary("androidx-test-runner").get())
            substitute(libs.findLibrary("diamondedge-logging").get())
            substitute(libs.findLibrary("slf4j-api").get())
            substitute(libs.findLibrary("kotlinx-coroutines-core").get())
            substitute(libs.findLibrary("kotlinx-serialization-core").get())

            // Transitive-only coordinates pinned to a catalog [versions] entry.
            substitute("androidx.annotation:annotation", "androidx-annotation")
            substitute("androidx.annotation:annotation-jvm", "androidx-annotation")
            substitute("androidx.collection:collection", "androidx-collection")
            substitute("androidx.concurrent:concurrent-futures", "androidx-concurrent-futures")
            substitute("androidx.lifecycle:lifecycle-common", "androidx-lifecycle")
            substitute("androidx.lifecycle:lifecycle-common-jvm", "androidx-lifecycle")
            substitute("androidx.lifecycle:lifecycle-runtime", "androidx-lifecycle")
            substitute("androidx.lifecycle:lifecycle-runtime-android", "androidx-lifecycle")
            substitute("androidx.lifecycle:lifecycle-runtime-compose", "androidx-lifecycle")
            substitute("androidx.lifecycle:lifecycle-runtime-compose-android", "androidx-lifecycle")
            substitute("androidx.lifecycle:lifecycle-runtime-ktx", "androidx-lifecycle")
            substitute("androidx.lifecycle:lifecycle-runtime-ktx-android", "androidx-lifecycle")
            substitute("androidx.navigationevent:navigationevent", "androidx-navigationevent")
            substitute("androidx.navigationevent:navigationevent-compose", "androidx-navigationevent")
            substitute("androidx.savedstate:savedstate", "androidx-savedstate")
            substitute("androidx.savedstate:savedstate-compose", "androidx-savedstate")
            substitute("androidx.test.services:storage", "androidx-test-services-storage")
            substitute("androidx.tracing:tracing", "androidx-tracing")
            substitute("org.jetbrains:annotations", "jetbrains-annotations")
            substitute("org.jetbrains.kotlinx:atomicfu", "atomicfu")
            substitute("org.jetbrains.kotlinx:atomicfu-jvm", "atomicfu")
            substitute("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm", "kotlinx-coroutines")
            substitute("org.jetbrains.kotlinx:kotlinx-coroutines-android", "kotlinx-coroutines")
            substitute("org.jetbrains.kotlinx:kotlinx-coroutines-bom", "kotlinx-coroutines")
            substitute("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm", "kotlinx-serialization")
            substitute("org.jetbrains.kotlinx:kotlinx-serialization-json", "kotlinx-serialization")
            substitute("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm", "kotlinx-serialization")
            substitute("org.jetbrains.kotlinx:kotlinx-serialization-bom", "kotlinx-serialization")
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
            libs.findBundle("build-tool-pins").get().get().forEach { substitute(it) }
        }
    }
}

// Set group based on project path
// Example: :app:model:core -> app.model.core
group = project.path.removePrefix(":").replace(":", ".")

tasks {
    val jvmTargetVersion = libs.findVersion("jvm-target").get().toString()

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
