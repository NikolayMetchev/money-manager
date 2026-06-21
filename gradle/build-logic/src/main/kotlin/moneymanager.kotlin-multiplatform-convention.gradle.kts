import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    id("moneymanager.kotlin-convention")
    id("org.jetbrains.kotlin.multiplatform")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

configure<KotlinMultiplatformExtension> {
    jvm()

    jvmToolchain(libs.findVersion("jvm-toolchain").get().toString().toInt())

    sourceSets {
        getByName("jvmMain") {
            dependencies {
                runtimeOnly(libs.findLibrary("kotlinx-coroutines-swing").get())
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
    setOf(":app:model:core", ":app:db:core", ":app:di:core", ":app:importer", ":test:app:db")
if (project.path !in writeRepositoryExemptModules) {
    val verifyNoWriteRepositoryUsage =
        tasks.register<VerifyNoWriteRepositoryUsageTask>("verifyNoWriteRepositoryUsage") {
            group = "verification"
            description = "Fails if this module references a *WriteRepository (writes must go through the ImportEngine)."
            projectPath.set(project.path)
            allowedFileNames.set(emptySet())
            // Scan only main source sets — test code may legitimately seed fixtures via write repositories.
            listOf("commonMain", "jvmMain", "androidMain").forEach { sourceSet ->
                sources.from(fileTree("src/$sourceSet") { include("**/*.kt") })
            }
        }
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(verifyNoWriteRepositoryUsage)
    }
}
