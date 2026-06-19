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
