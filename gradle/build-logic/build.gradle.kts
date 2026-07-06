import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
    kotlin("jvm") version libs.versions.kotlin.get()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.jvm.toolchain.get().toInt()))
    }
    targetCompatibility = JavaVersion.toVersion(libs.versions.jvm.target.get().toInt())
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        allWarningsAsErrors.set(true)
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvm.target.get()))
        // Metro 1.3.0 marks its Gradle `analyzeMetroGraph` task (AnalyzeGraphTask) with
        // @ExperimentalMetroGradleApi. The kotlin-dsl type-safe accessor generated for that task
        // trips -Werror, so opt in here even though we don't call the task ourselves.
        optIn.add("dev.zacsweers.metro.gradle.ExperimentalMetroGradleApi")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

// Align build-time/tooling transitive deps on build-logic's own plugin classpath, sourced from the
// catalog build-tool-pins bundle (same bundle used by moneymanager.kotlin-convention).
val buildToolPins = extensions.getByType<VersionCatalogsExtension>().named("libs")
    .findBundle("build-tool-pins").get().get()
configurations.all {
    resolutionStrategy {
        dependencySubstitution {
            buildToolPins.forEach { dep ->
                substitute(module(dep.module.toString()))
                    .using(module("${dep.module}:${dep.versionConstraint.requiredVersion}"))
            }
        }
    }
}

dependencies {
    // develocity is consumed as an API by BuildScan.kt (not applied as a plugin), so it stays explicit.
    compileOnly(libs.develocity.gradle.plugin)
    // Every other plugin marker is contributed automatically by the typesafe-conventions plugin when a
    // convention plugin applies it via `alias(libs.plugins.*)`, so those manual entries are redundant.
    // sqldelight is applied directly in module build scripts (no convention plugin), so it stays.
    implementation(libs.sqldelight.gradle.plugin)
}
