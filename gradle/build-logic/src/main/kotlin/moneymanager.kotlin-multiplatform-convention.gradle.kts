import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    alias(conventions.plugins.moneymanager.kotlin.convention)
    alias(libs.plugins.kotlin.multiplatform)
}

configure<KotlinMultiplatformExtension> {
    jvm()

    jvmToolchain(libs.versions.jvm.toolchain.get().toInt())

    sourceSets {
        getByName("jvmMain") {
            dependencies {
                runtimeOnly(libs.kotlinx.coroutines.swing)
            }
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

/**
 * Enforces the read/write repository split: only the ImportEngine may use a `*WriteRepository`, so every
 * other module must route writes through the engine. Exempted are the infrastructure modules that
 * legitimately define the interfaces (model), implement them (db), inject them (di — including the one
 * documented device-bootstrap exception), and the engine itself (the sole writer).
 */
val writeRepositoryExemptModules =
    setOf(
        ":app:model:repository:write",
        ":app:db:core",
        ":app:db:write",
        ":app:di:core",
        ":app:db:di",
        ":test:app:db",
    )
// The importer module is scanned, but its sole writer — ImportEngineImpl — is allowed by exact path so a
// new importer file can't quietly re-inject a write repository.
val allowedWriteRepositoryPathsByModule =
    mapOf(":app:importer" to setOf("src/commonMain/kotlin/com/moneymanager/importer/ImportEngineImpl.kt"))
if (project.path !in writeRepositoryExemptModules) {
    val verifyNoWriteRepositoryUsage =
        tasks.register<VerifyNoWriteRepositoryUsageTask>("verifyNoWriteRepositoryUsage") {
            group = "verification"
            description = "Fails if this module references a *WriteRepository (writes must go through the ImportEngine)."
            projectPath.set(project.path)
            projectDirectory.set(layout.projectDirectory)
            allowedFilePaths.set(allowedWriteRepositoryPathsByModule[project.path].orEmpty())
            // Scan only main source sets — test code may legitimately seed fixtures via write repositories.
            listOf("commonMain", "jvmMain", "androidMain").forEach { sourceSet ->
                sources.from(fileTree("src/$sourceSet") { include("**/*.kt") })
            }
        }
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(verifyNoWriteRepositoryUsage)
    }
}
